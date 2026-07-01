package com.mangaread

import com.mangaread.core.data.ChapterCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Chapter as DomainChapter
import com.mangaread.core.domain.ChapterFormat
import com.mangaread.core.reader.pageProviderFor
import com.mangaread.core.source.MangaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One pager "slot": a single page, or two portrait pages shown side by side (PLAN.md §8). */
sealed class PageUnit {
    data class Single(val index: Int) : PageUnit()
    data class Spread(val first: Int, val second: Int) : PageUnit()
}

/**
 * Groups raw pages into pager units using the aspect-ratio heuristic (PLAN.md §8): a page
 * wider than tall is a pre-stitched spread shown alone; consecutive portrait pages pair up
 * only when [pairPortrait] (landscape/tablet). Pure and side-effect free.
 */
fun buildPageUnits(pageCount: Int, wideFlags: List<Boolean>, pairPortrait: Boolean): List<PageUnit> {
    if (!pairPortrait || wideFlags.size < pageCount) return (0 until pageCount).map { PageUnit.Single(it) }
    val units = mutableListOf<PageUnit>()
    var i = 0
    while (i < pageCount) {
        val isWide = wideFlags[i]
        when {
            isWide -> { units += PageUnit.Single(i); i += 1 }
            i + 1 < pageCount && !wideFlags[i + 1] -> { units += PageUnit.Spread(i, i + 1); i += 2 }
            else -> { units += PageUnit.Single(i); i += 1 }
        }
    }
    return units
}

/** The pager only knows "page N of a chapter" (PLAN.md §8); loading a bitmap per page is
 * delegated to Coil ([MangaPage]/`PageFetcher`) for its caching/prefetch, while [PageProvider]
 * supplies the authoritative page count and per-page aspect ratio for spread pairing. */
class ReaderViewModel(
    private val repository: LibraryRepository,
    source: MangaSource,
    val chapter: ChapterCard,
    val readingDirectionRtl: Boolean,
    private val prefs: ReaderPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    val pageModel: String = if (chapter.format == "CBZ") "cbz:${chapter.locator}" else "imgdir:${chapter.locator}"

    private val _pageCount = MutableStateFlow(chapter.pageCount ?: 0)
    val pageCount: StateFlow<Int> = _pageCount

    /** One aspect-ratio flag per page (true = wider than tall); filled once, before pairing. */
    private val _wideFlags = MutableStateFlow<List<Boolean>>(emptyList())
    val wideFlags: StateFlow<List<Boolean>> = _wideFlags

    val currentPage = MutableStateFlow(chapter.lastPageIndex.coerceAtLeast(0))

    /** One-time overlay explaining tap zones (PLAN.md §8.1); dismiss persists so it shows once. */
    private val _showGestureHelp = MutableStateFlow(!prefs.hasSeenGestureHelp)
    val showGestureHelp: StateFlow<Boolean> = _showGestureHelp

    fun dismissGestureHelp() {
        prefs.hasSeenGestureHelp = true
        _showGestureHelp.value = false
    }

    init {
        scope.launch {
            val domainChapter = DomainChapter(
                id = chapter.id,
                seriesId = chapter.seriesId,
                sourceId = chapter.sourceId,
                locator = chapter.locator,
                format = ChapterFormat.valueOf(chapter.format),
                displayName = chapter.displayName,
                volume = chapter.volume,
                number = chapter.number,
                pageCount = chapter.pageCount,
                dateAdded = 0L,
            )
            val provider = pageProviderFor(domainChapter, source)
            val count = provider.pageCount
            _pageCount.value = count
            _wideFlags.value = (0 until count).map { i ->
                val size = provider.pageSize(i)
                size.width > size.height
            }
            provider.close()
        }
    }

    fun onPageChanged(index: Int) {
        currentPage.value = index
        val count = _pageCount.value
        val completed = count > 0 && index >= count - 1
        scope.launch { repository.markProgress(chapter.id, index, completed) }
    }
}
