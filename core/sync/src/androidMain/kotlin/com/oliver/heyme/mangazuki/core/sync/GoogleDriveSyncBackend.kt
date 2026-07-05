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
private const val METADATA_ALIAS_FILE_NAME = "metadata_aliases.json"
private const val WIRE_FORMAT_VERSION = 1

/**
 * Google Drive `appDataFolder` transport (PLAN.md §10) -- one JSON blob per synced entity, not
 * an event log (realistic volume, a few thousand rows at most, comfortably fits one file each).
 * `push` always uploads the full reconciled set; a single-blob transport has no notion of
 * "append." Hand-written against the Drive REST API (verified live against Google's own docs,
 * not assumed) rather than the `google-api-client` SDK, matching how
 * `AniListMetadataProvider`/`KitsuMetadataProvider` talk to their own APIs. `appDataFolder` is
 * a hidden per-app bucket inside the user's own Drive -- invisible in their normal Drive UI.
 *
 * Implements both [SyncBackend] (`progress.json`) and [MetadataAliasBackend]
 * (`metadata_aliases.json`) -- same auth/HTTP plumbing, just a different file name and DTO, so
 * one instance (shared between both `ProgressSyncCoordinator` constructor params) is enough.
 */
class GoogleDriveSyncBackend(
    private val auth: GoogleAuthManager,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    },
) : SyncBackend, MetadataAliasBackend {

    override suspend fun pull(since: SyncCursor?): List<ProgressRecord> {
        val token = auth.accessToken() ?: return emptyList()
        val fileId = findFileId(token, PROGRESS_FILE_NAME) ?: return emptyList()
        val response = getFile(token, fileId)
        if (!response.status.isSuccess()) return emptyList()
        return response.body<SyncFileDto>().records.map { it.toProgressRecord() }
    }

    override suspend fun push(changes: List<ProgressRecord>): SyncCursor {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        upsertFile(token, PROGRESS_FILE_NAME, SyncFileDto(WIRE_FORMAT_VERSION, changes.map { it.toDto() }))
        // Decorative for this single-blob transport -- pull() always fetches the full file
        // regardless of cursor, so there's no compaction/paging logic riding on this value.
        return SyncCursor(nowEpochMillis().toString())
    }

    override suspend fun pullAliases(): List<MetadataAliasRecord> {
        val token = auth.accessToken() ?: return emptyList()
        val fileId = findFileId(token, METADATA_ALIAS_FILE_NAME) ?: return emptyList()
        val response = getFile(token, fileId)
        if (!response.status.isSuccess()) return emptyList()
        return response.body<AliasFileDto>().records.map { it.toAliasRecord() }
    }

    override suspend fun pushAliases(aliases: List<MetadataAliasRecord>) {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        upsertFile(token, METADATA_ALIAS_FILE_NAME, AliasFileDto(WIRE_FORMAT_VERSION, aliases.map { it.toDto() }))
    }

    private suspend fun findFileId(token: String, fileName: String): String? {
        val response = client.get(DRIVE_FILES_ENDPOINT) {
            parameter("spaces", "appDataFolder")
            parameter("q", "name='$fileName'")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) return null
        return response.body<DriveFileListDto>().files.firstOrNull()?.id
    }

    private suspend fun getFile(token: String, fileId: String) = client.get("$DRIVE_FILES_ENDPOINT/$fileId") {
        parameter("alt", "media")
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend inline fun <reified T> upsertFile(token: String, fileName: String, body: T) {
        val fileId = findFileId(token, fileName)
        if (fileId == null) createFile(token, fileName, body) else updateFile(token, fileId, body)
    }

    private suspend inline fun <reified T> createFile(token: String, fileName: String, body: T) {
        val created = client.post(DRIVE_FILES_ENDPOINT) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateFileRequestDto(name = fileName, parents = listOf("appDataFolder")))
        }.body<DriveFileDto>()
        val fileId = created.id ?: error("Google Drive sync: file creation returned no id")
        updateFile(token, fileId, body)
    }

    private suspend inline fun <reified T> updateFile(token: String, fileId: String, body: T) {
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
private data class AliasFileDto(val version: Int, val records: List<AliasRecordDto>)

@Serializable
private data class AliasRecordDto(
    val normalizedTitle: String,
    val provider: String,
    val externalId: String,
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

private fun MetadataAliasRecord.toDto() = AliasRecordDto(normalizedTitle, provider, externalId, updatedAt, deviceId)

private fun AliasRecordDto.toAliasRecord() = MetadataAliasRecord(normalizedTitle, provider, externalId, updatedAt, deviceId)
