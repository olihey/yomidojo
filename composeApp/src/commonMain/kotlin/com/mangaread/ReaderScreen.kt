package com.mangaread

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class TapZone { BACKWARD, FORWARD, MENU }

/**
 * Pure UI over "page N of a chapter" (PLAN.md §8), plus the gesture/polish bundle (§8.1):
 * RTL-aware tap zones, double-tap + pinch zoom with pan, keep-screen-on, volume-key paging, a
 * one-time gesture-help overlay, double-page spread pairing on wide containers, and a chrome
 * overlay showing series/chapter info plus a read-progress bar.
 */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val pairPortrait = maxWidth > maxHeight
        val units = remember(wideFlags, pairPortrait) { buildPageUnits(pageCount, wideFlags, pairPortrait) }
        val initialUnit = remember(units) { unitIndexForPage(units, viewModel.currentPage.value) }

        val pagerState = rememberPagerState(initialPage = initialUnit.coerceIn(0, units.size - 1)) { units.size }
        val scope = rememberCoroutineScope()
        var showChrome by remember { mutableStateOf(true) }
        var zoomedIn by remember { mutableStateOf(false) }
        val showGestureHelp by viewModel.showGestureHelp.collectAsState()

        LaunchedEffect(pagerState, units) {
            snapshotFlow { pagerState.currentPage }.collect { unitIndex ->
                viewModel.onPageChanged(progressIndexFor(units.getOrNull(unitIndex)))
            }
        }
        // A page always starts unzoomed, so leaving it should re-enable pager swiping.
        LaunchedEffect(pagerState.currentPage) { zoomedIn = false }

        DisposableEffect(viewModel.readingDirectionRtl, units.size) {
            VolumeKeyBus.onVolumeKey = { down ->
                val forward = if (viewModel.readingDirectionRtl) !down else down
                scrollBy(pagerState, scope, if (forward) 1 else -1, units.size)
                true
            }
            onDispose { VolumeKeyBus.onVolumeKey = null }
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = viewModel.readingDirectionRtl,
            userScrollEnabled = !zoomedIn,
            modifier = Modifier.fillMaxSize(),
        ) { unitIndex ->
            val onZoneTap: (TapZone) -> Unit = { zone ->
                when (zone) {
                    TapZone.FORWARD -> scrollBy(pagerState, scope, 1, units.size)
                    TapZone.BACKWARD -> scrollBy(pagerState, scope, -1, units.size)
                    TapZone.MENU -> showChrome = !showChrome
                }
            }
            when (val unit = units[unitIndex]) {
                is PageUnit.Single -> ReaderPage(
                    viewModel.pageModel, unit.index, viewModel.readingDirectionRtl, onZoneTap,
                    onZoomChanged = { zoomedIn = it },
                )
                is PageUnit.Spread -> {
                    val order = if (viewModel.readingDirectionRtl) listOf(unit.second, unit.first) else listOf(unit.first, unit.second)
                    Row(Modifier.fillMaxSize()) {
                        order.forEach { pageIndex ->
                            ReaderPage(
                                viewModel.pageModel,
                                pageIndex,
                                viewModel.readingDirectionRtl,
                                onZoneTap,
                                onZoomChanged = { zoomedIn = it },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
        if (showChrome) {
            val rawPage = progressIndexFor(units.getOrNull(pagerState.currentPage))
            ReaderChrome(
                seriesTitle = viewModel.seriesTitle,
                chapterTitle = viewModel.chapter.displayName,
                currentPage = rawPage,
                pageCount = pageCount,
                onBack = onBack,
            )
        }
        if (showGestureHelp) {
            GestureHelpOverlay(isRtl = viewModel.readingDirectionRtl, onDismiss = viewModel::dismissGestureHelp)
        }
    }
}

/** Series/chapter info up top, a read-progress bar along the bottom (both toggled by the center tap zone). */
@Composable
private fun BoxScope.ReaderChrome(
    seriesTitle: String,
    chapterTitle: String,
    currentPage: Int,
    pageCount: Int,
    onBack: () -> Unit,
) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        Column {
            if (seriesTitle.isNotBlank()) {
                Text(seriesTitle, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(chapterTitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        LinearProgressIndicator(
            progress = { if (pageCount > 0) (currentPage + 1) / pageCount.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
        )
        Text(
            "${currentPage + 1} / $pageCount",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
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

@Composable
private fun ReaderPage(
    pageModel: String,
    index: Int,
    isRtl: Boolean,
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
            .pointerInput(isRtl, index) {
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
                        val third = size.width / 3f
                        val zone = when {
                            pos.x < third -> if (isRtl) TapZone.FORWARD else TapZone.BACKWARD
                            pos.x > third * 2 -> if (isRtl) TapZone.BACKWARD else TapZone.FORWARD
                            else -> TapZone.MENU
                        }
                        onZoneTap(zone)
                    },
                )
            }
            .pointerInput(index) {
                detectTransformGestures { _, pan, zoom, _ ->
                    applyScale(scale * zoom)
                    if (scale > 1f) offset += pan
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
private fun GestureHelpOverlay(isRtl: Boolean, onDismiss: () -> Unit) {
    val forwardSide = if (isRtl) "left" else "right"
    val backSide = if (isRtl) "right" else "left"
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
