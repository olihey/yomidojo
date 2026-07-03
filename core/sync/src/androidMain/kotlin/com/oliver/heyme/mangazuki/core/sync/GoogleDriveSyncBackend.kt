package com.oliver.heyme.mangazuki.core.sync

import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
private const val PROGRESS_FILE_NAME = "progress.json"
private const val WIRE_FORMAT_VERSION = 1

/**
 * Google Drive `appDataFolder` transport (PLAN.md §10) -- a single JSON blob, not an event log
 * (realistic reading_progress volume, a few thousand rows at most, comfortably fits one file).
 * `push` always uploads the full reconciled set; a single-blob transport has no notion of
 * "append." Hand-written against the Drive REST API (verified live against Google's own docs,
 * not assumed) rather than the `google-api-client` SDK, matching how
 * `AniListMetadataProvider`/`KitsuMetadataProvider` talk to their own APIs. `appDataFolder` is
 * a hidden per-app bucket inside the user's own Drive -- invisible in their normal Drive UI.
 */
class GoogleDriveSyncBackend(
    private val auth: GoogleAuthManager,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    },
) : SyncBackend {

    override suspend fun pull(since: SyncCursor?): List<ProgressRecord> {
        val token = auth.accessToken() ?: return emptyList()
        val fileId = findProgressFileId(token) ?: return emptyList()
        val response = client.get("$DRIVE_FILES_ENDPOINT/$fileId") {
            parameter("alt", "media")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body<SyncFileDto>().records.map { it.toProgressRecord() }
    }

    override suspend fun push(changes: List<ProgressRecord>): SyncCursor {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        val body = SyncFileDto(WIRE_FORMAT_VERSION, changes.map { it.toDto() })
        val fileId = findProgressFileId(token)
        if (fileId == null) createProgressFile(token, body) else updateProgressFile(token, fileId, body)
        // Decorative for this single-blob transport -- pull() always fetches the full file
        // regardless of cursor, so there's no compaction/paging logic riding on this value.
        return SyncCursor(nowEpochMillis().toString())
    }

    private suspend fun findProgressFileId(token: String): String? {
        val response = client.get(DRIVE_FILES_ENDPOINT) {
            parameter("spaces", "appDataFolder")
            parameter("q", "name='$PROGRESS_FILE_NAME'")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) return null
        return response.body<DriveFileListDto>().files.firstOrNull()?.id
    }

    private suspend fun createProgressFile(token: String, body: SyncFileDto) {
        val created = client.post(DRIVE_FILES_ENDPOINT) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateFileRequestDto(name = PROGRESS_FILE_NAME, parents = listOf("appDataFolder")))
        }.body<DriveFileDto>()
        val fileId = created.id ?: error("Google Drive sync: file creation returned no id")
        updateProgressFile(token, fileId, body)
    }

    private suspend fun updateProgressFile(token: String, fileId: String, body: SyncFileDto) {
        client.patch("$DRIVE_UPLOAD_ENDPOINT/$fileId") {
            parameter("uploadType", "media")
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
}

@Serializable
private data class SyncFileDto(val version: Int, val records: List<SyncRecordDto>)

@Serializable
private data class SyncRecordDto(
    val provider: String? = null,
    val externalId: String? = null,
    val normalizedTitle: String,
    val volume: Double? = null,
    val number: Double? = null,
    val completed: Boolean,
    val lastPageIndex: Int,
    val updatedAt: Long,
    val deviceId: String,
)

@Serializable
private data class CreateFileRequestDto(val name: String, val parents: List<String>)

@Serializable
private data class DriveFileDto(val id: String? = null)

@Serializable
private data class DriveFileListDto(val files: List<DriveFileDto> = emptyList())

private fun ProgressRecord.toDto() = SyncRecordDto(
    provider = key.provider,
    externalId = key.externalId,
    normalizedTitle = key.normalizedTitle,
    volume = key.volume,
    number = key.number,
    completed = completed,
    lastPageIndex = lastPageIndex,
    updatedAt = updatedAt,
    deviceId = deviceId,
)

private fun SyncRecordDto.toProgressRecord() = ProgressRecord(
    key = ProgressKey(provider, externalId, normalizedTitle, volume, number),
    completed = completed,
    lastPageIndex = lastPageIndex,
    updatedAt = updatedAt,
    deviceId = deviceId,
)
