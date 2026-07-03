package com.oliver.heyme.mangazuki.core.metadata

import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private const val KITSU_ENDPOINT = "https://kitsu.io/api/edge"
private const val MIN_CALL_INTERVAL_MS = 500L
private const val MAX_RETRIES = 3

/**
 * Second metadata provider (PLAN.md §9.3) — public Kitsu JSON:API, no API key. Kitsu's
 * webtoon/manhwa coverage is comparable to AniList's (community tracker, not a
 * scanlation-driven catalog), but it's the only other API checked with a genuine wide
 * banner image (`coverImage`, verified live against real entries before implementing —
 * not assumed): AniList has `bannerImage`, MangaDex and MangaUpdates have no banner field
 * at all. Same serialized/rate-limited call discipline as [AniListMetadataProvider], just
 * a gentler interval since Kitsu has no documented hard limit.
 *
 * `status`/`format` are normalized to AniList's canonical strings at the boundary (see
 * [RemoteWorkDetails.status]) so the rest of the app never needs to branch on provider.
 * `author` is always null — Kitsu's `mangaStaff` relationship exists but was frequently
 * empty in real entries checked before implementing; not worth an extra request for data
 * that usually isn't there.
 */
class KitsuMetadataProvider(
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest {
            header(HttpHeaders.UserAgent, "MangaRead/1.0 (Android manga reader)")
            header(HttpHeaders.Accept, "application/vnd.api+json")
        }
        expectSuccess = false
    },
) : MetadataProvider {

    private val mutex = Mutex()
    private var lastCallAt = 0L

    override suspend fun search(title: String): List<RemoteWork> {
        val response = execute<KitsuListResponse>("$KITSU_ENDPOINT/manga") {
            append("filter[text]", title)
            append("page[limit]", "10")
        } ?: return emptyList()
        return response.data.map { it.toRemoteWork() }
    }

    override suspend fun details(externalId: String): RemoteWorkDetails {
        val response = execute<KitsuSingleResponse>("$KITSU_ENDPOINT/manga/$externalId") {
            append("include", "categories")
        }
        return response?.toRemoteWorkDetails() ?: error("Kitsu: no details for id $externalId")
    }

    /** Serialized (one call at a time, [MIN_CALL_INTERVAL_MS] apart) with 429 backoff — same
     * discipline as [AniListMetadataProvider], just gentler since Kitsu has no documented
     * hard rate limit. [params] appends JSON:API query params (e.g. `filter[text]`). */
    private suspend inline fun <reified T> execute(url: String, crossinline params: io.ktor.http.ParametersBuilder.() -> Unit): T? {
        var attempt = 0
        var backoffMs = 3_000L
        while (attempt < MAX_RETRIES) {
            throttle()
            val response = client.get(url) { url { parameters.apply(params) } }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(retryDelayMs(response, backoffMs))
                backoffMs *= 2
                attempt++
                continue
            }
            if (!response.status.isSuccess()) return null
            return response.body<T>()
        }
        return null
    }

    private fun retryDelayMs(response: HttpResponse, fallbackMs: Long): Long =
        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1_000) ?: fallbackMs

    private suspend fun throttle() = mutex.withLock {
        val elapsed = nowEpochMillis() - lastCallAt
        if (elapsed < MIN_CALL_INTERVAL_MS) delay(MIN_CALL_INTERVAL_MS - elapsed)
        lastCallAt = nowEpochMillis()
    }
}

private fun KitsuResource.toRemoteWork() = RemoteWork(
    externalId = id,
    title = attributes.preferredTitle(),
    titleRomaji = attributes.titles["en_jp"],
    titleEnglish = attributes.titles["en_us"] ?: attributes.titles["en"],
    titleNative = attributes.titles["ja_jp"] ?: attributes.titles["ko_kr"] ?: attributes.titles["zh_hans"],
    coverUrl = attributes.posterImage?.large,
    startYear = attributes.startYear(),
    format = normalizeFormat(attributes.subtype),
)

