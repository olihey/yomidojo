package com.mangaread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mangaread.core.data.ChapterCard
import com.mangaread.core.domain.ReadingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class TapZone { BACKWARD, FORWARD, MENU }

/**
 * Pure UI over "page N of a chapter" (PLAN.md §8): paged (horizontal or vertical, RTL-aware) or
 * continuous-scroll (webtoon) per the reading mode, plus the gesture/polish bundle (§8.1) —
 * configurable tap zones, double-tap + pinch zoom with pan, keep-screen-on, volume-key paging, a
 * one-time gesture-help overlay, double-page spread pairing on wide containers, a chrome overlay
 * with a scrubbable progress slider, and (paged modes) a next-chapter preview past the last page.
 */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit, onNavigateToChapter: (String) -> Unit) {
    val pageCount by viewModel.pageCount.collectAsState()
    val wideFlags by viewModel.wideFlags.collectAsState()
    val pageAspectRatios by viewModel.pageAspectRatios.collectAsState()

    // Wait for both the page count AND every page's aspect ratio before building the pager,
    // so spread pairing (which needs the full picture) never has to reshuffle mid-read.
    if (pageCount <= 0 || wideFlags.size < pageCount || pageAspectRatios.size < pageCount) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    KeepScreenOn(enabled = true)
    // The system bars follow the chrome overlay: shown together, hidden together.
    var showChrome by remember { mutableStateOf(true) }
    ImmersiveMode(enabled = !showChrome)
    var isScrubbing by remember { mutableStateOf(false) }
    val showGestureHelp by viewModel.showGestureHelp.collectAsState()
    val readingMode by viewModel.readingMode.collectAsState()
    val readingDirectionRtl by viewModel.readingDirectionRtl.collectAsState()

    // Auto-hide only the initial chrome shown on opening the reader — a one-shot timer, not
    // re-armed by later manual toggles (center tap shows/hides it with no timeout after
    // that). Waits out an in-progress scrub rather than yanking the slider mid-drag.
    LaunchedEffect(Unit) {
        delay(5_000)
        snapshotFlow { isScrubbing }.first { !it }
        showChrome = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (readingMode == ReadingMode.VERTICAL_CONTINUOUS) {
            ContinuousReader(
                viewModel = viewModel,
                pageCount = pageCount,
                pageAspectRatios = pageAspectRatios,
                readingMode = readingMode,
                onReadingModeChange = viewModel::setReadingMode,
                showChrome = showChrome,
                onToggleChrome = { showChrome = !showChrome },
                onScrubbingChanged = { isScrubbing = it },
                onBack = onBack,
                onNavigateToChapter = onNavigateToChapter,
            )
        } else {
            PagedReader(
                viewModel = viewModel,
                pageCount = pageCount,
                wideFlags = wideFlags,
                readingMode = readingMode,
                readingDirectionRtl = readingDirectionRtl,
                showChrome = showChrome,
                onToggleChrome = { showChrome = !showChrome },
                onScrubbingChanged = { isScrubbing = it },
                onBack = onBack,
                onNavigateToChapter = onNavigateToChapter,
            )
        }
        if (showGestureHelp) {
            GestureHelpOverlay(
                isRtl = readingDirectionRtl,
                invertTapZones = viewModel.invertTapZones,
                isVertical = readingMode == ReadingMode.VERTICAL_PAGED,
                onDismiss = viewModel::dismissGestureHelp,
            )
        }
    }
}

/** PAGED_LTR/PAGED_RTL/VERTICAL_PAGED: one page (or spread) per swipe, snapping, zoomable,
 * with the next-chapter preview slot past the last page. */
