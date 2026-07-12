package com.oliver.heyme.mangazuki

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.data.LibraryCard
import com.oliver.heyme.mangazuki.core.data.RecentChapterCard
import com.oliver.heyme.mangazuki.core.domain.formatShortDate
import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shown in [MangaShelfGrid] under the LIBRARY/YOUR PAGE tab switcher when YOUR_PAGE is active
 * -- a dashboard built from the "Manga Welcome Tablet" design (Claude Design, imported
 * 2026-07-07), populated from real read progress and recently-added chapters rather than the
 * design's own mocked data. Every section's list is small and fixed-size (a handful of series/
 * chapters), so plain chunked [Row]s stand in for the design's CSS grid instead of a
 * [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] -- no virtualization needed, and it
 * sidesteps nesting a lazy grid inside a scrolling container (disallowed, see [MangaDetailScreen]'s
 * own note on the same issue).
 */
@Composable
fun YourPageContent(
    inProgress: List<LibraryCard>,
    favorites: List<LibraryCard>,
    resumeChapters: Map<String, ChapterCard>,
    recentChapters: List<RecentChapterCard>,
    titleLanguage: TitleLanguage,
    onSeriesClick: (String) -> Unit,
    onChapterClick: (seriesId: String, chapterId: String) -> Unit,
    /** Owned by [MangaShelfGrid] so the scroll position survives switching to the Library tab
     * and back (this whole subtree leaves composition on a tab switch). */
    listState: LazyListState = rememberLazyListState(),
) {
    val archivo = mangaArchivo()
    val anton = mangaAnton()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Portrait keeps the card's phone-era width fixed (2 columns) and grows downward with
        // more rows instead -- there's width to spare in landscape, so that direction grows
        // sideways with more columns per row instead of stacking further rows.
        val isLandscape = maxWidth > maxHeight
        val jumpBackInColumns = if (isLandscape) {
            when {
                maxWidth >= 1400.dp -> 4
                maxWidth >= 900.dp -> 3
                else -> 2
            }
        } else {
            2
        }
        val jumpBackInRows = if (isLandscape) 1 else 2
        val jumpBackIn = inProgress.take(jumpBackInColumns * jumpBackInRows)
        // Landscape is always a single row, so size it to the real card count rather than the
        // column cap -- otherwise a sparse in-progress list would leave half the row as blank
        // ChunkedGrid filler next to a couple of narrow cards. Portrait's multiple rows keep the
        // fixed column count instead (like the shelf/fresh grids below), so card width stays
        // constant across rows even when the last one is ragged.
        val jumpBackInGridColumns = if (isLandscape) jumpBackIn.size else jumpBackInColumns
        // Shelf/fresh covers keep the same physical size in both orientations -- landscape fits
        // more of them per row instead of stretching each cover wider. minOf(width, height) is
        // this device's portrait width regardless of current orientation (rotating just swaps
        // the two), so it doubles as "the width portrait's fixed 6-column row would have" without
        // needing a second BoxWithConstraints pass after actually rotating.
        val coverGridSpacing = 16.dp
        val sectionHorizontalPadding = 48.dp // YourPageSection's 24.dp padding on each side
        val portraitColumns = 6
        val portraitContentWidth = minOf(maxWidth, maxHeight) - sectionHorizontalPadding
        val coverWidth = (portraitContentWidth - coverGridSpacing * (portraitColumns - 1)) / portraitColumns
        val coverGridColumns = if (isLandscape) {
            val landscapeContentWidth = maxWidth - sectionHorizontalPadding
            (((landscapeContentWidth + coverGridSpacing).value) / (coverWidth + coverGridSpacing).value)
                .toInt().coerceAtLeast(portraitColumns)
        } else {
            portraitColumns
        }
        // Portrait has the vertical room to spare (unlike Jump Back In's taller cards), so these
        // two sections show a second row there -- landscape stays a single row since it already
        // grows sideways via coverGridColumns instead.
        val coverGridRows = if (isLandscape) 1 else 2
        val coverGridCount = coverGridColumns * coverGridRows
        // Drop the ones already shown above -- "on your shelf" is the rest of what's in progress,
        // not a second copy of "jump back in".
        val shelf = inProgress.drop(jumpBackIn.size).take(coverGridCount)
        val hearted = favorites.take(coverGridCount)
        val fresh = recentChapters.take(coverGridCount)
        val newSince = remember { nowEpochMillis() - 24 * 60 * 60 * 1000L }

        if (jumpBackIn.isEmpty() && fresh.isEmpty() && hearted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.your_page_empty_state),
                    color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(48.dp),
                )
            }
            return@BoxWithConstraints
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
            if (jumpBackIn.isNotEmpty()) {
                item {
                    YourPageSection(stringResource(Res.string.your_page_jump_back_in_title), stringResource(Res.string.your_page_jump_back_in_subtitle), archivo, anton) {
                        ChunkedGrid(jumpBackIn, columns = jumpBackInGridColumns, spacing = 18.dp) { card ->
                            val chapter = resumeChapters[card.id]
                            if (chapter != null) {
                                JumpBackInCard(card, chapter, titleLanguage, onClick = { onChapterClick(card.id, chapter.id) }, archivo, anton)
                            }
                        }
                    }
                }
            }
            if (shelf.isNotEmpty()) {
                item {
                    YourPageSection(stringResource(Res.string.your_page_shelf_title), stringResource(Res.string.your_page_shelf_subtitle), archivo, anton) {
                        ChunkedGrid(shelf, columns = coverGridColumns, spacing = coverGridSpacing) { card ->
                            ShelfMiniCard(card, titleLanguage, onClick = { onSeriesClick(card.id) }, archivo, anton)
                        }
                    }
                }
            }
            if (hearted.isNotEmpty()) {
                item {
                    YourPageSection(stringResource(Res.string.your_page_favorites_title), stringResource(Res.string.your_page_favorites_subtitle), archivo, anton) {
                        ChunkedGrid(hearted, columns = coverGridColumns, spacing = coverGridSpacing) { card ->
                            ShelfMiniCard(card, titleLanguage, onClick = { onSeriesClick(card.id) }, archivo, anton)
                        }
                    }
                }
            }
            if (fresh.isNotEmpty()) {
                item {
                    YourPageSection(stringResource(Res.string.your_page_fresh_title), stringResource(Res.string.your_page_fresh_subtitle), archivo, anton) {
                        ChunkedGrid(fresh, columns = coverGridColumns, spacing = coverGridSpacing) { chapter ->
                            FreshChapterCard(
                                chapter, titleLanguage, isNew = chapter.dateAdded >= newSince,
                                onClick = { onChapterClick(chapter.seriesId, chapter.chapterId) }, archivo, anton,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YourPageSection(title: String, subtitle: String, archivo: FontFamily, anton: FontFamily, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 22.dp)) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = MangaColors.Text, fontFamily = anton, fontSize = 24.sp)
            Text(
                subtitle.uppercase(), color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, letterSpacing = 1.4.sp,
            )
        }
        content()
    }
}

