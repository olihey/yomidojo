package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Same ordering `selectLibrary` uses to pick a series' cover chapter (nulls last). */
private val seriesCoverOrder = compareBy<Chapter>(
    { it.volume == null }, { it.volume ?: 0.0 },
    { it.number == null }, { it.number ?: 0.0 },
    { it.displayName },
)

/**
 * One scan pipeline shared by the foreground UI and the background worker: scan → persist each
 * series → generate the series' own cover chapter right away → prune anything not seen this run
 * (add/update/remove reconcile, PLAN.md §5, §9). Every *other* chapter's cover/page-count is
 * deferred to [backfillChapterCovers] so the library shows up as soon as the scan itself is
 * done, instead of waiting on every chapter of every series.
 */
class LibrarySyncer(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
    private val coverCache: ChapterCoverCache? = null,
) {
    private val deferred = mutableListOf<Chapter>()

    /** Runs a full scan of [rootLocator]. [onProgress] is (seriesFound, chaptersFound). */
    suspend fun sync(rootLocator: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        deferred.clear()
        val scanAt = nowEpochMillis()
        var series = 0
        var chapters = 0
        scanner.scan(rootLocator, scanAt).collect { scanned ->
            repository.persistSeries(scanned.series, scanned.chapters)
            if (coverCache != null) generateSeriesCoverNow(scanned.series.id, scanned.chapters)
            series++
            chapters += scanned.chapters.size
            onProgress(series, chapters)
        }
        // Only after a successful, complete scan — prune removed series/chapters.
        repository.deleteSeriesNotScannedAt(scanAt)
    }

    /**
     * Generates/caches the covers [sync] deferred — every chapter except each series' own cover
     * chapter. Meant to run after [sync] returns (e.g. fired off in the background) so it never
     * delays the library appearing; safe to call even with nothing pending.
     */
    suspend fun backfillChapterCovers() {
        val cache = coverCache ?: return
        if (deferred.isEmpty()) return
        val pending = deferred.toList()
        deferred.clear()
        coroutineScope {
            pending.map { chapter -> async { generateOne(cache, chapter) } }.forEach { it.await() }
        }
    }

    /**
     * Only the chapter the library actually displays as the cover is generated inline; the rest
     * of the series' chapters (used by the series-screen grid) are queued into [deferred].
     * Chapters that already have both a cached cover and a known page count are skipped
     * entirely — no file/archive is opened for them at all — which is what keeps a re-scan of an
     * already-scanned library fast.
     */
    private suspend fun generateSeriesCoverNow(seriesId: String, chapters: List<Chapter>) {
        val cache = coverCache ?: return
        if (chapters.isEmpty()) return
        val known = repository.coverStatesForSeries(seriesId)
        fun needsWork(chapter: Chapter): Boolean {
            val state = known[chapter.id]
            val coverPath = state?.coverPath
            // The cache dir is OS-purgeable, so a recorded cover_path isn't trustworthy on its
            // own — re-generate whenever the file itself is actually gone (PLAN.md §9).
            val coverMissing = coverPath == null || !cache.coverPathExists(coverPath)
            return state == null || coverMissing || (state.pageCount ?: 0) <= 0
        }

        val coverChapter = chapters.minWith(seriesCoverOrder)
        if (needsWork(coverChapter)) generateOne(cache, coverChapter)

        chapters.filterTo(deferred) { it.id != coverChapter.id && needsWork(it) }
    }

    private suspend fun generateOne(cache: ChapterCoverCache, chapter: Chapter) {
        val result = cache.ensureCover(chapter) ?: return
        result.coverPath?.let { path -> repository.setChapterCoverPath(chapter.id, path) }
        repository.setChapterPageCount(chapter.id, result.pageCount)
    }
}
