package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner

/**
 * One scan pipeline shared by the foreground UI and the background worker: scan → persist each
 * series → prune anything not seen this run (add/update/remove reconcile, PLAN.md §5).
 */
class LibrarySyncer(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
) {
    /** Runs a full scan of [rootLocator]. [onProgress] is (seriesFound, chaptersFound). */
    suspend fun sync(rootLocator: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
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
