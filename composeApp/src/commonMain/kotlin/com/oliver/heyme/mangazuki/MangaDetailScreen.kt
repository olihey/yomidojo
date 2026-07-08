package com.oliver.heyme.mangazuki

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.domain.Series
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** [DetailBanner]'s fixed height and how far [DetailInfoSection]'s cover callout overlaps up
 * into its bottom -- shared so the two stay in sync despite being laid out independently now
 * (a fixed backdrop plus separately-scrolling content instead of one Column). */
private val BANNER_HEIGHT = 190.dp
private val BANNER_OVERLAP = 78.dp

/** Vertical room the floating [DetailTopBar] occupies (16dp padding + ~40dp pill/button + 16dp)
 * -- the docked Chapters header starts below it so the back pill never sits on the header text. */
private val TOP_BAR_HEIGHT = 72.dp

/**
 * The "Manga Detail Tablet" design (Claude Design, imported 2026-07-06) applied to the Series
 * screen when browsing normally. Selection mode (long-press, bulk mark read/unread) keeps the
 * plain Material grid in [SeriesScreen] instead -- the design doesn't cover that either, same
 * scoping call as [MangaShelfGrid]. The Fix Metadata dialog also stays Material -- a modal
 * form isn't part of this visual language either.
 *
 * Real cover/banner art still comes from the existing Coil/[MangaCover] pipeline; the design's
 * flat gradient placeholders are reused only for the loading/no-image fallback, matching
 * [ShelfCoverImage]'s role in the Library grid.
 */
@Composable
fun MangaDetailScreen(
    series: Series,
    chapters: List<ChapterCard>,
    titleLanguage: TitleLanguage,
    onBack: () -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onChapterClick: (String) -> Unit,
    onFixMetadata: () -> Unit,
    onLongClickChapter: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onExitSelectionMode: () -> Unit,
) {
    val archivo = mangaArchivo()
    val anton = mangaAnton()
    val title = series.displayTitle(titleLanguage)
    val nextUnread = chapters.firstOrNull { !it.completed }
    val readCount = chapters.count { it.completed }

    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val topBarPx = remember(density) { with(density) { TOP_BAR_HEIGHT.roundToPx() } }
    val bannerFadePx = remember(density) { with(density) { BANNER_HEIGHT.toPx() } }
    // Docks when the real in-grid Chapters header row (item index 1, after the info section)
    // reaches the dock line just under the floating top bar -- NOT the viewport top. The docked
    // copy then appears exactly where the row already is, making the swap pixel-aligned instead
    // of the row visibly jumping back down by the top bar's height. Once the row scrolls out of
    // the visible set entirely, fall back to "anything past the info section = docked".
    // derivedStateOf so per-frame scrolling only recomposes when the boolean actually flips.
    val chaptersHeaderDocked by remember {
        derivedStateOf {
            val header = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 }
            if (header != null) header.offset.y <= topBarPx else gridState.firstVisibleItemIndex >= 1
        }
    }

    Box(Modifier.fillMaxSize().background(MangaColors.Bg)) {
        // Fixed backdrop -- unlike the rest of the hero (cover, title, description, CTA), this
        // never scrolls. It fades with scroll instead of vanishing when the header docks: alpha
        // reaches 0 over the first banner-height of scroll, well before the dock point, which is
        // also what keeps its bottom strip (attribution label included) from peeking through the
        // grid's transparent side margins below the docked header. Read inside graphicsLayer so
        // per-frame scrolling only redraws the layer, never recomposes.
        DetailBanner(
            series,
            Modifier.align(Alignment.TopStart).graphicsLayer {
                alpha = if (gridState.firstVisibleItemIndex > 0) 0f
                else 1f - (gridState.firstVisibleItemScrollOffset / bannerFadePx).coerceIn(0f, 1f)
            },
        )

        // One grid for the whole screen, not a LazyVerticalGrid nested inside a scrolling
        // Column -- Compose disallows that regardless of userScrollEnabled (a vertically
        // scrollable measured with unbounded height throws). The scrolling hero info and the
        // Chapters header sit in full-width spanning items ahead of the per-chapter tiles.
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DetailInfoSection(series, title, chapters, readCount, nextUnread, onChapterClick, archivo, anton)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                ChaptersHeader(
                    chapters.size, selectionMode, selectedIds.size,
                    onSelectAll, onSelectNone, onMarkRead, onMarkUnread, onExitSelectionMode,
                    archivo, anton,
                )
            }
            gridItemsIndexed(chapters, key = { _, c -> c.id }) { index, c ->
                ChapterTile(
                    c, index, isNext = c.id == nextUnread?.id,
                    selectionMode = selectionMode, selected = c.id in selectedIds,
                    onClick = { onChapterClick(c.id) }, onLongClick = { onLongClickChapter(c.id) }, archivo,
                )
            }
        }
        // Docked copy of the Chapters header: LazyVerticalGrid has no stickyHeader DSL (that's
        // LazyColumn-only through foundation 1.8), so "sticky" is this overlay taking over the
        // moment the real in-grid header row reaches the same spot (see chaptersHeaderDocked).
        // Its opaque background starts at the very top so chapter tiles visibly scroll under
        // neither it nor the floating top bar; the header content itself starts below the bar
        // to keep the back pill off the header text.
        // Fades rather than popping: the real header row is still scrolling underneath during
        // the transition, so this reads as a cross-fade from the moving row to the pinned copy
        // (and back on the way up) instead of the opaque strip snapping into existence.
        AnimatedVisibility(visible = chaptersHeaderDocked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().background(MangaColors.Bg).padding(top = TOP_BAR_HEIGHT)) {
                Box(Modifier.padding(horizontal = 32.dp)) {
                    ChaptersHeader(
                        chapters.size, selectionMode, selectedIds.size,
                        onSelectAll, onSelectNone, onMarkRead, onMarkUnread, onExitSelectionMode,
                        archivo, anton,
                    )
                }
            }
        }
        DetailTopBar(onBack, onFixMetadata, archivo)
    }
}

