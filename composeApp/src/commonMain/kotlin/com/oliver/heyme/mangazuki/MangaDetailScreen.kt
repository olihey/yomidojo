package com.oliver.heyme.mangazuki

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.domain.Series

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
    onChapterClick: (String) -> Unit,
    onFixMetadata: () -> Unit,
    onLongClickChapter: (String) -> Unit,
) {
    val archivo = mangaArchivo()
    val anton = mangaAnton()
    val title = series.displayTitle(titleLanguage)
    val nextUnread = chapters.firstOrNull { !it.completed }
    val readCount = chapters.count { it.completed }

    Box(Modifier.fillMaxSize().background(MangaColors.Bg)) {
        // One grid for the whole screen, not a LazyVerticalGrid nested inside a scrolling
        // Column -- Compose disallows that regardless of userScrollEnabled (a vertically
        // scrollable measured with unbounded height throws). The hero + chapters-header sit in
        // a single full-width spanning item ahead of the per-chapter tiles instead.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    DetailHero(series, title, chapters, readCount, nextUnread, onChapterClick, archivo, anton)
                    ChaptersHeader(chapters.size, archivo, anton)
                }
            }
            gridItemsIndexed(chapters, key = { _, c -> c.id }) { index, c ->
                ChapterTile(c, index, isNext = c.id == nextUnread?.id, onClick = { onChapterClick(c.id) }, onLongClick = { onLongClickChapter(c.id) }, archivo)
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = Color.White, modifier = Modifier.size(17.dp))
            Text("Library", color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        ShelfIconButton(onClick = onFixMetadata, icon = Icons.Default.Build, background = Color.Black.copy(alpha = 0.4f), tint = Color.White)
    }
}

@Composable
private fun DetailHero(
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
        nextUnread == null -> "Read again"
        readCount == 0 -> "Start reading"
        else -> {
            val (prefix, value) = chapterOrdinal(nextUnread, chapters.indexOf(nextUnread))
            "Continue · $prefix $value"
        }
    }
    val ctaTarget = nextUnread?.id ?: chapters.firstOrNull()?.id

    Column {
        Box(Modifier.expandHorizontally(32.dp).fillMaxWidth().height(190.dp)) {
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

        Row(Modifier.fillMaxWidth().overlapAbove(78.dp)) {
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
                            "PROGRESS", color = MangaColors.TextMuted2, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp, letterSpacing = 1.sp,
                        )
                        Text(
                            "$readCount / ${chapters.size}", color = MangaColors.Text, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
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
private fun ChaptersHeader(count: Int, archivo: FontFamily, anton: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CHAPTERS", color = MangaColors.Text, fontFamily = anton, fontSize = 24.sp)
        Text(
            "$count " + if (count == 1) "TOTAL" else "TOTAL",
            color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MangaColors.Divider).padding(bottom = 16.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterTile(
    chapter: ChapterCard,
    indexInList: Int,
    isNext: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    archivo: FontFamily,
) {
    val (numberPrefix, numberValue) = chapterOrdinal(chapter, indexInList)
    val numColor = if (isNext) MangaColors.Accent else if (chapter.completed) Color(0xFF5A554F) else Color(0xFF8A857F)
    val titleColor = if (isNext) Color.White else if (chapter.completed) MangaColors.TextMuted2 else Color(0xFFE6E2DC)

    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.4f).clip(RoundedCornerShape(9.dp))) {
            ShelfCoverImage(chapter.displayName, chapter.coverModel, null, null, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))),
            )
            Text(
                numberValue, color = Color.White.copy(alpha = 0.92f), fontFamily = mangaAnton(), fontSize = 26.sp, lineHeight = 26.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 7.dp),
            )
            if (isNext) {
                Text(
                    "UP NEXT", color = MangaColors.Bg, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(MangaColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            if (chapter.completed) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Read", tint = Color(0xFF7DE0A0), modifier = Modifier.size(13.dp))
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
private fun chapterOrdinal(chapter: ChapterCard, indexInList: Int): Pair<String, String> = when {
    chapter.number != null -> "CH." to formatChapterNumber(chapter.number)
    chapter.volume != null -> "VOL." to formatChapterNumber(chapter.volume)
    else -> "#" to (indexInList + 1).toString()
}

private fun formatChapterNumber(number: Double?): String {
    if (number == null) return "?"
    return if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
}

/** Shifts this Row up to overlap the banner (the cover callout is meant to sit partly on top of
 * it) while reporting less height to the parent Column by the same amount -- otherwise, since
 * `Modifier.offset` shifts drawing only and doesn't shrink the reported layout size, the Column
 * would still reserve the un-shifted (taller) space below it, leaving a large dead gap before
 * [ChaptersHeader]. Same technique as [SeriesScreen]'s own `overlapAbove`, duplicated here since
 * that one is file-private. */
private fun Modifier.overlapAbove(overlap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlapPx = overlap.roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - overlapPx) {
        placeable.placeRelative(0, -overlapPx)
    }
}

/** Escapes a parent's symmetric horizontal inset (here, the hero grid item's 32dp
 * [PaddingValues] from the outer LazyVerticalGrid) so the banner can render edge-to-edge while
 * the rest of the hero content keeps its margin -- the horizontal counterpart to
 * [SeriesScreen]'s own vertical `overlapAbove`, which solves the same "Modifier.padding can't go
 * negative" problem in the other axis. */
private fun Modifier.expandHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val amountPx = amount.roundToPx()
    val expanded = Constraints(
        minWidth = (constraints.minWidth + amountPx * 2).coerceAtLeast(0),
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + amountPx * 2 else constraints.maxWidth,
        minHeight = constraints.minHeight,
        maxHeight = constraints.maxHeight,
    )
    val placeable = measurable.measure(expanded)
    layout(placeable.width - amountPx * 2, placeable.height) {
        placeable.placeRelative(-amountPx, 0)
    }
}
