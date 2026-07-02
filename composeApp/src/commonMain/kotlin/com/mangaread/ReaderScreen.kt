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
import androidx.compose.ui.platform.LocalDensity
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
                        viewModel.pageModel, viewModel.chapter.size, unit.index, readingDirectionRtl,
                        viewModel.invertTapZones, isVertical, onZoneTap, onZoomChanged = { zoomedIn = it },
                    )
                    is PageUnit.Spread -> {
                        val order = if (readingDirectionRtl) listOf(unit.second, unit.first) else listOf(unit.first, unit.second)
                        Row(Modifier.fillMaxSize()) {
                            order.forEach { pageIndex ->
                                ReaderPage(
                                    viewModel.pageModel,
                                    viewModel.chapter.size,
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

        // Keeps the adjacent page composed (and its AsyncImage fetching) before it's actually
        // scrolled into view, so the fetch/decode already happened by the time the user swipes —
        // matters most on a network source (SMB, PLAN.md §6.2), where a fetch is a real
        // round-trip instead of a fast local read.
        if (isVertical) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = !zoomedIn,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
                pageContent = pageContent,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                reverseLayout = readingDirectionRtl,
                userScrollEnabled = !zoomedIn,
                beyondViewportPageCount = 1,
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
 * the same double-tap/pinch zoom as the paged modes applies to the *whole column at once*
 * ([Zoomable] wraps the `LazyColumn` itself, not each page), so the strip scales as one seamless
 * unit instead of only the page under your fingers. Zooming in disables the column's own
 * scrolling (mirroring the paged pager's `userScrollEnabled`) so a pan on the zoomed strip doesn't
 * also scroll it. Scrolling past the last page reaches a next-chapter preview slot, same as the
 * paged modes; scrolling it fully into view (or tapping it) switches chapters. */
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
        // Zoom wraps the whole LazyColumn (not each page individually) so pinching scales the
        // entire visible strip as one seamless unit, matching how the continuous scroll itself
        // treats every page as part of one long image rather than separate slides.
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Only used to compute each page's *intrinsic* (scale = 1) pixel height from its
            // aspectRatio, for the zoom-out scroll compensation below — not to inflate anything.
            val fullWidthPx = with(density) { maxWidth.toPx() }
            val cumulativeHeights = remember(pageAspectRatios, fullWidthPx) {
                FloatArray(pageCount + 1).also { heights ->
                    for (i in 0 until pageCount) heights[i + 1] = heights[i] + fullWidthPx / pageAspectRatios[i]
                }
            }
            Zoomable(
                key = Unit,
                onZoomChanged = {},
                onGesture = { hasInteracted = true },
                modifier = Modifier.fillMaxSize(),
                freezeOffsetBelowOne = true,
                tapGestures = { currentScale, applyZoom, resetZoom ->
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            hasInteracted = true
                            if (isZoomed(currentScale())) resetZoom() else applyZoom(2.5f, pos, Offset.Zero)
                        },
                        onTap = {
                            hasInteracted = true
                            if (!isZoomedIn(currentScale())) onToggleChrome()
                        },
                    )
                },
                // Shrinking each page's own layout size (below) means the LazyColumn's normal
                // scroll-position bookkeeping — an (item index, pixel offset) pair — silently
                // drifts across a zoom-out: Compose keeps the same *raw pixel* offset into the
                // first visible item, which was measured against the *old* (bigger) page size, so
                // reinterpreting it against the new, smaller page systematically walks the scroll
                // position forward. Left uncorrected, that reads as the pinch centroid sliding
                // upward — smoothly for zoom-in (which never touches the column's own scroll,
                // just pans within already-composed content), but not for this per-page-shrink
                // approach. Fix: recompute, from our own intrinsic-height model (scale-independent
                // by construction), the exact (index, offset) pair that keeps the *intrinsic*
                // content point under the pinch centroid fixed, and jump straight there — rather
                // than trust Compose's own offset-preservation heuristic, which only reasons in
                // raw pixels and has no notion of "the page shrank."
                onScaleChange = { old, new, centroid ->
                    if (old < 1f && new < 1f) {
                        val startIndex = listState.firstVisibleItemIndex.coerceIn(0, pageCount)
                        val tIntrinsic = cumulativeHeights[startIndex.coerceAtMost(pageCount)] +
                            listState.firstVisibleItemScrollOffset / old
                        val pIntrinsic = tIntrinsic + centroid.y / old
                        val tTarget = pIntrinsic - centroid.y / new
                        var lo = 0
                        var hi = pageCount
                        while (lo < hi) {
                            val mid = (lo + hi + 1) / 2
                            if (cumulativeHeights[mid] <= tTarget) lo = mid else hi = mid - 1
                        }
                        val targetIndex = lo.coerceIn(0, pageCount - 1)
                        val targetOffset = ((tTarget - cumulativeHeights[targetIndex]) * new)
                            .roundToInt().coerceAtLeast(0)
                        scope.launch { listState.scrollToItem(targetIndex, targetOffset) }
                    }
                },
            ) { scale, offset ->
                // Zooming in scales the whole rendered column as one unit (graphicsLayer, panning
                // via offset, scroll disabled) — the same model the paged modes use. Zooming out
                // can't use that trick: shrinking a fixed-size already-laid-out column with a
                // post-layout paint transform leaves the freed-up space blank, since a LazyColumn
                // only ever composes enough items to fill its own measured height (an earlier
                // attempt tried inflating that measured height and scaling back down, but
                // Modifier.height silently coerces to the parent's real constraints, and even
                // requiredHeight would only reveal more content *below* the current scroll
                // position, not distribute it around the gesture). Instead, when zoomed out, each
                // page shrinks its own layout size (width, and — via aspectRatio — proportionally
                // its height) via [WebtoonPage]'s widthFraction, so the LazyColumn just measures
                // and scrolls a genuinely denser strip of smaller pages: no post-hoc scaling, no
                // borders, and scroll position behaves exactly like normal scrolling because it is
                // (the scroll compensation above is what keeps that scrolling anchored correctly).
                val zoomedIn = scale >= 1f
                LazyColumn(
                    state = listState,
                    userScrollEnabled = !isZoomedIn(scale),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = if (zoomedIn) scale else 1f,
                            scaleY = if (zoomedIn) scale else 1f,
                            translationX = if (zoomedIn) offset.x else 0f,
                            translationY = if (zoomedIn) offset.y else 0f,
                            transformOrigin = ZoomPivot,
                        ),
                ) {
                    items(pageCount, key = { it }) { index ->
                        WebtoonPage(
                            pageModel = viewModel.pageModel,
                            chapterSize = viewModel.chapter.size,
                            index = index,
                            // Reserves the image's real height up front instead of measuring it as
                            // zero/placeholder-sized until Coil decodes the bitmap — otherwise, as
                            // each of many short images resolved its true (small) height,
                            // LazyColumn kept remeasuring to keep the viewport filled and walked
                            // the scroll position forward with no user input, landing on the last
                            // page on open.
                            aspectRatio = pageAspectRatios[index],
                            widthFraction = if (zoomedIn) 1f else scale,
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

/** Whether [scale] has drifted from the default "fit" size in *either* direction — this is only
 * the double-tap toggle's notion of "zoomed" (reset to fit vs jump to the 2.5x preset). */
private fun isZoomed(scale: Float) = abs(scale - 1f) > 0.01f

/** Whether [scale] is zoomed *in* specifically — gates pan-vs-scroll and tap-vs-navigate
 * dispatch, and whether the pager/column's own scroll is disabled. Zooming *out* deliberately
 * doesn't count: a shrunk page still scrolls/taps normally, it's just smaller on screen, so
 * there's no reason to also lock the reader into a "must pinch back in first" mode. */
private fun isZoomedIn(scale: Float) = scale > 1.01f

/**
 * Pinch/double-tap zoom shared by [ReaderPage] (one image) and [ContinuousReader] (the *whole*
 * webtoon strip at once, via [content] = the entire `LazyColumn` — zooming only the page under
 * your fingers wouldn't be seamless with the rest of the continuous scroll). Hoists scale+offset,
 * wires up the gesture detectors, and applies the resulting `graphicsLayer` transform to
 * [content], pivoting around wherever the gesture actually is via [zoomOffset] rather than always
 * around the screen's center. [onGesture] runs first on every pointer event (pinch or plain drag)
 * so callers can track "the user touched the reader" independent of whether a zoom actually
 * resulted; it only takes over the gesture for 2+ fingers or once already zoomed *in*, so a plain
 * single-finger drag at scale ≤ 1 is left untouched to reach the pager's swipe or the webtoon
 * column's own scroll — including right after zooming *out*, once fingers lift: releasing a pinch
 * that shrank the content just recenters it in place rather than resetting to fit, so it stays
 * shrunk and normal scrolling keeps working.
 */
@Composable
private fun Zoomable(
    key: Any,
    onZoomChanged: (Boolean) -> Unit,
    onGesture: () -> Unit,
    modifier: Modifier,
    tapGestures: suspend PointerInputScope.(
        scale: () -> Float,
        applyZoom: (Float, Offset, Offset) -> Unit,
        resetZoom: () -> Unit,
    ) -> Unit,
    // Fires on every raw scale change (pinch or double-tap), before recomposition — lets a caller
    // whose content isn't a simple graphicsLayer target (ContinuousReader shrinks pages' own
    // layout size instead) react to the transition itself, not just the resulting state.
    onScaleChange: (oldScale: Float, newScale: Float, centroid: Offset) -> Unit = { _, _, _ -> },
    // ContinuousReader ignores offset entirely below 1x (that regime shrinks pages' own layout
    // size instead), so letting it keep evolving there — off-screen, unseen — left it holding a
    // stale, arbitrary value from wherever the fingers wandered while zoomed out. The instant a
    // later pinch crossed back to zoomed-in and this content started reading offset again, the
    // page would jump to that stale value instead of the fresh position a clean 1x crossing
    // should start from. ReaderPage has no such split (it always uses offset, even below 1x, to
    // pan the shrunk image smoothly per §8.1) so it keeps the default of never freezing it.
    freezeOffsetBelowOne: Boolean = false,
    content: @Composable (scale: Float, offset: Offset) -> Unit,
) {
    // Keyed so zoom/pan resets when the pager moves to a different page (per-page usage) — the
    // whole-strip usage keys on a constant, since one shared zoom state covers every page.
    var scale by remember(key) { mutableStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    // Pinching continuously through scale 1 should feel seamless in either direction, so this
    // never snaps offset back on its own — only an explicit resetZoom() (double-tap) or
    // recenter() (releasing a zoom-out) touches it, since a pinch that happens to pass through
    // 1x mid-gesture isn't asking to be recentered.
    fun applyZoom(newScale: Float, centroid: Offset, pan: Offset) {
        val coerced = newScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = if (freezeOffsetBelowOne && coerced < 1f) Offset.Zero else zoomOffset(offset, scale, coerced, centroid, pan)
        val old = scale
        scale = coerced
        onZoomChanged(isZoomedIn(scale))
        if (coerced != old) onScaleChange(old, coerced, centroid)
    }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
        onZoomChanged(false)
    }

    // Animates the offset (scale untouched) to whatever centers the current, still-shrunk scale
    // within [size] — used once fingers lift on a pinch that ended below 1x, so the content stays
    // the size the user chose instead of springing back to fit. A plain coroutine, not called
    // from inside awaitPointerEventScope: that scope only permits a restricted set of suspend
    // calls and animateTo isn't one of them.
    fun recenter(size: IntSize) {
        val target = Offset(size.width * (1f - scale) / 2f, size.height * (1f - scale) / 2f)
        val start = offset
        scope.launch {
            Animatable(0f).animateTo(1f, tween(200)) {
                offset = start + (target - start) * value
            }
        }
    }

    Box(
        modifier
            .pointerInput(key) { tapGestures({ scale }, ::applyZoom, ::resetZoom) }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onGesture()
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || isZoomedIn(scale)) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                applyZoom(scale * zoomChange, centroid, panChange)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1f) recenter(size)
                }
            },
    ) {
        content(scale, offset)
    }
}

@Composable
private fun ReaderPage(
    pageModel: String,
    chapterSize: Long?,
    index: Int,
    isRtl: Boolean,
    invertTapZones: Boolean,
    isVertical: Boolean,
    onZoneTap: (TapZone) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Zoomable(
        key = index,
        onZoomChanged = onZoomChanged,
        onGesture = {},
        modifier = modifier,
        tapGestures = { currentScale, applyZoom, resetZoom ->
            detectTapGestures(
                onDoubleTap = { pos ->
                    if (isZoomed(currentScale())) resetZoom() else applyZoom(2.5f, pos, Offset.Zero)
                },
                onTap = { pos ->
                    if (isZoomedIn(currentScale())) return@detectTapGestures // zoomed in: taps pan, not navigate
                    onZoneTap(computeTapZone(pos, size, isRtl, invertTapZones, isVertical))
                },
            )
        },
    ) { scale, offset ->
        AsyncImage(
            model = MangaPage(pageModel, index, chapterSize),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
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

/** One webtoon page: a plain image — zoom lives one level up, on the whole `LazyColumn`
 * (PLAN.md §8.1), so the strip scales as a seamless unit instead of only the touched page.
 * [widthFraction] shrinks (and, via [aspectRatio], proportionally shortens) the page itself when
 * zoomed out, rather than visually scaling a fixed-size layout — see [ContinuousReader]. */
@Composable
private fun WebtoonPage(pageModel: String, chapterSize: Long?, index: Int, aspectRatio: Float, widthFraction: Float) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = MangaPage(pageModel, index, chapterSize),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(widthFraction).aspectRatio(aspectRatio),
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