@Composable
private fun PagedReader(
    viewModel: ReaderViewModel,
    pageCount: Int,
    wideFlags: List<Boolean>,
    readingMode: ReadingMode,
    readingDirectionRtl: Boolean,
    showChrome: Boolean,
    onToggleChrome: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigateToChapter: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isVertical = readingMode == ReadingMode.VERTICAL_PAGED
        val pairPortrait = maxWidth > maxHeight
        val units = remember(wideFlags, pairPortrait) { buildPageUnits(pageCount, wideFlags, pairPortrait) }
        val initialUnit = remember(units) { unitIndexForPage(units, viewModel.currentPage.value) }
        val nextChapter by viewModel.nextChapter.collectAsState()
        // One extra slot past the real pages previews the next chapter's cover; swiping onto it
        // and letting it settle there switches chapters (PLAN.md §8.1).
        val totalCount = units.size + if (nextChapter != null) 1 else 0

        val pagerState = rememberPagerState(initialPage = initialUnit.coerceIn(0, units.size - 1)) { totalCount }
        val scope = rememberCoroutineScope()
        var zoomedIn by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState, units) {
            snapshotFlow { pagerState.currentPage }.collect { unitIndex ->
                if (unitIndex < units.size) viewModel.onPageChanged(progressIndexFor(units.getOrNull(unitIndex)))
            }
        }
        // A page always starts unzoomed, so leaving it should re-enable pager swiping.
        LaunchedEffect(pagerState.currentPage) { zoomedIn = false }

        // Only fires once the swipe settles ON the preview slot (not just passing through
        // during a fling), so a quick overscroll-and-snap-back doesn't switch chapters.
        LaunchedEffect(pagerState, units.size, nextChapter) {
            snapshotFlow { pagerState.settledPage }.collect { settled ->
                val next = nextChapter
                if (next != null && settled == units.size) onNavigateToChapter(next.id)
            }
        }

        DisposableEffect(readingDirectionRtl, isVertical, units.size) {
            VolumeKeyBus.onVolumeKey = { down ->
                // "Down" always turns to the next page in reading order — vertical top-to-bottom
                // has no reversed direction; horizontal still respects LTR/RTL.
                val forward = if (isVertical) down else (if (readingDirectionRtl) !down else down)
                scrollBy(pagerState, scope, if (forward) 1 else -1, units.size)
                true
            }
            onDispose { VolumeKeyBus.onVolumeKey = null }
        }

        val pageContent: @Composable PagerScope.(Int) -> Unit = { unitIndex ->
            if (unitIndex >= units.size) {
                nextChapter?.let { NextChapterPreview(it) }
            } else {
                val onZoneTap: (TapZone) -> Unit = { zone ->
                    when (zone) {
                        TapZone.FORWARD -> scrollBy(pagerState, scope, 1, units.size)
                        TapZone.BACKWARD -> scrollBy(pagerState, scope, -1, units.size)
                        TapZone.MENU -> onToggleChrome()
                    }
                }
                when (val unit = units[unitIndex]) {
                    is PageUnit.Single -> ReaderPage(
                        viewModel.pageModel, unit.index, readingDirectionRtl, viewModel.invertTapZones,
                        isVertical, onZoneTap, onZoomChanged = { zoomedIn = it },
                    )
                    is PageUnit.Spread -> {
                        val order = if (readingDirectionRtl) listOf(unit.second, unit.first) else listOf(unit.first, unit.second)
                        Row(Modifier.fillMaxSize()) {
                            order.forEach { pageIndex ->
                                ReaderPage(
                                    viewModel.pageModel,
                                    pageIndex,
                                    readingDirectionRtl,
                                    viewModel.invertTapZones,
                                    isVertical,
                                    onZoneTap,
                                    onZoomChanged = { zoomedIn = it },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isVertical) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = !zoomedIn,
                modifier = Modifier.fillMaxSize(),
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                reverseLayout = readingDirectionRtl,
                userScrollEnabled = !zoomedIn,
                modifier = Modifier.fillMaxSize(),
                pageContent = pageContent,
            )
        }

        if (showChrome && pagerState.currentPage < units.size) {
            val rawPage = progressIndexFor(units.getOrNull(pagerState.currentPage))
            ReaderChrome(
                seriesTitle = viewModel.seriesTitle,
                chapterTitle = viewModel.chapter.displayName,
                currentPage = rawPage,
                pageCount = pageCount,
                readingMode = readingMode,
                onReadingModeChange = viewModel::setReadingMode,
                onBack = onBack,
                onSeek = { target ->
                    scope.launch { pagerState.scrollToPage(unitIndexForPage(units, target)) }
                },
                onScrubbingChanged = onScrubbingChanged,
            )
        }
    }
}

/** VERTICAL_CONTINUOUS (webtoon): every page stacked in one continuously scrollable column, no
 * snapping. Simpler interaction than the paged modes — a single tap anywhere toggles the chrome;
 * each page supports the same double-tap/pinch zoom as the paged modes, which disables the
 * column's own scrolling while zoomed (mirroring the paged pager's `userScrollEnabled`) so a pan
 * on a zoomed image doesn't also scroll the list. Scrolling past the last page reaches a
 * next-chapter preview slot, same as the paged modes; scrolling it fully into view (or tapping
 * it) switches chapters. */
@Composable
private fun ContinuousReader(
    viewModel: ReaderViewModel,
    pageCount: Int,
    pageAspectRatios: List<Float>,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    showChrome: Boolean,
    onToggleChrome: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigateToChapter: (String) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = viewModel.currentPage.value.coerceIn(0, pageCount - 1))
    val scope = rememberCoroutineScope()
    val nextChapter by viewModel.nextChapter.collectAsState()
    // Chapters made of many short images (e.g. text-over-image panels) can be shorter overall
    // than one viewport once every image finishes loading — Compose's LazyColumn then settles
    // with nothing left to scroll on its own, with zero user input. Without this guard that
    // passive settling looked identical to "the user scrolled to the end", so short chapters
    // auto-completed and cascaded into the next one (and the next…) before anyone had read them.
    var hasInteracted by remember { mutableStateOf(false) }
    var anyZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            viewModel.onPageChanged(index.coerceIn(0, pageCount - 1))
        }
    }

    // The next-chapter item is sized to exactly one viewport (fillParentMaxSize), so scrolling
    // it fully into view is simultaneously hitting the end of the scrollable range — no separate
    // "settle" concept needed like the paged pager's snap points.
    LaunchedEffect(listState, pageCount, nextChapter, hasInteracted) {
        if (!hasInteracted) return@LaunchedEffect
        val next = nextChapter ?: return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == pageCount && !listState.canScrollForward
        }.first { it }
        onNavigateToChapter(next.id)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = !anyZoomed,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageCount, key = { it }) { index ->
                WebtoonPage(
                    pageModel = viewModel.pageModel,
                    index = index,
                    // Reserves the image's real height up front instead of measuring it as
                    // zero/placeholder-sized until Coil decodes the bitmap — otherwise, as each
                    // of many short images resolved its true (small) height, LazyColumn kept
                    // remeasuring to keep the viewport filled and walked the scroll position
                    // forward with no user input, landing on the last page on open.
                    aspectRatio = pageAspectRatios[index],
                    onInteracted = { hasInteracted = true },
                    onZoomChanged = { anyZoomed = it },
                    onTap = { onToggleChrome() },
                )
            }
            nextChapter?.let { next ->
                item(key = "next_chapter") {
                    Box(
                        Modifier.fillParentMaxSize().clickable { onNavigateToChapter(next.id) },
                    ) {
                        NextChapterPreview(next)
                    }
                }
            }
        }
        if (showChrome) {
            ReaderChrome(
                seriesTitle = viewModel.seriesTitle,
                chapterTitle = viewModel.chapter.displayName,
                currentPage = listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                readingMode = readingMode,
                onReadingModeChange = onReadingModeChange,
                onBack = onBack,
                onSeek = { target -> scope.launch { listState.scrollToItem(target.coerceIn(0, pageCount - 1)) } },
                onScrubbingChanged = onScrubbingChanged,
            )
        }
    }
}

