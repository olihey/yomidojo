package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
            if (coverCache != null) generateCovers(scanned.series.id, scanned.chapters)
            series++
            chapters += scanned.chapters.size
            onProgress(series, chapters)
        }
        // Only after a successful, complete scan — prune removed series/chapters.
        repository.deleteSeriesNotScannedAt(scanAt)
    }

    /**
     * Chapters that already have both a cached cover and a known page count skip straight past —
     * no file/archive is even opened for them, which is what makes a re-scan of an already-scanned
     * library fast. The rest are generated concurrently (each is just independent I/O).
     */
    private suspend fun generateCovers(seriesId: String, chapters: List<Chapter>) {
        val cache = coverCache ?: return
        val known = repository.coverStatesForSeries(seriesId)
        val pending = chapters.filter { chapter ->
            val state = known[chapter.id]
            state == null || state.coverPath == null || (state.pageCount ?: 0) <= 0
        }
        if (pending.isEmpty()) return
        coroutineScope {
            pending.map { chapter ->
                async {
                    val result = cache.ensureCover(chapter) ?: return@async
                    result.coverPath?.let { path -> repository.setChapterCoverPath(chapter.id, path) }
                    repository.setChapterPageCount(chapter.id, result.pageCount)
                }
            }.forEach { it.await() }
        }
    }
}
