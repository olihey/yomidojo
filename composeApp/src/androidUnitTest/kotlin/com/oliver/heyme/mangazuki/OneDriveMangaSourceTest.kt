package com.oliver.heyme.mangazuki

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Network behavior of [OneDriveMangaSource.list]/[OneDriveMangaSource.canAccess] against a
 * scripted [MockEngine]: pagination, 429 back-off, and the signed-out short-circuit. The
 * OkHttp byte-stream half (open/openRandomAccess) is exercised on-device instead — its value
 * is in real ranged reads against real download URLs, not in mocking OkHttp. */
class OneDriveMangaSourceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun clientOf(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

    private fun source(engine: MockEngine, token: String? = "token") =
        OneDriveMangaSource(accessToken = { token }, client = clientOf(engine))

    @Test
    fun `list follows nextLink across pages`() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.toString().contains("next-page") -> respond(
                    """{"value": [{"name": "b.cbz", "size": 2, "file": {}}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(
                    """
                    {
                      "value": [{"name": "a.cbz", "size": 1, "file": {}}],
                      "@odata.nextLink": "https://graph.microsoft.com/v1.0/next-page"
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }

        val entries = source(engine).list("Manga")

        assertEquals(listOf("Manga/a.cbz", "Manga/b.cbz"), entries.map { it.locator })
        assertEquals(2, engine.requestHistory.size)
        // Every Graph request carries the bearer token.
        assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization] == "Bearer token" })
    }

    @Test
    fun `list retries a 429 honoring Retry-After then succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("throttled", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
            } else {
                respond("""{"value": [{"name": "a.cbz", "size": 1, "file": {}}]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }

        val entries = source(engine).list("")

        assertEquals(1, entries.size)
        assertEquals(2, calls)
    }

    @Test
    fun `list gives up after bounded retries`() = runTest {
        val engine = MockEngine { respond("still throttled", HttpStatusCode.TooManyRequests) }

        assertFailsWith<IOException> { source(engine).list("") }
        // 1 initial + MAX_RETRIES(2) attempts.
        assertEquals(3, engine.requestHistory.size)
    }

    @Test
    fun `list throws when signed out without touching the network`() = runTest {
        val engine = MockEngine { error("must not be called") }

        assertFailsWith<IOException> { source(engine, token = null).list("") }
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `canAccess is true for a reachable root and false when signed out or missing`() = runTest {
        val ok = MockEngine { respond("""{"name": "root", "folder": {}}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(source(ok).canAccess("Manga"))

        val notFound = MockEngine { respond("nope", HttpStatusCode.NotFound) }
        assertFalse(source(notFound).canAccess("Manga"))

        val signedOut = MockEngine { error("must not be called") }
        assertFalse(source(signedOut, token = null).canAccess("Manga"))
    }
}