/** Lays [items] out in fixed-size rows of [columns], padding a ragged last row with blank
 * [Spacer]s so its items don't stretch wider than the ones above -- the "no virtualization
 * needed" alternative to a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] described on
 * [YourPageContent]. */
@Composable
private fun <T> ChunkedGrid(items: List<T>, columns: Int, spacing: Dp, itemContent: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { item -> Box(Modifier.weight(1f)) { itemContent(item) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "Jump back in" card: cover + author/title/next-chapter + progress + a Resume button that
 * jumps straight into the reader, skipping the series screen. */
@Composable
private fun JumpBackInCard(
    card: LibraryCard,
    chapter: ChapterCard,
    titleLanguage: TitleLanguage,
    onClick: () -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    val title = card.displayTitle(titleLanguage)
    val readCount = card.chapterCount - card.unreadCount
    val percent = if (card.chapterCount > 0) readCount * 100 / card.chapterCount else 0
    val (prefix, value) = chapterOrdinalFor(chapter.number, chapter.volume)

    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(MangaColors.Panel)
            .clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(100.dp).aspectRatio(0.75f).clip(RoundedCornerShape(9.dp))) {
            ShelfCoverImage(title, card.coverModel, card.id, card.externalId, Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f)) {
            card.author?.let {
                Text(
                    it.uppercase(), color = MangaColors.TextMuted2, fontFamily = archivo, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                title, color = MangaColors.Text, fontFamily = anton, fontSize = 21.sp, lineHeight = 21.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            if (prefix.isNotEmpty()) {
                Text(
                    stringResource(Res.string.chapter_ordinal_with_name, prefix, value, chapter.displayName), color = MangaColors.TextDim, fontFamily = archivo,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(Res.string.your_page_read_total, readCount, card.chapterCount), color = MangaColors.TextMuted, fontFamily = archivo,
                    fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.sp,
                )
                Text("$percent%", color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 5.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF221F1C)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(MangaColors.Accent))
            }
            Row(
                Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(MangaColors.Accent)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.your_page_resume), color = Color.White, fontFamily = archivo, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }
    }
}

/** "On your shelf" tile: just a cover, a read/total badge, and a progress sliver -- lighter than
 * [JumpBackInCard] since there's no specific chapter to resume shown here, only a way back into
 * the series screen. */
@Composable
private fun ShelfMiniCard(card: LibraryCard, titleLanguage: TitleLanguage, onClick: () -> Unit, archivo: FontFamily, anton: FontFamily) {
    val title = card.displayTitle(titleLanguage)
    val readCount = card.chapterCount - card.unreadCount
    val percent = if (card.chapterCount > 0) readCount * 100 / card.chapterCount else 0

    Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        ShelfCoverImage(title, card.coverModel, card.id, card.externalId, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize()
                .background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))),
        )
        Text(
            "$readCount/${card.chapterCount}", color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Text(
            title, color = Color.White, fontFamily = anton, fontSize = 14.sp, lineHeight = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 12.dp),
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(MangaColors.Accent))
        }
    }
}

