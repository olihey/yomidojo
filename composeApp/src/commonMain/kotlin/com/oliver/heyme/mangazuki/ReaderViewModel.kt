package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.Chapter as DomainChapter
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.ReadingDirection
import com.oliver.heyme.mangazuki.core.domain.ReadingMode
import com.oliver.heyme.mangazuki.core.reader.pageProviderFor
import com.oliver.heyme.mangazuki.core.source.MangaSource
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

/** A PDF chapter's one-time local materialization (PLAN.md §16) while it's in flight --
 * [totalBytes] null means the size wasn't known at scan time (indeterminate progress). */
data class PdfPrepProgress(val bytesCopied: Long, val totalBytes: Long?)

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
    private val deviceId: String,
    /** Debounced cloud-sync trigger (PLAN.md §10) — see [AppGraph.requestSync]. */
    private val requestSync: () -> Unit = {},
    /** Where PDF chapters are materialized to a seekable local file (PLAN.md §16) — see
     * [AppGraph.pdfCacheDir]. Null only in tests/previews that never open a PDF. */
    private val pdfCacheDir: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    val pageModel: String = when (chapter.format) {
        ChapterFormat.CBZ.name -> "cbz:${chapter.locator}"
        ChapterFormat.PDF.name -> "pdf:${chapter.locator}"
        else -> "imgdir:${chapter.locator}"
    }

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

    /** Non-null while a PDF chapter is being copied to local storage before Pdfium can open it
     * (PLAN.md §16) — the loading screen shows it as "Preparing chapter… NN%". Only ever fires
     * for PDFs on a cache miss; a local/cached chapter never leaves null. */
    private val _pdfPrepProgress = MutableStateFlow<PdfPrepProgress?>(null)
    val pdfPrepProgress: StateFlow<PdfPrepProgress?> = _pdfPrepProgress

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
                size = chapter.size,
                dateAdded = 0L,
            )
            // runCatching: PDF materialization adds real failure modes the other formats don't
            // have (share unreachable mid-download, password-protected file) -- log and leave
            // the loading state rather than crash the whole Activity's Main-dispatcher scope.
            runCatching {
                val provider = try {
                    pageProviderFor(domainChapter, source, pdfCacheDir) { copied, total ->
                        _pdfPrepProgress.value = PdfPrepProgress(copied, total)
                    }
                } finally {
                    _pdfPrepProgress.value = null
                }
                val count = provider.pageCount
                _pageCount.value = count
                // A remote CBZ may need one range read per page just to discover aspect ratios.
                // Seed stable defaults immediately so the reader can render page 1 now instead of
                // blocking behind a whole-chapter geometry walk.
                _wideFlags.value = List(count) { false }
                _pageAspectRatios.value = List(count) { 1f }
                // PDFs skip the series screen's lazy page-count probe (counting means a full
                // download there, PLAN.md §16) -- persist the count on first open instead, so
                // the read-percentage overlay works from then on.
                if (chapter.pageCount == null && count > 0) {
                    repository.setChapterPageCounts(listOf(chapter.id to count))
                }
                if (count > 0) {
                    val wideFlags = MutableList(count) { false }
                    val aspectRatios = MutableList(count) { 1f }
                    for (i in 0 until count) {
                        val size = runCatching { provider.pageSize(i) }.getOrNull() ?: continue
                        if (size.width <= 0f || size.height <= 0f) continue
                        wideFlags[i] = size.width > size.height
                        aspectRatios[i] = size.width / size.height
                    }
                    _wideFlags.value = wideFlags
                    _pageAspectRatios.value = aspectRatios
                }
                provider.close()
            }.onFailure { t ->
                println("ReaderViewModel: failed to open ${chapter.displayName}: $t")
            }
        }
    }

    fun onPageChanged(index: Int) {
        currentPage.value = index
        val count = _pageCount.value
        val completed = count > 0 && index >= count - 1
        scope.launch {
            repository.markProgress(chapter.id, index, completed, deviceId)
            requestSync()
        }
    }
}
