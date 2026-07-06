package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.MetadataAliasRow
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.metadata.MetadataProvider
import com.oliver.heyme.mangazuki.core.metadata.bestMatch
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
    /** Resolves a [MetadataAliasRow.provider] string (e.g. "ANILIST") back to the concrete
     * provider that recorded it (PLAN.md §10 follow-up, 2026-07-06) -- an alias's own match can
     * come from a *different* provider than whatever's currently selected in Settings, so this
     * can't just reuse [providerFor]. Null on an unrecognized/corrupted provider string, in
     * which case the alias is skipped in favor of a fresh search rather than guessing. */
    private val providerNamed: (String) -> MetadataProvider?,
    private val coverClient: HttpClient,
    private val coversDir: String,
) {
    /** Serialized via [libraryWriteMutex] — see its doc for why this can't overlap a scan
     * (or another enrichment pass) touching the same database. [onProgress] reports (done,
     * total) after each series is processed — matched, checked-no-match, or failed all count
     * as "done", so the count always reaches `total` — for the "Fetching metadata… N / M" UI
     * (PLAN.md §9.2). Never invoked when there's nothing pending, so callers can use a null-vs-
     * non-null progress value to know whether enrichment is actually running. */
    suspend fun enrichPending(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) = libraryWriteMutex.withLock {
        val provider = providerFor()
        val pending = repository.unmatchedSeries()
        val total = pending.size
        // A series whose normalized title already has a recorded Fix Metadata alias (PLAN.md
        // §10 follow-up, 2026-07-06) -- e.g. re-scanned after a library reset, or matched fresh
        // on a second device -- gets that exact match applied directly instead of re-running a
        // fuzzy search that could easily land on a different (wrong) entry for an ambiguous
        // title. Fetched once up front, not per series: the alias table only ever grows from
        // deliberate user actions, so it's small regardless of library size.
        val aliasByTitle: Map<String, MetadataAliasRow> = repository.allMetadataAliases().associateBy { it.normalizedOldTitle }
        pending.forEachIndexed { index, (seriesId, rawTitle) ->
            try {
                val alias = aliasByTitle[normalizeSortTitle(rawTitle)]
                val aliasProvider = alias?.let { providerNamed(it.provider) }
                if (alias != null && aliasProvider != null) {
                    applyMatch(seriesId, aliasProvider, alias.externalId)
                } else {
                    val query = cleanSearchQuery(rawTitle)
                    val match = bestMatch(query, provider.search(query))
                    if (match == null) {
                        // A real search that came back with nothing good enough — distinct from a
                        // network/rate-limit failure below, which leaves the series "never checked"
                        // (not "checked, no match") so it's retried rather than stuck showing ✕.
                        repository.markMetadataChecked(seriesId)
                    } else {
                        applyMatch(seriesId, provider, match.externalId)
                    }
                }
            } catch (t: Throwable) {
                // Best-effort — leave this one unmatched, try again next pass.
            }
            onProgress(index + 1, total)
        }
    }

    private suspend fun applyMatch(seriesId: String, provider: MetadataProvider, externalId: String) {
        val details = provider.details(externalId)
        val coverPath = downloadCover(coverClient, coversDir, details.externalId, details.coverUrl)
        val bannerPath = downloadBanner(coverClient, coversDir, details.externalId, details.bannerUrl)
        repository.applyMetadata(seriesId, details, coverPath, bannerPath)
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