/** Floats over the banner, same as the design's back pill + action buttons -- not part of the
 * scrolling content, so it stays reachable the whole time. */
@Composable
private fun DetailTopBar(onBack: () -> Unit, onFixMetadata: () -> Unit, archivo: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(30.dp)).background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onBack).padding(start = 10.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.content_desc_back_to_library), tint = Color.White, modifier = Modifier.size(17.dp))
            Text(stringResource(Res.string.detail_library_button), color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        ShelfIconButton(onClick = onFixMetadata, icon = Icons.Default.Build, background = Color.Black.copy(alpha = 0.4f), tint = Color.White)
    }
}

/** The fixed backdrop behind [DetailInfoSection]'s scrolling content -- a plain top-level element
 * behind the grid rather than something nested inside its padded content, so it renders
 * edge-to-edge without needing a negative-padding layout trick. */
@Composable
private fun DetailBanner(series: Series, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(BANNER_HEIGHT)) {
        val (b1, b2) = remember(series.id) { mangaGradientFor(series.id) }
        val bannerPath = series.bannerPath
        if (bannerPath != null) {
            AsyncImage(
                model = MangaCover(bannerPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(b1, b2))))
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to MangaColors.Bg.copy(alpha = 0.55f), 1f to MangaColors.Bg),
            ),
        )
        MetadataAttributionLabel(series.metadataProvider, Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/** Cover callout + author/title/description/CTA -- scrolls normally, unlike [DetailBanner]
 * behind it. A plain [Spacer] leaves the banner's top [BANNER_HEIGHT] - [BANNER_OVERLAP]
 * uncovered, recreating the old single-Column layout's cover-overlaps-banner look without the
 * negative-padding `overlapAbove` hack that design needed -- the banner isn't in this layout
 * at all anymore, it's a fixed element the grid scrolls over. */
@Composable
private fun DetailInfoSection(
    series: Series,
    title: String,
    chapters: List<ChapterCard>,
    readCount: Int,
    nextUnread: ChapterCard?,
    onChapterClick: (String) -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    val percent = if (chapters.isNotEmpty()) readCount * 100 / chapters.size else 0
    val cta = when {
        chapters.isEmpty() -> null
        nextUnread == null -> stringResource(Res.string.detail_cta_read_again)
        readCount == 0 -> stringResource(Res.string.detail_cta_start_reading)
        else -> {
            val (prefix, value) = chapterOrdinal(nextUnread, chapters.indexOf(nextUnread))
            stringResource(Res.string.detail_cta_continue, prefix, value)
        }
    }
    val ctaTarget = nextUnread?.id ?: chapters.firstOrNull()?.id

    Column {
        Spacer(Modifier.height(BANNER_HEIGHT - BANNER_OVERLAP))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(160.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(11.dp))) {
                    ShelfCoverImage(title, series.coverPath, series.id, series.externalId, Modifier.matchParentSize())
                    Box(
                        Modifier.matchParentSize()
                            .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))),
                    )
                    Text(
                        title, color = Color.White, fontFamily = anton, fontSize = 15.sp, lineHeight = 15.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 9.dp),
                    )
                }
                if (chapters.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(Res.string.detail_progress_label), color = MangaColors.TextMuted2, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp, letterSpacing = 1.sp,
                        )
                        Text(
                            stringResource(Res.string.detail_progress_fraction, readCount, chapters.size), color = MangaColors.Text, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF221F1C))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(MangaColors.Accent))
                    }
                }
            }

            Column(Modifier.weight(1f).padding(start = 26.dp, top = 88.dp)) {
                series.author?.let {
                    Text(
                        it.uppercase(), color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.Bold,
                        fontSize = 10.sp, letterSpacing = 2.sp,
                    )
                }
                Text(
                    title, color = MangaColors.Text, fontFamily = anton, fontSize = 36.sp, lineHeight = 36.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                DetailChips(series, archivo)

                series.description?.let {
                    Text(
                        it, color = Color(0xFFA29C95), fontFamily = archivo, fontSize = 13.sp, lineHeight = 20.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (cta != null && ctaTarget != null) {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(MangaColors.Accent)
                                .clickable { onChapterClick(ctaTarget) }.padding(horizontal = 22.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(cta, color = Color.White, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailChips(series: Series, archivo: FontFamily) {
    Row(
        Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val (statusLabel, statusColor) = statusPresentation(series.status) ?: (null to null)
        if (statusLabel != null && statusColor != null) {
            DetailChip(archivo) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Text(statusLabel, color = Color(0xFFEEEEEE), fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        val (formatLabel, _) = formatPresentation(series.format) ?: (null to null)
        if (formatLabel != null) {
            DetailChip(archivo) { Text(formatLabel, color = Color(0xFFEEEEEE), fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
        series.startYear?.let { year ->
            DetailChip(archivo) { Text(year.toString(), color = Color(0xFFEEEEEE), fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
        series.averageScore?.let { score ->
            DetailChip(archivo) {
                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF5A524), modifier = Modifier.size(13.dp))
                Text("$score%", color = Color(0xFFEEEEEE), fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        if (series.genres.isNotEmpty()) {
            Text(
                series.genres.take(3).joinToString("   ") { it.uppercase() },
                color = MangaColors.TextMuted3, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailChip(archivo: FontFamily, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(MangaColors.ChipBg).border(1.dp, MangaColors.ChipBorder, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
private fun ChaptersHeader(
    count: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onExitSelectionMode: () -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    // A single-root Column (not two sibling layouts) so callers can drop this into a grid item
    // or the docked overlay directly, without needing their own Column to stack the divider.
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (selectionMode) {
                Text(
                    pluralStringResource(Res.plurals.selection_chapters_selected, selectedCount, selectedCount),
                    color = MangaColors.Text, fontFamily = anton, fontSize = 24.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShelfPillButton(onClick = onSelectAll) { Text(stringResource(Res.string.selection_action_all), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    ShelfPillButton(onClick = onSelectNone) { Text(stringResource(Res.string.selection_action_none), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    ShelfPillButton(onClick = onMarkRead) { Text(stringResource(Res.string.selection_action_read), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    ShelfPillButton(onClick = onMarkUnread) { Text(stringResource(Res.string.selection_action_unread), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(MangaColors.Accent)
                            .clickable(onClick = onExitSelectionMode).padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        Text(stringResource(Res.string.selection_action_done), color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.chapters_header_title), color = MangaColors.Text, fontFamily = anton, fontSize = 24.sp)
                    Text(
                        stringResource(Res.string.chapters_header_total, count),
                        color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MangaColors.Divider).padding(bottom = 16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterTile(
    chapter: ChapterCard,
    indexInList: Int,
    isNext: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    archivo: FontFamily,
) {
    val (numberPrefix, numberValue) = chapterOrdinal(chapter, indexInList)
    val numColor = if (isNext) MangaColors.Accent else if (chapter.completed) Color(0xFF5A554F) else Color(0xFF8A857F)
    val titleColor = if (isNext) Color.White else if (chapter.completed) MangaColors.TextMuted2 else Color(0xFFE6E2DC)

    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / 1.4f).clip(RoundedCornerShape(9.dp))
                .let { if (selected) it.border(2.dp, MangaColors.Accent, RoundedCornerShape(9.dp)) else it },
        ) {
            ShelfCoverImage(chapter.displayName, chapter.coverModel, null, null, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))),
            )
            // Same dim treatment as the Library shelf's covers while picking (MangaShelfGrid.kt).
            if (selectionMode && !selected) {
                Box(Modifier.matchParentSize().background(MangaColors.Bg.copy(alpha = 0.55f)))
            }
            Text(
                numberValue, color = Color.White.copy(alpha = 0.92f), fontFamily = mangaAnton(), fontSize = 26.sp, lineHeight = 26.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 7.dp),
            )
            if (isNext) {
                Text(
                    stringResource(Res.string.chapter_tile_up_next), color = MangaColors.Bg, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(MangaColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            if (selectionMode) {
                ShelfSelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(7.dp))
            } else if (chapter.completed) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.content_desc_chapter_read), tint = Color(0xFF7DE0A0), modifier = Modifier.size(13.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$numberPrefix $numberValue", color = numColor, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.6.sp)
        }
        Text(
            chapter.displayName, color = titleColor, fontFamily = archivo, fontWeight = if (isNext) FontWeight.ExtraBold else FontWeight.SemiBold,
            fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** A chapter's own number, when the filename parser found one (PLAN.md §5, §17); falling back to
 * its volume number, and finally to its plain position in the list -- some series are organized
 * by volume alone with no per-file chapter number to parse (e.g. "A Silent Voice v01"), and the
 * big callout number on a tile needs to show *something* meaningful rather than a bare "?". */
@Composable
private fun chapterOrdinal(chapter: ChapterCard, indexInList: Int): Pair<String, String> = when {
    chapter.number != null -> stringResource(Res.string.chapter_prefix_ch) to formatChapterNumber(chapter.number)
    chapter.volume != null -> stringResource(Res.string.chapter_prefix_vol) to formatChapterNumber(chapter.volume)
    else -> "#" to (indexInList + 1).toString()
}

private fun formatChapterNumber(number: Double?): String {
    if (number == null) return "?"
    return if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
}

