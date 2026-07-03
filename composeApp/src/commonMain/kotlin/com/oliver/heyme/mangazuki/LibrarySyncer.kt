package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
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
    /** Runs a full scan of [rootLocator]. [onProgress] is (seriesFound, chaptersFound).
     * Serialized via [libraryWriteMutex] — see its doc for why overlapping scans are unsafe. */
    suspend fun sync(rootLocator: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) = libraryWriteMutex.withLock {
        val scanAt = nowEpochMillis()
        val previousCount = repository.seriesCount()
        var series = 0
        var chapters = 0
        scanner.scan(rootLocator, scanAt).collect { scanned ->
            // Only a brand-new series gets the ComicInfo.xml naming pass -- an already-known
            // series (even one being rescanned right now) keeps whatever title it has, so this
            // can never flip-flop on a later scan.
            val toPersist = if (repository.seriesExists(scanned.series.id)) scanned else withComicInfoTitle(scanned)
            repository.persistSeries(toPersist.series, toPersist.chapters)
            series++
            chapters += toPersist.chapters.size
            onProgress(series, chapters)
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

    /**
     * A freshly discovered series defaults to its folder name, but if its first CBZ chapter (by
     * volume/number, so this is deterministic regardless of the source's own listing order)
     * carries a `ComicInfo.xml` with a `<Series>` name, that replaces it -- checked against only
     * that one file, per [LibraryScanner.comicInfoSeriesTitle]'s contract.
     */
    private suspend fun withComicInfoTitle(scanned: ScannedSeries): ScannedSeries {
        val firstCbz = scanned.chapters
            .filter { it.format == ChapterFormat.CBZ }
            .minWithOrNull(compareBy({ it.volume ?: Double.MAX_VALUE }, { it.number ?: Double.MAX_VALUE }, { it.locator }))
            ?: return scanned
        val comicInfoTitle = scanner.comicInfoSeriesTitle(firstCbz.locator) ?: return scanned
        return scanned.copy(series = scanned.series.copy(title = comicInfoTitle, sortTitle = normalizeSortTitle(comicInfoTitle)))
    }
}
