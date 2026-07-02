package com.mangaread.core.metadata

import com.mangaread.core.domain.nowEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val ANILIST_ENDPOINT = "https://graphql.anilist.co"
private const val MIN_CALL_INTERVAL_MS = 2_000L
private const val MAX_RETRIES = 3
private val AUTHOR_ROLE_PRIORITY = listOf("Story & Art", "Story", "Original Creator", "Art")

/**
 * The single metadata provider (PLAN.md §9) — public AniList GraphQL, no API key. Owns its
 * own serialized, rate-limited, 429-backing-off call queue (§9.2) so every caller (the
 * background enrichment pipeline AND the user-facing Fix Metadata search, §9.1) shares one
 * throttle regardless of how many places hold a reference to this instance.
 */
class AniListMetadataProvider(
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { header(HttpHeaders.UserAgent, "MangaRead/1.0 (Android manga reader)") }
        expectSuccess = false
    },
) : MetadataProvider {

    private val mutex = Mutex()
    private var lastCallAt = 0L

    override suspend fun search(title: String): List<RemoteWork> {
        val data = execute<SearchRequest, SearchData>(SearchRequest(SEARCH_QUERY, SearchVariables(title)))
            ?: return emptyList()
        return data.Page.media.map { it.toRemoteWork() }
    }

    override suspend fun details(externalId: String): RemoteWorkDetails {
        val id = externalId.toInt()
        val data = execute<DetailsRequest, DetailsData>(DetailsRequest(DETAILS_QUERY, IdVariables(id)))
        val media = data?.Media ?: error("AniList: no details for id $externalId")
        return media.toRemoteWorkDetails()
    }

    /** Serialized (one call at a time, [MIN_CALL_INTERVAL_MS] apart) with 429 backoff. */
    private suspend inline fun <reified TReq, reified TRes> execute(body: TReq): TRes? {
        var attempt = 0
        var backoffMs = 5_000L
        while (attempt < MAX_RETRIES) {
            throttle()
            val response = client.post(ANILIST_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                delay(retryDelayMs(response, backoffMs))
                backoffMs *= 2
                attempt++
                continue
            }
            if (!response.status.isSuccess()) return null
            return response.body<GraphQlResponse<TRes>>().data
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

    private companion object {
        const val SEARCH_QUERY = """
            query (${'$'}search: String) {
              Page(perPage: 10) {
                media(search: ${'$'}search, type: MANGA) {
                  id
                  title { romaji english native }
                  startDate { year }
                  coverImage { large }
                }
              }
            }
        """

        const val DETAILS_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: MANGA) {
                id
                title { romaji english native }
                description(asHtml: true)
                startDate { year }
                coverImage { large }
                bannerImage
                status
                format
                genres
                tags { name }
                isAdult
                averageScore
                siteUrl
                staff(perPage: 25) {
                  edges { role node { name { full } } }
                }
              }
            }
        """
    }
}

private fun MediaSummary.toRemoteWork() = RemoteWork(
    externalId = id.toString(),
    title = title.preferred(),
    coverUrl = coverImage?.large,
    startYear = startDate?.year,
)

private fun MediaDetails.toRemoteWorkDetails() = RemoteWorkDetails(
    externalId = id.toString(),
    title = title.preferred(),
    titleRomaji = title.romaji,
    titleEnglish = title.english,
    titleNative = title.native,
    author = pickAuthor(staff),
    description = cleanDescription(description),
    coverUrl = coverImage?.large,
    startYear = startDate?.year,
    status = status,
    format = format,
    genres = genres,
    tags = tags.map { it.name },
    isAdult = isAdult,
    averageScore = averageScore,
    siteUrl = siteUrl,
    bannerUrl = bannerImage,
)

private fun MediaTitle.preferred(): String = english ?: romaji ?: native ?: "Unknown"

private fun pickAuthor(staff: StaffConnection?): String? {
    val edges = staff?.edges.orEmpty()
    if (edges.isEmpty()) return null
    for (role in AUTHOR_ROLE_PRIORITY) {
        edges.firstOrNull { it.role.equals(role, ignoreCase = true) }?.let { return it.node.name.full }
    }
    return edges.first().node.name.full
}

/**
 * AniList's `description(asHtml: true)` includes basic tags and HTML entities, plus a
 * frequent trailing "(Source: …)" attribution line — none of which belongs in the app's
 * plain-text series description (PLAN.md §9).
 */
internal fun cleanDescription(raw: String?): String? {
    if (raw == null) return null
    var text = raw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    text = text.replace(Regex("\\(Source:[^)]*\\)", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
    return text.ifBlank { null }
}

@Serializable private data class GraphQlResponse<T>(val data: T? = null)

@Serializable private data class SearchRequest(val query: String, val variables: SearchVariables)
@Serializable private data class SearchVariables(val search: String)
@Serializable private data class DetailsRequest(val query: String, val variables: IdVariables)
@Serializable private data class IdVariables(val id: Int)

@Serializable private data class SearchData(val Page: PageData)
@Serializable private data class PageData(val media: List<MediaSummary> = emptyList())
@Serializable private data class MediaSummary(
    val id: Int,
    val title: MediaTitle,
    val startDate: FuzzyDate? = null,
    val coverImage: CoverImage? = null,
)

@Serializable private data class DetailsData(val Media: MediaDetails? = null)
@Serializable private data class MediaDetails(
    val id: Int,
    val title: MediaTitle,
    val description: String? = null,
    val startDate: FuzzyDate? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val isAdult: Boolean = false,
    val averageScore: Int? = null,
    val siteUrl: String? = null,
    val staff: StaffConnection? = null,
)

@Serializable private data class TagDto(val name: String)

@Serializable private data class StaffConnection(val edges: List<StaffEdge> = emptyList())
@Serializable private data class StaffEdge(val role: String? = null, val node: StaffNode)
@Serializable private data class StaffNode(val name: StaffName)
@Serializable private data class StaffName(val full: String? = null)

@Serializable private data class MediaTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)
@Serializable private data class FuzzyDate(val year: Int? = null)
@Serializable private data class CoverImage(val large: String? = null, val medium: String? = null)