/** Series/chapter info up top; a scrubbable slider along the bottom for quick page jumps (both
 * toggled by the center tap zone in paged modes, or any tap in continuous mode). */
@Composable
private fun BoxScope.ReaderChrome(
    seriesTitle: String,
    chapterTitle: String,
    currentPage: Int,
    pageCount: Int,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    onBack: () -> Unit,
    onSeek: (Int) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f)) {
            if (seriesTitle.isNotBlank()) {
                Text(seriesTitle, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(chapterTitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ReadingModeSwitcher(current = readingMode, onSelect = onReadingModeChange)
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 0.dp),
    ) {
        var dragValue by remember { mutableStateOf<Float?>(null) }
        if (pageCount > 1) {
            Slider(
                value = dragValue ?: currentPage.toFloat(),
                onValueChange = {
                    dragValue = it
                    onScrubbingChanged(true)
                },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it.roundToInt().coerceIn(0, pageCount - 1)) }
                    dragValue = null
                    onScrubbingChanged(false)
                },
                valueRange = 0f..(pageCount - 1).toFloat(),
                // One discrete stop per page, rather than a free-scrubbing continuous drag.
                steps = (pageCount - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "${currentPage + 1} / $pageCount",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** Quick-switcher on the reader's chrome: shows the active mode, opens a dropdown of the other
 * three on tap. Lets the mode change while looking at the pages, without a trip to Settings —
 * [onSelect] is wired to [ReaderViewModel.setReadingMode], which persists per series. */
@Composable
private fun ReadingModeSwitcher(current: ReadingMode, onSelect: (ReadingMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.shortLabel(), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReadingMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label()) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Shown when swiping past the last page of the chapter, sliding in with the pager's own
 * motion; settling on it switches to that chapter (PLAN.md §8.1). */
@Composable
private fun NextChapterPreview(chapter: ChapterCard) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth(0.55f).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                val coverModel = chapter.coverModel
                if (coverModel != null) {
                    AsyncImage(
                        model = MangaCover(coverModel),
                        contentDescription = chapter.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Text(chapter.displayName.trim().take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Next chapter", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Text(
                chapter.displayName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}

private fun unitIndexForPage(units: List<PageUnit>, page: Int): Int =
    units.indexOfFirst { unit ->
        when (unit) {
            is PageUnit.Single -> unit.index >= page
            is PageUnit.Spread -> unit.first >= page || unit.second >= page
        }
    }.let { if (it < 0) units.lastIndex.coerceAtLeast(0) else it }

private fun progressIndexFor(unit: PageUnit?): Int = when (unit) {
    null -> 0
    is PageUnit.Single -> unit.index
    is PageUnit.Spread -> maxOf(unit.first, unit.second)
}

private fun scrollBy(pagerState: PagerState, scope: CoroutineScope, delta: Int, unitCount: Int) {
    val target = (pagerState.currentPage + delta).coerceIn(0, unitCount - 1)
    scope.launch { pagerState.animateScrollToPage(target) }
}

/** Base tap-zone semantics: horizontal respects RTL (first third advances in RTL); vertical has
 * no reversed direction (top-to-bottom is the only order). [invertTapZones] flips whichever side
 * that resolves to advance — the "user-configurable layout" from PLAN.md §8.1. */
private fun computeTapZone(pos: Offset, size: IntSize, isRtl: Boolean, invertTapZones: Boolean, isVertical: Boolean): TapZone {
    val coordinate = if (isVertical) pos.y else pos.x
    val extent = if (isVertical) size.height else size.width
    val third = extent / 3f
    val firstThirdIsForward = (if (isVertical) false else isRtl) xor invertTapZones
    return when {
        coordinate < third -> if (firstThirdIsForward) TapZone.FORWARD else TapZone.BACKWARD
        coordinate > third * 2 -> if (firstThirdIsForward) TapZone.BACKWARD else TapZone.FORWARD
        else -> TapZone.MENU
    }
}

/** Zoom pivot pinned to the content's own top-left, so [zoomOffset] alone (not a moving
 * `transformOrigin`) is what keeps an arbitrary pinch/double-tap point visually still. */
private val ZoomPivot = TransformOrigin(0f, 0f)

/**
 * New translation that keeps the point at [centroid] visually fixed (plus any [pan] the fingers
 * made since) when scale changes from [oldScale] to [newScale], given [ZoomPivot] is the origin.
 * Derived from `screen(p) = p * scale + translation`: solve for the local point under the
 * centroid before the change, then require it renders at `centroid + pan` after.
 */
private fun zoomOffset(offset: Offset, oldScale: Float, newScale: Float, centroid: Offset, pan: Offset): Offset {
    val ratio = newScale / oldScale
    return offset * ratio + centroid * (1f - ratio) + pan
}

/** Pinch range: below 1 shrinks the page smaller than "fit" (e.g. to see a whole spread at
 * once), above 1 is the usual zoom-in-for-detail. */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 5f

/** Whether [scale] has drifted from the default "fit" size in *either* direction — used to
 * gate tap-vs-pan dispatch and to disable the pager/column's own scroll while pinched out too,
 * not just while pinched in. */
private fun isZoomed(scale: Float) = abs(scale - 1f) > 0.01f

/** Pinch/double-tap zoom shared by [ReaderPage] and [WebtoonPage]: scales/pans an [AsyncImage] in
 * a `graphicsLayer`, pivoting around wherever the gesture actually is via [zoomOffset] rather than
 * always around the screen's center. [onGesture] runs first on every pointer event (pinch or
 * plain drag) so callers can track "the user touched the reader" independent of whether a zoom
 * actually resulted; it only takes over the gesture for 2+ fingers or once already zoomed, so a
 * plain single-finger drag at scale 1 is left untouched to reach the pager's swipe or the
 * webtoon column's own scroll. */
@Composable
private fun ZoomableImage(
    pageModel: String,
    index: Int,
    contentScale: ContentScale,
    onZoomChanged: (Boolean) -> Unit,
    onGesture: () -> Unit,
    modifier: Modifier,
    // Webtoon-only (PLAN.md §8.1): releasing a pinch that shrank the page below "fit" animates
    // straight back to it instead of leaving the strip shrunk and its own scroll disabled —
    // there's no "inspect while shrunk" use case the way there is for zooming in, so the
    // continuous-scroll feel should always come back once fingers lift.
    snapBackWhenZoomedOut: Boolean = false,
    tapGestures: suspend PointerInputScope.(
        zoomed: () -> Boolean,
        applyZoom: (Float, Offset, Offset) -> Unit,
        resetZoom: () -> Unit,
    ) -> Unit,
) {
    // Keyed on `index` so zoom/pan resets when the pager/column moves past this page.
    var scale by remember(index) { mutableStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    // Pinching continuously through scale 1 should feel seamless in either direction, so this
    // never snaps offset back to zero on its own — only an explicit resetZoom() (double-tap, or
    // snapBackWhenZoomedOut once fingers lift) recenters, since a pinch that happens to land
    // near 1x mid-gesture isn't asking to be recentered.
    fun applyZoom(newScale: Float, centroid: Offset, pan: Offset) {
        val coerced = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = zoomOffset(offset, scale, coerced, centroid, pan)
        scale = coerced
        onZoomChanged(isZoomed(scale))
    }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
        onZoomChanged(false)
    }

    // Animates scale/offset back to "fit", centered — used once fingers lift on a pinch that
    // ended below 1x. A plain coroutine, not called from inside awaitPointerEventScope: that
    // scope only permits a restricted set of suspend calls and animateTo isn't one of them.
    fun animateSnapBack() {
        val fromScale = scale
        val fromOffset = offset
        scope.launch {
            Animatable(0f).animateTo(1f, tween(200)) {
                scale = fromScale + (1f - fromScale) * value
                offset = fromOffset * (1f - value)
            }
            onZoomChanged(false)
        }
    }

    Box(
        modifier
            .pointerInput(index) { tapGestures({ isZoomed(scale) }, ::applyZoom, ::resetZoom) }
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onGesture()
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || isZoomed(scale)) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                applyZoom(scale * zoomChange, centroid, panChange)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (snapBackWhenZoomedOut && scale < 1f) animateSnapBack()
                }
            },
    ) {
        AsyncImage(
            model = MangaPage(pageModel, index),
            contentDescription = "Page ${index + 1}",
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
                transformOrigin = ZoomPivot,
            ),
        )
    }
}

@Composable
private fun ReaderPage(
    pageModel: String,
    index: Int,
    isRtl: Boolean,
    invertTapZones: Boolean,
    isVertical: Boolean,
    onZoneTap: (TapZone) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    ZoomableImage(
        pageModel = pageModel,
        index = index,
        contentScale = ContentScale.Fit,
        onZoomChanged = onZoomChanged,
        onGesture = {},
        modifier = modifier,
    ) { zoomed, applyZoom, resetZoom ->
        detectTapGestures(
            onDoubleTap = { pos ->
                if (zoomed()) resetZoom() else applyZoom(2.5f, pos, Offset.Zero)
            },
            onTap = { pos ->
                if (zoomed()) return@detectTapGestures // zoomed: taps pan, not navigate
                onZoneTap(computeTapZone(pos, size, isRtl, invertTapZones, isVertical))
            },
        )
    }
}

/** One webtoon page: same pinch/double-tap zoom as [ReaderPage], but a plain tap toggles the
 * chrome instead of dispatching to a tap zone (PLAN.md §8.1 — no left/right concept scrolling
 * straight down). [onInteracted] feeds [ContinuousReader]'s "has the user actually touched this
 * reader" gate for its scroll-to-end auto-navigate. */
@Composable
private fun WebtoonPage(
    pageModel: String,
    index: Int,
    aspectRatio: Float,
    onInteracted: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    ZoomableImage(
        pageModel = pageModel,
        index = index,
        contentScale = ContentScale.FillWidth,
        onZoomChanged = onZoomChanged,
        onGesture = onInteracted,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
        snapBackWhenZoomedOut = true,
    ) { zoomed, applyZoom, resetZoom ->
        detectTapGestures(
            onDoubleTap = { pos ->
                onInteracted()
                if (zoomed()) resetZoom() else applyZoom(2.5f, pos, Offset.Zero)
            },
            onTap = {
                onInteracted()
                if (!zoomed()) onTap()
            },
        )
    }
}

@Composable
private fun GestureHelpOverlay(isRtl: Boolean, invertTapZones: Boolean, isVertical: Boolean, onDismiss: () -> Unit) {
    val firstThirdIsForward = (if (isVertical) false else isRtl) xor invertTapZones
    val (startLabel, endLabel) = if (isVertical) "top" to "bottom" else "left" to "right"
    val forwardSide = if (firstThirdIsForward) startLabel else endLabel
    val backSide = if (firstThirdIsForward) endLabel else startLabel
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Tap $forwardSide to go forward, $backSide to go back, center for menu.\n" +
                "Double-tap or pinch to zoom. Volume keys turn pages.\n\nTap anywhere to start reading.",
            color = Color.White,
            modifier = Modifier.padding(32.dp),
            textAlign = TextAlign.Center,
        )
    }
}
