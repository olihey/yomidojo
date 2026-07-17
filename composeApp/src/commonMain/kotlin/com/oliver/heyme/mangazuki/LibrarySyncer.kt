package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import com.oliver.heyme.mangazuki.core.scanner.CachedChapter
import com.oliver.heyme.mangazuki.core.scanner.ChapterSkipCache
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.oliver.heyme.mangazuki.core.scanner.ScannedSeries
import kotlinx.coroutines.sync.withLock

/**
 * One scan pipeline shared by the foreground UI and the background worker: scan → persist each
 * series → prune anything not seen this run (add/update/remove reconcile, PLAN.md §5). Chapter
 * covers and page counts are generated on demand when the series screen actually needs them
 * (see [SeriesViewModel]), not here — an eager scan-time pass over every chapter's CBZ/folder
 * didn't pull its weight for large libraries.
 */
class LibrarySyncer(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
) {
    /** Runs a full scan of [rootLocator]. Serialized via [libraryWriteMutex] — see its doc for
     * why overlapping scans are unsafe. Cancels a still-running [MetadataEnricher.enrichPending]
     * pass first (PLAN.md §9.2, 2026-07-06), wherever it was started from, rather than queue up
     * behind it for however long AniList's rate-limited queue takes to drain -- that cancel has
     * to happen *before* attempting to acquire the mutex below, since the enrichment pass is
     * what's holding it. */
    suspend fun sync(rootLocator: String, onProgress: (ScanProgress) -> Unit = {}) {
        currentEnrichmentJob.value?.cancel()
        libraryWriteMutex.withLock {
            val scanAt = nowEpochMillis()
            val previousCount = repository.seriesCount()
            var series = 0
            var chapters = 0
            var directories = 0
            scanner.scan(
                rootLocator, scanAt, RepositoryChapterSkipCache(repository),
                // A directory-granularity ping (PLAN.md §5) -- a big first series can otherwise
                // leave onProgress below silent, and the UI stuck at "0 series, 0 chapters", for
                // however long that one series alone takes to fully list and process.
                onDirectoryListed = {
                    directories++
                    onProgress(ScanProgress(series, chapters, directories))
                },
            ).collect { scanned ->
                // Only a brand-new series gets the ComicInfo.xml naming pass -- an already-known
                // series (even one being rescanned right now) keeps whatever title it has, so this
                // can never flip-flop on a later scan.
                val toPersist = if (repository.seriesExists(scanned.series.id)) scanned else withComicInfoTitle(scanned)
                repository.persistSeries(toPersist.series, toPersist.chapters)
                series++
                chapters += toPersist.chapters.size
                onProgress(ScanProgress(series, chapters, directories))
            }
            // Only after a successful, complete scan — prune removed series/chapters. But a scan
            // that comes back with far fewer series than the library already has is more likely an
            // incomplete directory listing (e.g. an SMB share dropping the connection mid-enumerate,
            // reproduced 2026-07-02) than a real mass-deletion, so skip pruning rather than risk
            // wiping real data — a later, fuller scan will still catch up and prune correctly.
            if (previousCount == 0L || series >= previousCount / 2) {
                repository.deleteSeriesNotScannedAt(scanAt)
            } else {
                println("LibrarySyncer: skipping prune — this scan found $series series vs $previousCount already in the library")
            }
        }
    }

    /**
     * A freshly discovered series defaults to its folder name, but if its first CBZ chapter (by
     * volume/number, so this is deterministic regardless of the source's own listing order)
     * carries a `ComicInfo.xml` with a `<Series>` name, that replaces it -- checked against only
     * that one file, per [LibraryScanner.comicInfoMeta]'s contract.
     */
    private suspend fun withComicInfoTitle(scanned: ScannedSeries): ScannedSeries {
        val firstCbz = scanned.chapters
            .filter { it.format == ChapterFormat.CBZ }
            .minWithOrNull(compareBy({ it.volume ?: Double.MAX_VALUE }, { it.number ?: Double.MAX_VALUE }, { it.locator }))
            ?: return scanned
        val comicInfoTitle = scanner.comicInfoMeta(firstCbz.locator, firstCbz.size)?.seriesTitle ?: return scanned
        return scanned.copy(series = scanned.series.copy(title = comicInfoTitle, sortTitle = normalizeSortTitle(comicInfoTitle)))
    }
}

/** Bridges [LibraryRepository] to [ChapterSkipCache] (PLAN.md §5) -- lives here rather than in
 * `core:scanner` since that module can't depend on `core:data`'s SQLDelight types. */
class RepositoryChapterSkipCache(private val repository: LibraryRepository) : ChapterSkipCache {
    override suspend fun lookup(chapterId: String): CachedChapter? =
        repository.findChapterForSkipCache(chapterId)?.let {
            CachedChapter(
                changeToken = it.changeToken,
                seriesId = it.seriesId,
                seriesTitle = it.seriesTitle,
                displayName = it.displayName,
                pageCount = it.pageCount,
            )
        }
}
