package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner

/**
 * One scan pipeline shared by the foreground UI and the background worker: scan → persist each
 * series → generate/cache missing chapter covers → prune anything not seen this run (add/update/
 * remove reconcile, PLAN.md §5, §9).
 */
class LibrarySyncer(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
    private val coverCache: ChapterCoverCache? = null,
) {
    /** Runs a full scan of [rootLocator]. [onProgress] is (seriesFound, chaptersFound). */
    suspend fun sync(rootLocator: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val scanAt = nowEpochMillis()
        var series = 0
        var chapters = 0
        scanner.scan(rootLocator, scanAt).collect { scanned ->
            repository.persistSeries(scanned.series, scanned.chapters)
            if (coverCache != null) {
                scanned.chapters.forEach { chapter ->
                    // Cover generation is a no-op if the cached file already exists (§9); page
                    // count is still (cheaply) recounted so the read-percentage overlay works.
                    val result = coverCache.ensureCover(chapter) ?: return@forEach
                    result.coverPath?.let { path -> repository.setChapterCoverPath(chapter.id, path) }
                    repository.setChapterPageCount(chapter.id, result.pageCount)
                }
            }
            series++
            chapters += scanned.chapters.size
            onProgress(series, chapters)
        }
        // Only after a successful, complete scan — prune removed series/chapters.
        repository.deleteSeriesNotScannedAt(scanAt)
    }
}
