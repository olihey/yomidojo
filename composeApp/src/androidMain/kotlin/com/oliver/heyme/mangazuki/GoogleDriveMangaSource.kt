package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.source.ChangeEvent
import com.oliver.heyme.mangazuki.core.source.ChangeSet
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.RandomAccessHandle
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import okio.Source
import java.net.URLEncoder

/**
 * Google Drive source via the Drive v3 REST API (PLAN.md §6.4), personal accounts. Locators are
 * **Drive file ids**, not `/`-joined paths — unlike [OneDriveMangaSource]/Microsoft Graph, Drive
 * has no `root:/{path}:`-style path addressing; every file/folder is addressed by its own opaque
 * id, and children are found via `q="'{parentId}' in parents"`. `""` (root locator) means Drive's
 * own `root` alias. [GoogleDriveConfig] and the folder-picker dialog track this the same way.
 *
 * Two HTTP stacks on purpose, same split as [OneDriveMangaSource]: Ktor for JSON metadata/
 * listing, and OkHttp directly for byte streams (its response body is already an Okio source
 * that can outlive the call, a better fit for [open]/[RandomAccessHandle.readAt] than Ktor 3's
 * scope-confined streaming). Unlike OneDrive's pre-authenticated, no-header download URLs,
 * Drive's `?alt=media` download endpoint always needs the `Authorization` header — there's no
 * separate pre-signed URL step, so [open]/[openRandomAccess] hit it directly with the current
 * bearer token, refreshing once on a 401 the same shape as OneDrive's expired-URL retry.
 *
 * Declaring RANGE_READ + populating [SourceEntry.size] is what routes CBZ chapters into
 * `CbzArchive`'s RangedBacking: two small positional reads for the central directory, then only
 * each page's own bytes — never a whole-archive download (PLAN.md §11).
 */
