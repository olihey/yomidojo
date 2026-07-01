package com.mangaread

import com.mangaread.core.data.ChapterCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Chapter as DomainChapter
import com.mangaread.core.domain.ChapterFormat
import com.mangaread.core.domain.ReadingDirection
import com.mangaread.core.domain.ReadingMode
import com.mangaread.core.reader.pageProviderFor
import com.mangaread.core.source.MangaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun rtlFor(seriesReadingDirection: ReadingDirection?, mode: ReadingMode): Boolean = when (seriesReadingDirection) {
    ReadingDirection.LTR -> false
    ReadingDirection.RTL -> true
    null -> mode != ReadingMode.PAGED_LTR
}

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
    seriesReadingDirection: ReadingDirection?,
    val seriesTitle: String,
    initialNextChapter: ChapterCard?,
    private val prefs: ReaderPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    val pageModel: String = if (chapter.format == "CBZ") "cbz:${chapter.locator}" else "imgdir:${chapter.locator}"

    val invertTapZones: Boolean = prefs.invertTapZones

    /** Per-series override (the chrome's quick-switcher) wins; otherwise the global default from
     * Settings. Mutable — this is exactly what lets the mode change while looking at pages. */
    private val _readingMode = MutableStateFlow(prefs.readingModeFor(chapter.seriesId) ?: prefs.defaultReadingMode)
    val readingMode: StateFlow<ReadingMode> = _readingMode

    /** A series' `reading_direction` column wins for paged modes; otherwise falls back to the
     * current mode's own direction (still RTL unless explicitly PAGED_LTR — manga defaults RTL).
     * Reactive because [readingMode] can change live via [setReadingMode]. */
    val readingDirectionRtl: StateFlow<Boolean> = _readingMode
        .map { mode -> rtlFor(seriesReadingDirection, mode) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), rtlFor(seriesReadingDirection, _readingMode.value))

    /** Changes the mode for the rest of this reading session and remembers it for this series
     * specifically (PLAN.md §8: "global default + per-series override"), not the global default. */
    fun setReadingMode(mode: ReadingMode) {
        _readingMode.value = mode
        prefs.setReadingModeFor(chapter.seriesId, mode)
    }

    private val _pageCount = MutableStateFlow(chapter.pageCount ?: 0)
    val pageCount: StateFlow<Int> = _pageCount

    /** One aspect-ratio flag per page (true = wider than tall); filled once, before pairing. */
    private val _wideFlags = MutableStateFlow<List<Boolean>>(emptyList())
    val wideFlags: StateFlow<List<Boolean>> = _wideFlags

    /** Width/height per page, so the continuous/webtoon reader can reserve each image's real
     * height up front (`Modifier.aspectRatio`) instead of measuring it as zero-height until Coil
     * decodes the bitmap. Without this, a chapter of many short images kept reflowing/remeasuring
     * as each one loaded, and Compose's LazyColumn — trying to keep the viewport filled through
     * each of those resizes — walked the scroll position forward past everything already loaded,
     * landing on the last page (or past it) with no scrolling at all. */
    private val _pageAspectRatios = MutableStateFlow<List<Float>>(emptyList())
    val pageAspectRatios: StateFlow<List<Float>> = _pageAspectRatios

    val currentPage = MutableStateFlow(chapter.lastPageIndex.coerceAtLeast(0))

    /** The chapter right after this one in the series (same order as the series screen), if
     * any — lets the reader offer a "swipe past the last page to continue" transition. Seeded
     * with [initialNextChapter] (already known synchronously from the series' chapter list at
     * navigation time) rather than `null`, so it's correct from the very first frame — the
     * continuous/webtoon reader's initial scroll-to-resume-position depends on the final item
     * count being right immediately; a transient `null` here (before this Flow's first real
     * emission) used to make LazyColumn think there was nothing past a short last page and pull
     * the scroll back to fill the viewport, landing well before the actual resume point. */
    val nextChapter: StateFlow<ChapterCard?> = repository.observeChapters(chapter.seriesId).map { list ->
        val index = list.indexOfFirst { it.id == chapter.id }
        if (index in 0 until list.lastIndex) list[index + 1] else null
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initialNextChapter)

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
            val sizes = (0 until count).map { i -> provider.pageSize(i) }
            _wideFlags.value = sizes.map { it.width > it.height }
            _pageAspectRatios.value = sizes.map { it.width.toFloat() / it.height.toFloat() }
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