/** "Fresh chapters" tile: the chapter's own cover, a big chapter number, an optional "NEW" badge,
 * then the series name/date and the chapter's own display name below. */
@Composable
private fun FreshChapterCard(
    chapter: RecentChapterCard,
    titleLanguage: TitleLanguage,
    isNew: Boolean,
    onClick: () -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    val (prefix, value) = chapterOrdinalFor(chapter.number, chapter.volume)

    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))) {
            ShelfCoverImage(chapter.displayTitle(titleLanguage), chapter.coverModel, null, null, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))),
            )
            if (isNew) {
                Text(
                    stringResource(Res.string.your_page_new_badge), color = MangaColors.Bg, fontFamily = archivo, fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.sp, letterSpacing = 1.4.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(MangaColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 9.dp)) {
                if (value.isNotEmpty()) {
                    Text(value, color = Color.White, fontFamily = anton, fontSize = 24.sp, lineHeight = 24.sp)
                    Text(
                        prefix, color = Color.White.copy(alpha = 0.7f), fontFamily = archivo, fontWeight = FontWeight.Bold,
                        fontSize = 8.sp, letterSpacing = 1.2.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                chapter.displayTitle(titleLanguage), color = MangaColors.Text, fontFamily = anton, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                formatShortDate(chapter.dateAdded), color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold,
                fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            chapter.displayName, color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** "CH." + a number, or "VOL." + a volume, or blank when a chapter has neither (PLAN.md §5, §17)
 * -- same cascade as [MangaDetailScreen]'s `chapterOrdinal`, minus its list-position fallback
 * (there's no "index in list" here, just a single chapter pulled out of context). */
@Composable
private fun chapterOrdinalFor(number: Double?, volume: Double?): Pair<String, String> = when {
    number != null -> stringResource(Res.string.chapter_prefix_ch) to formatOrdinalNumber(number)
    volume != null -> stringResource(Res.string.chapter_prefix_vol) to formatOrdinalNumber(volume)
    else -> "" to ""
}

private fun formatOrdinalNumber(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
