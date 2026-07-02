package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.metadata.MetadataProvider
import com.mangaread.core.metadata.bestMatch
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.withLock

/**
 * Background AniList enrichment (PLAN.md §9.2): dequeues series still missing an
 * `external_id` one at a time, auto-matches by fuzzy title, and applies the result.
 * Best-effort and resumable — a series that fails to match or fetch just stays unmatched
 * (the query that feeds this is `WHERE external_id IS NULL`) and gets picked up again on
 * the next pass, whether that's the next foreground scan or the periodic background worker.
 * Each provider owns its own rate limiting (§9.2), so this loop doesn't need to throttle
 * itself. [providerFor] is resolved fresh at the start of every [enrichPending] call
 * (not fixed at construction) so a background pass always uses whichever provider is
 * currently selected in Settings (PLAN.md §9.3), without needing to recreate this class
 * when the setting changes.
 */
class MetadataEnricher(
    private val repository: LibraryRepository,
    private val providerFor: () -> MetadataProvider,
    private val coverClient: HttpClient,
    private val coversDir: String,
) {
    /** Serialized via [libraryWriteMutex] — see its doc for why this can't overlap a scan
     * (or another enrichment pass) touching the same database. */
    suspend fun enrichPending() = libraryWriteMutex.withLock {
        val provider = providerFor()
        for ((seriesId, rawTitle) in repository.unmatchedSeries()) {
            try {
                val query = cleanSearchQuery(rawTitle)
                val match = bestMatch(query, provider.search(query))
                if (match == null) {
                    // A real search that came back with nothing good enough — distinct from a
                    // network/rate-limit failure below, which leaves the series "never checked"
                    // (not "checked, no match") so it's retried rather than stuck showing ✕.
                    repository.markMetadataChecked(seriesId)
                    continue
                }
                val details = provider.details(match.externalId)
                val coverPath = downloadCover(coverClient, coversDir, details.externalId, details.coverUrl)
                val bannerPath = downloadBanner(coverClient, coversDir, details.externalId, details.bannerUrl)
                repository.applyMetadata(seriesId, details, coverPath, bannerPath)
            } catch (t: Throwable) {
                // Best-effort — leave this one unmatched, try again next pass.
            }
        }
    }
}

/** Strips common scanlation-group/year noise (e.g. "[MangaGroup] Title (2019)") before
 * sending a folder name to AniList search — folder names are the literal, unedited title
 * everywhere else in the app; this cleanup is local to the search query only. */
private fun cleanSearchQuery(rawTitle: String): String {
    val cleaned = rawTitle
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifBlank { rawTitle }
}