class GoogleDriveMangaSource(
    private val accessToken: suspend () -> String?,
    private val client: HttpClient = defaultDriveClient(),
    private val http: OkHttpClient = OkHttpClient(),
) : MangaSource {

    override val id: String = "googledrive"

    override val capabilities: Set<SourceCapability> =
        setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)

    private suspend fun bearer(): String =
        accessToken() ?: throw IOException("Google Drive: not signed in")

    /** GET with the Drive auth header plus bounded retry: 429 honors `Retry-After` (default 2s,
     * capped at 30s), 5xx retries after a short pause, anything else non-success throws. At most
     * [MAX_RETRIES] retries — same shape as [OneDriveMangaSource.graphGet]. */
    private suspend fun driveGet(url: String): HttpResponse {
        val token = bearer()
        var attempt = 0
        while (true) {
            val response = client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }
            if (response.status.isSuccess()) return response
            val status = response.status.value
            val retriable = status == 429 || status >= 500
            if (!retriable || attempt >= MAX_RETRIES) {
                throw IOException("Google Drive: HTTP $status for $url")
            }
            val waitSeconds = if (status == 429) {
                (response.headers["Retry-After"]?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
                    .coerceIn(1, MAX_RETRY_AFTER_SECONDS)
            } else {
                DEFAULT_RETRY_AFTER_SECONDS
            }
            delay(waitSeconds * 1000)
            attempt++
        }
    }

    /** Also false when signed out (null token), or when signed in under the old drive.appdata-only
     * scope (pre-2026-07-16) without `drive.readonly` — both surface as a request failure here,
     * which is exactly what routes into the existing `needsReGrant` banner with zero extra
     * plumbing (the banner's copy is generic enough to cover "sign in again" either way). */
    override suspend fun canAccess(rootLocator: String): Boolean = withContext(ioDispatcher) {
        try {
            driveGet(driveItemUrl(rootLocator))
            true
        } catch (t: Exception) {
            false
        }
    }

    override suspend fun list(path: String): List<SourceEntry> = withContext(ioDispatcher) {
        val entries = mutableListOf<SourceEntry>()
        var pageToken: String? = null
        do {
            val url = driveChildrenUrl(path) + (pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val page: GoogleDriveChildrenPageDto = driveGet(url).body()
            page.files.mapTo(entries) { it.toSourceEntry() }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        entries
    }

    override suspend fun open(locator: String): Source = withContext(ioDispatcher) {
        val response = mediaGet(locator, bearer())
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Google Drive: download failed (HTTP $code) for $locator")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Google Drive: empty download response for $locator")
        }
        GoogleClosingBodySource(body.source()) { response.close() }
    }

    /** One bearer token per handle, refreshed once on expiry, serving every
     * [RandomAccessHandle.readAt] as a plain `Range` GET against the same `?alt=media` endpoint
     * -- so a CBZ open costs at most one extra token fetch + N small range reads, no separate
     * metadata call (Drive's media endpoint takes the file id directly, unlike OneDrive's
     * pre-authenticated-URL indirection). */
    override suspend fun openRandomAccess(locator: String): RandomAccessHandle = withContext(ioDispatcher) {
        var token = bearer()
        object : RandomAccessHandle {
            override suspend fun readAt(offset: Long, length: Int): ByteArray = withContext(ioDispatcher) {
                var refreshed = false
                while (true) {
                    val response = mediaGet(locator, token, range = "bytes=$offset-${offset + length - 1}")
                    when {
                        response.code == 206 -> return@withContext response.body!!.use { body ->
                            val bytes = body.source().readByteArray()
                            if (bytes.size <= length) bytes else bytes.copyOf(length)
                        }
                        !refreshed && response.code in TOKEN_EXPIRED_CODES -> {
                            response.close()
                            token = bearer()
                            refreshed = true
                        }
                        else -> {
                            val code = response.code
                            response.close()
                            throw IOException("Google Drive: range read failed (HTTP $code) for $locator")
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

            override fun close() { /* nothing held open between reads */ }
        }
    }

    private fun mediaGet(locator: String, token: String, range: String? = null): okhttp3.Response {
        val builder = Request.Builder()
            .url("$DRIVE_FILES_URL/$locator?alt=media")
            .header("Authorization", "Bearer $token")
        if (range != null) builder.header("Range", range)
        return http.newCall(builder.build()).execute()
    }

    override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

    override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()

    private companion object {
        const val MAX_RETRIES = 2
        const val DEFAULT_RETRY_AFTER_SECONDS = 2L
        const val MAX_RETRY_AFTER_SECONDS = 30L
        val TOKEN_EXPIRED_CODES = setOf(401, 403)
    }
}

private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"

/** Same Ktor configuration as `GoogleDriveSyncBackend`: engine from the classpath (OkHttp on
 * Android), lenient JSON, manual status checks. */
private fun defaultDriveClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    expectSuccess = false
}

/** Closing an Okio source over an OkHttp body must also close the OkHttp response, or the
 * connection leaks back to the pool dirty — same pattern as [SmbMangaSource]'s ClosingSource. */
private class GoogleClosingBodySource(private val delegate: Source, private val onClose: () -> Unit) : Source by delegate {
    override fun close() {
        delegate.close()
        onClose()
    }
}

@kotlinx.serialization.Serializable
internal data class GoogleDriveChildrenPageDto(
    val files: List<GoogleDriveItemDto> = emptyList(),
    val nextPageToken: String? = null,
)

/** One Drive file/folder as the API returns it. [mimeType] is the sole, authoritative folder
 * signal (`application/vnd.google-apps.folder`) — no file-extension heuristic needed the way an
 * early draft of this class used one, since Drive always populates this field. [size] arrives as
 * a decimal string in Drive's JSON (not a number), same as everywhere else in v3's API. */
@kotlinx.serialization.Serializable
internal data class GoogleDriveItemDto(
    val id: String,
    val name: String,
    val size: String? = null,
    val mimeType: String? = null,
)

internal fun GoogleDriveItemDto.toSourceEntry(): SourceEntry {
    val isFolder = mimeType == "application/vnd.google-apps.folder"
    return SourceEntry(
        locator = id,
        name = name,
        isDirectory = isFolder,
        size = if (isFolder) null else size?.toLongOrNull(),
        // TODO: no change-detection field requested from the API yet (see the scanner's
        // skip-cache, which relies on this to avoid re-processing unchanged files) --
        // null means every scan re-processes this entry, correct but not optimized. Drive's
        // `md5Checksum`/`headRevisionId` fields would fill this in a follow-up.
        changeToken = null,
    )
}

/** `parentId` is a Drive file id (or blank for the Drive root, Drive's own `root` alias) --
 * [SourceEntry.locator]'s meaning throughout this class. `trashed=false` excludes the user's
 * trash, which Drive's API otherwise includes in a plain children listing. */
internal fun driveChildrenUrl(parentId: String): String {
    val effectiveParent = parentId.ifBlank { "root" }
    val q = URLEncoder.encode("'$effectiveParent' in parents and trashed=false", "UTF-8")
    val fields = URLEncoder.encode("nextPageToken,files(id,name,size,mimeType)", "UTF-8")
    return "$DRIVE_FILES_URL?q=$q&fields=$fields&pageSize=1000"
}

internal fun driveItemUrl(id: String): String {
    val effectiveId = id.ifBlank { "root" }
    val fields = URLEncoder.encode("id,name,size,mimeType", "UTF-8")
    return "$DRIVE_FILES_URL/$effectiveId?fields=$fields"
}
