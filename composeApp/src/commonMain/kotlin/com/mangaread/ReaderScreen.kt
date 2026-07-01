package com.mangaread

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class TapZone { BACKWARD, FORWARD, MENU }

/**
 * Pure UI over "page N of a chapter" (PLAN.md §8), plus the gesture/polish bundle (§8.1):
 * RTL-aware tap zones, double-tap zoom, keep-screen-on, volume-key paging, a one-time
 * gesture-help overlay, and double-page spread pairing on wide (landscape/tablet) containers.
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
        val showGestureHelp by viewModel.showGestureHelp.collectAsState()

        LaunchedEffect(pagerState, units) {
            snapshotFlow { pagerState.currentPage }.collect { unitIndex ->
                viewModel.onPageChanged(progressIndexFor(units.getOrNull(unitIndex)))
            }
        }

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
                is PageUnit.Single -> ReaderPage(viewModel.pageModel, unit.index, viewModel.readingDirectionRtl, onZoneTap)
                is PageUnit.Spread -> {
                    val order = if (viewModel.readingDirectionRtl) listOf(unit.second, unit.first) else listOf(unit.first, unit.second)
                    Row(Modifier.fillMaxSize()) {
                        order.forEach { pageIndex ->
                            ReaderPage(
                                viewModel.pageModel,
                                pageIndex,
                                viewModel.readingDirectionRtl,
                                onZoneTap,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
        if (showChrome) {
            Text(
                "${pagerState.currentPage + 1} / ${units.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (showGestureHelp) {
            GestureHelpOverlay(isRtl = viewModel.readingDirectionRtl, onDismiss = viewModel::dismissGestureHelp)
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

@Composable
private fun ReaderPage(
    pageModel: String,
    index: Int,
    isRtl: Boolean,
    onZoneTap: (TapZone) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    var scale by remember { mutableStateOf(1f) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    Box(
        modifier
            .pointerInput(isRtl) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            scale = 1f
                            origin = TransformOrigin.Center
                        } else {
                            scale = 2.5f
                            origin = TransformOrigin(pos.x / size.width, pos.y / size.height)
                        }
                    },
                    onTap = { pos ->
                        val third = size.width / 3f
                        val zone = when {
                            pos.x < third -> if (isRtl) TapZone.FORWARD else TapZone.BACKWARD
                            pos.x > third * 2 -> if (isRtl) TapZone.BACKWARD else TapZone.FORWARD
                            else -> TapZone.MENU
                        }
                        onZoneTap(zone)
                    },
                )
            },
    ) {
        AsyncImage(
            model = MangaPage(pageModel, index),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = origin),
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
                "Double-tap to zoom. Volume keys turn pages.\n\nTap anywhere to start reading.",
            color = Color.White,
            modifier = Modifier.padding(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
