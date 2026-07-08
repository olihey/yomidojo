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

/**
 * OneDrive source via Microsoft Graph (PLAN.md §6.3), personal accounts. Locators are
 * drive-root-relative `/`-joined paths ("" = drive root) — the [SmbMangaSource] convention;
 * the account identity (tokens) lives outside the instance in `MicrosoftAuthStore`, reached
 * through the injected [accessToken] supplier (refreshes transparently per call).
 *
 * Two HTTP stacks on purpose: Ktor (the `GoogleDriveSyncBackend` configuration) for Graph's
 * JSON metadata/listing, and OkHttp directly for byte streams — `ResponseBody.source()` IS an
 * `okio.BufferedSource`, a zero-copy fit for [open]'s `okio.Source` and for `Range` reads,
 * where Ktor 3 would either buffer whole bodies or confine streaming to a scope that can't
 * escape into a returned Source.
 *
 * Reads go through the item's pre-authenticated `@microsoft.graph.downloadUrl` (no auth
 * header, supports `Range`, expires after ~1h — refreshed via one metadata re-fetch when a
 * read is rejected). Declaring RANGE_READ + populating [SourceEntry.size] is what routes CBZ
 * chapters into `CbzArchive`'s RangedBacking: two small positional reads for the central
 * directory, then only each page's own bytes — never a whole-archive download (PLAN.md §11).
 */
class OneDriveMangaSource(
    private val accessToken: suspend () -> String?,
    private val client: HttpClient = defaultGraphClient(),
    private val http: OkHttpClient = OkHttpClient(),
) : MangaSource {

    override val id: String = "onedrive"

    override val capabilities: Set<SourceCapability> =
        setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)

    private suspend fun bearer(): String =
        accessToken() ?: throw IOException("OneDrive: not signed in")

    /** GET with the Graph auth header plus bounded retry: 429 honors `Retry-After` (default 2s,
     * capped at 30s), 5xx retries after a short pause, anything else non-success throws. At most
     * [MAX_RETRIES] retries — the scanner is sequential (one list per directory), so throttling
     * is a slow burn to ride out, not a burst to fight. */
    private suspend fun graphGet(url: String): HttpResponse {
        val token = bearer()
        var attempt = 0
        while (true) {
            val response = client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }
            if (response.status.isSuccess()) return response
            val status = response.status.value
            val retriable = status == 429 || status >= 500
            if (!retriable || attempt >= MAX_RETRIES) {
                throw IOException("OneDrive: HTTP $status for $url")
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

    /** Also false when signed out (null token) — which is exactly what routes token
     * revocation into the existing `needsReGrant` banner with zero extra plumbing. */
    override suspend fun canAccess(rootLocator: String): Boolean = withContext(ioDispatcher) {
        try {
            graphGet(graphItemUrl(rootLocator))
            true
        } catch (t: Exception) {
            false
        }
    }

    override suspend fun list(path: String): List<SourceEntry> = withContext(ioDispatcher) {
        val entries = mutableListOf<SourceEntry>()
        // nextLink is a complete pre-built (and pre-encoded) URL — followed verbatim.
        var url: String? = graphChildrenUrl(path) + "?\$select=name,size,file,folder,eTag&\$top=200"
        while (url != null) {
            val page: DriveChildrenPageDto = graphGet(url).body()
            page.value.mapTo(entries) { it.toSourceEntry(path) }
            url = page.nextLink
        }
        entries
    }

    /** The item's short-lived pre-authenticated download URL. No `$select` on this call:
     * `@microsoft.graph.downloadUrl` is an instance annotation Graph includes by default for
     * files, and selecting around it is exactly the kind of encoding subtlety worth avoiding. */
    private suspend fun fetchDownloadUrl(locator: String): String {
        val item: DriveItemDto = graphGet(graphItemUrl(locator)).body()
        return item.downloadUrl ?: throw IOException("OneDrive: no download URL for $locator")
    }

    override suspend fun open(locator: String): Source = withContext(ioDispatcher) {
        val url = fetchDownloadUrl(locator)
        // Deliberately not the Ktor client: the download URL is pre-authenticated (an auth
        // header must NOT be re-sent to it), and OkHttp's response body is already an Okio
        // source that can outlive this call.
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("OneDrive: download failed (HTTP $code) for $locator")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("OneDrive: empty download response for $locator")
        }
        ClosingBodySource(body.source()) { response.close() }
    }

    /** One metadata fetch per handle, its download URL serving every [RandomAccessHandle.readAt]
     * as a plain `Range` GET — so a CBZ open costs 1 Graph call + N small range reads. A read
     * rejected with 401/403/404/410 (the URL expires after ~1h) refreshes the URL once via a
     * metadata re-fetch and retries; a 200 (server ignoring Range) is treated as an error rather
     * than silently buffering a whole chapter. */
    override suspend fun openRandomAccess(locator: String): RandomAccessHandle = withContext(ioDispatcher) {
        var url = fetchDownloadUrl(locator)
        object : RandomAccessHandle {
            override suspend fun readAt(offset: Long, length: Int): ByteArray = withContext(ioDispatcher) {
                var refreshed = false
                while (true) {
                    val request = Request.Builder().url(url)
                        .header("Range", "bytes=$offset-${offset + length - 1}")
                        .build()
                    val response = http.newCall(request).execute()
                    when {
                        response.code == 206 -> return@withContext response.body!!.use { body ->
                            val bytes = body.source().readByteArray()
                            if (bytes.size <= length) bytes else bytes.copyOf(length)
                        }
                        !refreshed && response.code in URL_EXPIRED_CODES -> {
                            response.close()
                            url = fetchDownloadUrl(locator)
                            refreshed = true
                        }
                        else -> {
                            val code = response.code
                            response.close()
                            throw IOException("OneDrive: range read failed (HTTP $code) for $locator")
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

            override fun close() { /* nothing held open between reads */ }
        }
    }

    override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

    override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()

    private companion object {
        const val MAX_RETRIES = 2
        const val DEFAULT_RETRY_AFTER_SECONDS = 2L
        const val MAX_RETRY_AFTER_SECONDS = 30L
        val URL_EXPIRED_CODES = setOf(401, 403, 404, 410)
    }
}

/** Same Ktor configuration as `GoogleDriveSyncBackend`: engine from the classpath (OkHttp on
 * Android), lenient JSON, manual status checks. */
private fun defaultGraphClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    expectSuccess = false
}

/** Closing an Okio source over an OkHttp body must also close the OkHttp response, or the
 * connection leaks back to the pool dirty — same pattern as [SmbMangaSource]'s ClosingSource. */
private class ClosingBodySource(private val delegate: Source, private val onClose: () -> Unit) : Source by delegate {
    override fun close() {
        delegate.close()
        onClose()
    }
}
