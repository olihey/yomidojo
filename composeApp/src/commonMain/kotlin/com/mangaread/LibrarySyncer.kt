package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner
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
        var series = 0
        var chapters = 0
        scanner.scan(rootLocator, scanAt).collect { scanned ->
            repository.persistSeries(scanned.series, scanned.chapters)
            series++
            chapters += scanned.chapters.size
            onProgress(series, chapters)
        }
        // Only after a successful, complete scan — prune removed series/chapters.
        repository.deleteSeriesNotScannedAt(scanAt)
    }
}
