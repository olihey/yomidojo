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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
private const val PROGRESS_FILE_NAME = "progress.json"
private const val METADATA_ALIAS_FILE_NAME = "metadata_aliases.json"
// v2 (2026-07-05): one record per SERIES with completed/in-progress chapter lists, replacing
// v1's one record per chapter -- a v1 remote file just deserializes as "no series yet" (the new
// `series` field defaults to empty, and the old `records` field is silently ignored), so each
// device's very next sync simply re-populates it from that device's own local progress. No
// migration path -- nothing local is lost, only the already-merged cross-device snapshot resets.
private const val WIRE_FORMAT_VERSION = 2
private val debugJsonPrinter = Json { prettyPrint = true }

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

    override suspend fun pull(since: SyncCursor?): List<SeriesProgressRecord> {
        val token = auth.accessToken() ?: return emptyList()
        val fileId = findFileId(token, PROGRESS_FILE_NAME) ?: return emptyList()
        val response = getFile(token, fileId)
        if (!response.status.isSuccess()) return emptyList()
        return response.body<SyncFileDto>().series.map { it.toSeriesProgressRecord() }
    }

    override suspend fun push(changes: List<SeriesProgressRecord>): SyncCursor {
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

    /** Settings' Debug section (PLAN.md §10) -- fetches a file's raw content as-is from Drive,
     * since `appDataFolder` can't be browsed any other way. Reuses [findFileId]/[getFile] rather
     * than [pull]/[pullAliases] so this shows exactly what's on Drive, not the
     * already-deserialized-and-reconstructed [ProgressRecord]/[MetadataAliasRecord] shapes. */
    suspend fun fetchRawProgressJson(): String? {
        val token = auth.accessToken() ?: return null
        return fetchRawFile(token, PROGRESS_FILE_NAME)
    }

    suspend fun fetchRawMetadataAliasesJson(): String? {
        val token = auth.accessToken() ?: return null
        return fetchRawFile(token, METADATA_ALIAS_FILE_NAME)
    }

    /** Settings' Debug section "Clear" actions (PLAN.md §10) -- overwrites the Drive copy with
     * an empty file via the existing full-snapshot [push]/[pushAliases], rather than deleting it
     * outright, so there's no separate delete-vs-recreate path to reason about; the next sync
     * from any device just re-populates it from whatever that device still has locally. */
    suspend fun clearProgress() {
        push(emptyList())
    }

    suspend fun clearMetadataAliases() {
        pushAliases(emptyList())
    }

    private suspend fun fetchRawFile(token: String, fileName: String): String? {
        val fileId = findFileId(token, fileName) ?: return null
        val response = getFile(token, fileId)
        if (!response.status.isSuccess()) return null
        val raw = response.bodyAsText()
        // Best-effort pretty-print for readability in the debug dialog -- falls back to the raw
        // (already valid, just unindented) text if this ever doesn't parse for some reason.
        return runCatching { debugJsonPrinter.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }
            .getOrDefault(raw)
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
private data class SyncFileDto(val version: Int, val series: List<SeriesRecordDto> = emptyList())

/** [completedVolumes]/[inProgressVolumes] entries are plain JSON arrays (`[volume, number]` /
 * `[volume, number, lastPageIndex]`, `volume`/`number` `null` when a chapter doesn't have that
 * dimension) rather than nested objects -- this is the whole point of the v2 format (PLAN.md
 * §10): one compact series record instead of one object per chapter. `lastPageIndex` round-trips
 * as a `Double` like the other two slots (e.g. `172.0` not `172`) so this stays a single
 * `List<List<Double?>>` shape with no custom serializer needed -- JSON doesn't distinguish
 * integer-valued numbers from other numbers anyway, so nothing is lost. */
@Serializable
private data class SeriesRecordDto(
    val provider: String? = null,
    val externalId: String? = null,
    val normalizedTitle: String,
    val completedVolumes: List<List<Double?>> = emptyList(),
    val inProgressVolumes: List<List<Double?>> = emptyList(),
    val updatedAt: Long,
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

private fun SeriesProgressRecord.toDto() = SeriesRecordDto(
    provider = key.provider,
    externalId = key.externalId,
    normalizedTitle = key.normalizedTitle,
    completedVolumes = completedVolumes.map { listOf(it.volume, it.number) },
    inProgressVolumes = inProgressVolumes.map { listOf(it.volume, it.number, it.lastPageIndex.toDouble()) },
    updatedAt = updatedAt,
)

private fun SeriesRecordDto.toSeriesProgressRecord() = SeriesProgressRecord(
    key = SeriesKey(provider, externalId, normalizedTitle),
    completedVolumes = completedVolumes.map { VolumeChapterKey(volume = it.getOrNull(0), number = it.getOrNull(1)) },
    inProgressVolumes = inProgressVolumes.map {
        InProgressVolume(volume = it.getOrNull(0), number = it.getOrNull(1), lastPageIndex = (it.getOrNull(2) ?: 0.0).toInt())
    },
    updatedAt = updatedAt,
)

private fun MetadataAliasRecord.toDto() = AliasRecordDto(normalizedTitle, provider, externalId, updatedAt, deviceId)

private fun AliasRecordDto.toAliasRecord() = MetadataAliasRecord(normalizedTitle, provider, externalId, updatedAt, deviceId)