private fun KitsuSingleResponse.toRemoteWorkDetails(): RemoteWorkDetails {
    val resource = data ?: error("Kitsu: empty response")
    val attrs = resource.attributes
    val categoryIds = resource.relationships?.categories?.data.orEmpty().map { it.id }.toSet()
    val genres = included.filter { it.type == "categories" && it.id in categoryIds }.mapNotNull { it.attributes.title }
    return RemoteWorkDetails(
        externalId = resource.id,
        title = attrs.preferredTitle(),
        titleRomaji = attrs.titles["en_jp"],
        titleEnglish = attrs.titles["en_us"] ?: attrs.titles["en"],
        titleNative = attrs.titles["ja_jp"] ?: attrs.titles["ko_kr"] ?: attrs.titles["zh_hans"],
        author = null,
        description = (attrs.synopsis ?: attrs.description)?.trim()?.ifBlank { null },
        coverUrl = attrs.posterImage?.large,
        startYear = attrs.startYear(),
        status = normalizeStatus(attrs.status),
        format = normalizeFormat(attrs.subtype),
        genres = genres,
        tags = emptyList(),
        isAdult = attrs.ageRating in setOf("R", "R18"),
        averageScore = attrs.averageRating?.toDoubleOrNull()?.roundToInt(),
        siteUrl = attrs.slug?.let { "https://kitsu.io/manga/$it" },
        bannerUrl = attrs.coverImage?.large,
        providerId = "KITSU",
    )
}

internal fun KitsuAttributes.preferredTitle(): String =
    canonicalTitle ?: titles["en_us"] ?: titles["en"] ?: titles.values.filterNotNull().firstOrNull() ?: "Unknown"

private fun KitsuAttributes.startYear(): Int? = startDate?.take(4)?.toIntOrNull()

/** Kitsu's status values (lowercase) -> AniList's canonical MediaStatus strings (§9). Kitsu
 * has no CANCELLED/HIATUS equivalent, so a Kitsu-matched series never shows those. */
internal fun normalizeStatus(kitsuStatus: String?): String? = when (kitsuStatus) {
    "finished" -> "FINISHED"
    "current" -> "RELEASING"
    "tba", "unreleased", "upcoming" -> "NOT_YET_RELEASED"
    else -> null
}

/** Kitsu's `subtype` -> AniList's canonical MediaFormat strings (§9). AniList's format enum
 * doesn't distinguish manhwa/manhua from manga, so both fold into MANGA. */
internal fun normalizeFormat(subtype: String?): String? = when (subtype) {
    "novel" -> "NOVEL"
    "oneshot" -> "ONE_SHOT"
    "manga", "manhwa", "manhua", "oel", "doujin" -> "MANGA"
    else -> null
}

@Serializable private data class KitsuListResponse(val data: List<KitsuResource> = emptyList())

@Serializable private data class KitsuSingleResponse(
    val data: KitsuResource? = null,
    val included: List<KitsuIncluded> = emptyList(),
)

@Serializable private data class KitsuResource(
    val id: String,
    val attributes: KitsuAttributes,
    val relationships: KitsuRelationships? = null,
)

@Serializable private data class KitsuRelationships(val categories: KitsuRelationshipData? = null)
@Serializable private data class KitsuRelationshipData(val data: List<KitsuRef> = emptyList())
@Serializable private data class KitsuRef(val id: String)

@Serializable private data class KitsuIncluded(val id: String, val type: String, val attributes: KitsuCategoryAttributes)
@Serializable private data class KitsuCategoryAttributes(val title: String? = null)

@Serializable internal data class KitsuAttributes(
    val slug: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val titles: Map<String, String?> = emptyMap(),
    val canonicalTitle: String? = null,
    val startDate: String? = null,
    val status: String? = null,
    val subtype: String? = null,
    val ageRating: String? = null,
    val averageRating: String? = null,
    val posterImage: KitsuImage? = null,
    val coverImage: KitsuImage? = null,
)

@Serializable internal data class KitsuImage(val large: String? = null, val medium: String? = null)
