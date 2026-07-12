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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
private const val PROGRESS_FILE_NAME = "progress.json"
private const val METADATA_ALIAS_FILE_NAME = "metadata_aliases.json"
private const val FAVORITES_FILE_NAME = "favorites.json"
// favorites.json has its own version counter (v1, 2026-07-12) -- it didn't exist through
// progress.json's v1-v3 evolution, so inheriting WIRE_FORMAT_VERSION would fake a history.
private const val FAVORITES_WIRE_FORMAT_VERSION = 1
// v2 (2026-07-05): one record per SERIES with completed/in-progress chapter lists, replacing
// v1's one record per chapter -- a v1 remote file just deserializes as "no series yet" (the new
// `series` field defaults to empty, and the old `records` field is silently ignored), so each
// device's very next sync simply re-populates it from that device's own local progress. No
// migration path -- nothing local is lost, only the already-merged cross-device snapshot resets.
// v3 (2026-07-07): each chapter entry gains its own `updatedAt` (`volumes`, one row per chapter),
// replacing v2's `completedVolumes`/`inProgressVolumes` split that had no per-entry timestamp --
// that's what made an explicit un-read impossible to ever win a merge (PLAN.md §10). Same "no
// migration" story: a v2 file's `completedVolumes`/`inProgressVolumes` are silently ignored (the
// new `volumes` field isn't there, so it defaults to empty), and the next sync from any device
// re-populates it from that device's own local `reading_progress`, this time with real timestamps.
private const val WIRE_FORMAT_VERSION = 3
private val debugJsonPrinter = Json { prettyPrint = true }
// Lenient (unlike debugJsonPrinter) since this only validates shape before an Import overwrites
// Drive -- an older/newer wire version missing a since-added field should still pass, matching
// the same forward/backward tolerance pull()/pullAliases() already get from the shared client's
// ContentNegotiation config.
private val debugJsonParser = Json { ignoreUnknownKeys = true }

/**
 * Google Drive `appDataFolder` transport (PLAN.md §10) -- one JSON blob per synced entity, not
 * an event log (realistic volume, a few thousand rows at most, comfortably fits one file each).
 * `push` always uploads the full reconciled set; a single-blob transport has no notion of
 * "append." Hand-written against the Drive REST API (verified live against Google's own docs,
 * not assumed) rather than the `google-api-client` SDK, matching how
 * `AniListMetadataProvider`/`KitsuMetadataProvider` talk to their own APIs. `appDataFolder` is
 * a hidden per-app bucket inside the user's own Drive -- invisible in their normal Drive UI.
 *
 * Implements [SyncBackend] (`progress.json`), [MetadataAliasBackend]
 * (`metadata_aliases.json`), and [FavoritesBackend] (`favorites.json`) -- same auth/HTTP
 * plumbing, just a different file name and DTO each, so one instance (shared between the
 * `ProgressSyncCoordinator` constructor params) is enough.
 */
