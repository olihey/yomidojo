package com.mangaread

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

    // Wait for both the page count AND every page's aspect ratio before building the pager,
    // so spread pairing (which needs the full picture) never has to reshuffle mid-read.
    if (pageCount <= 0 || wideFlags.size < pageCount) {
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
                readingMode = readingMode,
                onReadingModeChange = viewModel::setReadingMode,
                showChrome = showChrome,
                onShowChromeChange = { showChrome = it },
                onScrubbingChanged = { isScrubbing = it },
                onBack = onBack,
            )
        } else {
            PagedReader(
                viewModel = viewModel,
                pageCount = pageCount,
                wideFlags = wideFlags,
                readingMode = readingMode,
                readingDirectionRtl = readingDirectionRtl,
                showChrome = showChrome,
                onShowChromeChange = { showChrome = it },
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
    onShowChromeChange: (Boolean) -> Unit,
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
                        TapZone.MENU -> onShowChromeChange(!showChrome)
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
 * no pinch-zoom or next-chapter preview yet (designed-for, not built — PLAN.md §8.1). */
@Composable
private fun ContinuousReader(
    viewModel: ReaderViewModel,
    pageCount: Int,
    readingMode: ReadingMode,
    onReadingModeChange: (ReadingMode) -> Unit,
    showChrome: Boolean,
    onShowChromeChange: (Boolean) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = viewModel.currentPage.value.coerceIn(0, pageCount - 1))
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            viewModel.onPageChanged(index.coerceIn(0, pageCount - 1))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { onShowChromeChange(!showChrome) })
            },
        ) {
            items(pageCount, key = { it }) { index ->
                AsyncImage(
                    model = MangaPage(viewModel.pageModel, index),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    // Keyed on `index` so zoom/pan resets when the pager moves to a different page.
    var scale by remember(index) { mutableStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var origin by remember(index) { mutableStateOf(TransformOrigin.Center) }

    fun applyScale(newScale: Float) {
        scale = newScale.coerceIn(1f, 5f)
        if (scale <= 1f) offset = Offset.Zero
        onZoomChanged(scale > 1.01f)
    }

    Box(
        modifier
            .pointerInput(isRtl, invertTapZones, isVertical, index) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            origin = TransformOrigin.Center
                            applyScale(1f)
                        } else {
                            origin = TransformOrigin(pos.x / size.width, pos.y / size.height)
                            applyScale(2.5f)
                        }
                    },
                    onTap = { pos ->
                        if (scale > 1f) return@detectTapGestures // zoomed: taps pan, not navigate
                        onZoneTap(computeTapZone(pos, size, isRtl, invertTapZones, isVertical))
                    },
                )
            }
            // Only takes over for a real pinch (2+ fingers) or panning while already zoomed —
            // a plain single-finger drag at scale 1 is untouched so it reaches the pager's own
            // swipe-to-turn-page gesture instead of being consumed here.
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || scale > 1f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                applyScale(scale * zoomChange)
                                if (scale > 1f) offset += panChange
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = MangaPage(pageModel, index),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
                transformOrigin = origin,
            ),
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