class GoogleDriveSyncBackend(
    private val auth: GoogleAuthManager,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    },
) : SyncBackend, MetadataAliasBackend, FavoritesBackend {

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

    override suspend fun pullFavorites(): List<FavoriteRecord> {
        val token = auth.accessToken() ?: return emptyList()
        val fileId = findFileId(token, FAVORITES_FILE_NAME) ?: return emptyList()
        val response = getFile(token, fileId)
        if (!response.status.isSuccess()) return emptyList()
        return response.body<FavoritesFileDto>().records.map { it.toFavoriteRecord() }
    }

    override suspend fun pushFavorites(favorites: List<FavoriteRecord>) {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        upsertFile(token, FAVORITES_FILE_NAME, FavoritesFileDto(FAVORITES_WIRE_FORMAT_VERSION, favorites.map { it.toDto() }))
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

    suspend fun fetchRawFavoritesJson(): String? {
        val token = auth.accessToken() ?: return null
        return fetchRawFile(token, FAVORITES_FILE_NAME)
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

    suspend fun clearFavorites() {
        pushFavorites(emptyList())
    }

    /** Settings' Debug section "Import" actions (PLAN.md §10) -- overwrites the Drive copy with
     * a user-picked backup file's exact bytes, rather than re-deriving the wire file from
     * whatever [SyncFileDto]/[AliasFileDto] would round-trip to, so what lands on Drive is
     * byte-for-byte what was exported. Still decodes into the wire DTO first purely to validate
     * shape -- a malformed picked file must fail loudly here rather than silently corrupting the
     * next device's pull. */
    suspend fun pushRawProgressJson(json: String) {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        debugJsonParser.decodeFromString<SyncFileDto>(json)
        upsertFileRaw(token, PROGRESS_FILE_NAME, json)
    }

    suspend fun pushRawMetadataAliasesJson(json: String) {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        debugJsonParser.decodeFromString<AliasFileDto>(json)
        upsertFileRaw(token, METADATA_ALIAS_FILE_NAME, json)
    }

    suspend fun pushRawFavoritesJson(json: String) {
        val token = auth.accessToken() ?: error("Google Drive sync: not signed in")
        debugJsonParser.decodeFromString<FavoritesFileDto>(json)
        upsertFileRaw(token, FAVORITES_FILE_NAME, json)
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

    /** Raw-string counterparts of [upsertFile]/[createFile]/[updateFile] for [pushRawProgressJson]/
     * [pushRawMetadataAliasesJson] -- a `String` body bypasses `ContentNegotiation`'s serializer
     * (it's one of the types the plugin passes through untouched) and is sent as-is, so this
     * uploads the picked file's exact text rather than a DTO re-encoding of it. */
    private suspend fun upsertFileRaw(token: String, fileName: String, raw: String) {
        val fileId = findFileId(token, fileName)
        if (fileId == null) createFileRaw(token, fileName, raw) else updateFileRaw(token, fileId, raw)
    }

    private suspend fun createFileRaw(token: String, fileName: String, raw: String) {
        val created = client.post(DRIVE_FILES_ENDPOINT) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateFileRequestDto(name = fileName, parents = listOf("appDataFolder")))
        }.body<DriveFileDto>()
        val fileId = created.id ?: error("Google Drive sync: file creation returned no id")
        updateFileRaw(token, fileId, raw)
    }

    private suspend fun updateFileRaw(token: String, fileId: String, raw: String) {
        client.patch("$DRIVE_UPLOAD_ENDPOINT/$fileId") {
            parameter("uploadType", "media")
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(raw)
        }
    }
}

@Serializable
private data class SyncFileDto(val version: Int, val series: List<SeriesRecordDto> = emptyList())

/** [volumes] entries are plain JSON arrays -- `[volume, number, completed, lastPageIndex,
 * updatedAt]`, `volume`/`number` `null` when a chapter doesn't have that dimension, `completed`
 * as `1.0`/`0.0` -- rather than nested objects, keeping the same compact-series-record spirit as
 * v2 (PLAN.md §10) while giving every chapter its own timestamp (v3, 2026-07-07). Everything
 * round-trips as a `Double` (e.g. `172.0` not `172`, `1737849600000.0` for a millis timestamp)
 * so this stays a single `List<List<Double?>>` shape with no custom serializer needed -- JSON
 * doesn't distinguish integer-valued numbers from other numbers anyway, and a millis timestamp
 * is nowhere near `Double`'s 2^53 exact-integer ceiling. */
@Serializable
private data class SeriesRecordDto(
    val provider: String? = null,
    val externalId: String? = null,
    val normalizedTitle: String,
    val volumes: List<List<Double?>> = emptyList(),
)

@Serializable
private data class AliasFileDto(val version: Int, val records: List<AliasRecordDto>)

@Serializable
private data class FavoritesFileDto(val version: Int, val records: List<FavoriteRecordDto> = emptyList())

@Serializable
private data class FavoriteRecordDto(
    val normalizedTitle: String,
    val provider: String? = null,
    val externalId: String? = null,
    val favorited: Boolean,
    val updatedAt: Long,
    val deviceId: String,
)

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
    volumes = volumes.map {
        listOf(it.volume, it.number, if (it.completed) 1.0 else 0.0, it.lastPageIndex.toDouble(), it.updatedAt.toDouble())
    },
)

private fun SeriesRecordDto.toSeriesProgressRecord() = SeriesProgressRecord(
    key = SeriesKey(provider, externalId, normalizedTitle),
    volumes = volumes.map {
        VolumeProgress(
            volume = it.getOrNull(0),
            number = it.getOrNull(1),
            completed = (it.getOrNull(2) ?: 0.0) != 0.0,
            lastPageIndex = (it.getOrNull(3) ?: 0.0).toInt(),
            updatedAt = (it.getOrNull(4) ?: 0.0).toLong(),
        )
    },
)

private fun MetadataAliasRecord.toDto() = AliasRecordDto(normalizedTitle, provider, externalId, updatedAt, deviceId)

private fun AliasRecordDto.toAliasRecord() = MetadataAliasRecord(normalizedTitle, provider, externalId, updatedAt, deviceId)

private fun FavoriteRecord.toDto() = FavoriteRecordDto(normalizedTitle, provider, externalId, favorited, updatedAt, deviceId)

private fun FavoriteRecordDto.toFavoriteRecord() = FavoriteRecord(normalizedTitle, provider, externalId, favorited, updatedAt, deviceId)
