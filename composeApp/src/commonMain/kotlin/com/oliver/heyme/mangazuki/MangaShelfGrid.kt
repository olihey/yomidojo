package com.oliver.heyme.mangazuki

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.oliver.heyme.mangazuki.core.data.LibraryCard
import manga_reader.composeapp.generated.resources.Res
import manga_reader.composeapp.generated.resources.anton_regular
import manga_reader.composeapp.generated.resources.archivo_black
import manga_reader.composeapp.generated.resources.archivo_bold
import manga_reader.composeapp.generated.resources.archivo_extrabold
import manga_reader.composeapp.generated.resources.archivo_medium
import manga_reader.composeapp.generated.resources.archivo_regular
import manga_reader.composeapp.generated.resources.archivo_semibold
import org.jetbrains.compose.resources.Font

// Palette lifted directly from the "Manga Library Tablet" Claude Design project (imported
// 2026-07-06) -- a dark, pulp/comic-poster look: near-black background, a red-orange accent,
// bold condensed display type for titles.
private val ShelfBg = Color(0xFF0E0D0C)
private val ShelfPanel = Color(0xFF1A1817)
private val ShelfPanelBorder = Color(0xFF2A2725)
private val ShelfAccent = Color(0xFFEF4023)
private val ShelfText = Color.White
private val ShelfTextDim = Color(0xFFCFCAC4)
private val ShelfTextMuted = Color(0xFF6B6763)
private val ShelfTextMuted2 = Color(0xFF7D7873)

@Composable
private fun shelfArchivo(): FontFamily = FontFamily(
    Font(Res.font.archivo_regular, FontWeight.Normal),
    Font(Res.font.archivo_medium, FontWeight.Medium),
    Font(Res.font.archivo_semibold, FontWeight.SemiBold),
    Font(Res.font.archivo_bold, FontWeight.Bold),
    Font(Res.font.archivo_extrabold, FontWeight.ExtraBold),
    Font(Res.font.archivo_black, FontWeight.Black),
)

@Composable
private fun shelfAnton(): FontFamily = FontFamily(Font(Res.font.anton_regular, FontWeight.Normal))

/**
 * The "Manga Library Tablet" design (Claude Design, imported 2026-07-06) applied to the
 * library's GRID view only. List/Detailed keep the plain Material look unchanged -- the design
 * has no equivalent for those -- and entering selection mode (long-press, bulk mark read/unread)
 * falls back to the old Material grid too, since that's a secondary, infrequent tool the design
 * doesn't cover either. `LibraryScreen` renders this instead of its own `Scaffold` whenever
 * `viewMode == GRID && !selectionMode`.
 *
 * Uses real cover art via the existing [AsyncImage]/[MangaCover] pipeline, unlike the design's
 * mocked flat-gradient placeholders -- those are reused here only for the loading/no-cover
 * fallback state, the same role [CoverPlaceholder]'s letter tile already played.
 */
@Composable
fun MangaShelfGrid(
    viewModel: LibraryViewModel,
    progress: ScanProgress?,
    enrichProgress: EnrichProgress?,
    canRescan: Boolean,
    needsReGrant: Boolean,
    cards: List<LibraryCard>,
    query: String,
    sort: SortMode,
    ascending: Boolean,
    filter: LibraryFilter,
    viewMode: ViewMode,
    titleLanguage: TitleLanguage,
    /** Opens the "Local folder vs. SMB share" chooser (PLAN.md §6) -- NOT the raw SAF picker
     * directly. Both the re-grant banner and the "no source" empty state need the same choice
     * a fresh setup does, not just a folder re-pick, since the previously-configured source
     * could equally have been an SMB share. */
    onAddSource: () -> Unit,
    onSeriesClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onLongClickSeries: (String) -> Unit,
) {
    val archivo = shelfArchivo()
    val anton = shelfAnton()

    Column(Modifier.fillMaxSize().background(ShelfBg)) {
        ShelfMasthead(progress, enrichProgress, canRescan, onRescan = viewModel::rescan, onSettingsClick, archivo, anton)
        if (needsReGrant) ShelfReGrantBanner(onAddSource, archivo)
        when {
            !canRescan && !needsReGrant -> ShelfEmptyState("No library source configured yet.", archivo) {
                Button(onClick = onAddSource, colors = ButtonDefaults.buttonColors(containerColor = ShelfAccent)) {
                    Text("+ Add source", fontFamily = archivo, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            cards.isEmpty() && progress == null && query.isBlank() && !needsReGrant ->
                ShelfEmptyState("No series found in this library yet.", archivo)
            else -> {
                ShelfToolbar(viewModel, query, sort, ascending, filter, viewMode, archivo)
                ShelfHeaderRow(filter, cards.size, archivo, anton)
                ShelfGrid(cards, titleLanguage, onSeriesClick, onLongClickSeries, archivo, anton)
            }
        }
    }
}

@Composable
private fun ShelfMasthead(
    progress: ScanProgress?,
    enrichProgress: EnrichProgress?,
    canRescan: Boolean,
    onRescan: () -> Unit,
    onSettingsClick: () -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            progress != null -> ShelfProgressLabel("Scanning… ${progress.seriesFound} series, ${progress.chaptersFound} chapters", archivo)
            enrichProgress != null -> ShelfProgressLabel("Fetching metadata… ${enrichProgress.done} / ${enrichProgress.total}", archivo)
            else -> Column {
                Text(
                    "YOUR SHELF", color = ShelfAccent, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp, letterSpacing = 3.sp,
                )
                Text("LIBRARY", color = ShelfText, fontFamily = anton, fontSize = 34.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canRescan && progress == null) {
                ShelfIconButton(onClick = onRescan, icon = Icons.Default.Refresh, background = ShelfPanel, tint = ShelfTextDim, border = ShelfPanelBorder)
            }
            ShelfIconButton(onClick = onSettingsClick, icon = Icons.Default.Settings, background = ShelfAccent, tint = Color.White)
        }
    }
}

@Composable
private fun ShelfProgressLabel(text: String, archivo: FontFamily) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), color = ShelfAccent, strokeWidth = 2.dp)
        Text(text, color = ShelfText, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun ShelfIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, tint: Color, border: Color? = null) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(background)
            .let { if (border != null) it.border(1.dp, border, CircleShape) else it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ShelfReGrantBanner(onReconnect: () -> Unit, archivo: FontFamily) {
    Row(
        Modifier.fillMaxWidth().background(ShelfAccent.copy(alpha = 0.16f)).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Source access was lost. Re-grant to keep your library updated.",
            color = ShelfText, fontFamily = archivo, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Button(onClick = onReconnect, colors = ButtonDefaults.buttonColors(containerColor = ShelfAccent)) {
            Text("Re-grant", fontFamily = archivo, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun ShelfEmptyState(text: String, archivo: FontFamily, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, color = ShelfTextMuted, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp),
            )
            action?.invoke()
        }
    }
}

@Composable
private fun ShelfToolbar(
    viewModel: LibraryViewModel,
    query: String,
    sort: SortMode,
    ascending: Boolean,
    filter: LibraryFilter,
    viewMode: ViewMode,
    archivo: FontFamily,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).widthIn(max = 380.dp)
                .clip(RoundedCornerShape(13.dp)).background(ShelfPanel).border(1.dp, ShelfPanelBorder, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = ShelfTextMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search titles, authors…", color = ShelfTextMuted, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = ShelfText, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    cursorBrush = SolidColor(ShelfAccent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close, contentDescription = "Clear search", tint = ShelfTextMuted,
                    modifier = Modifier.size(16.dp).clickable { viewModel.query.value = "" },
                )
            }
        }
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            ShelfPillButton(onClick = { sortOpen = true }, archivo = archivo) {
                Text("Sort · ${sort.label}", color = ShelfTextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.label, color = if (mode == sort) ShelfText else ShelfTextDim, fontFamily = archivo,
                                fontWeight = if (mode == sort) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        onClick = { viewModel.sort.value = mode; sortOpen = false },
                        trailingIcon = { if (mode == sort) Icon(Icons.Default.Check, contentDescription = null, tint = ShelfAccent) },
                    )
                }
            }
        }
        ShelfPillButton(onClick = viewModel::toggleDirection, archivo = archivo) {
            Icon(
                if (ascending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (ascending) "Ascending" else "Descending", tint = ShelfTextDim, modifier = Modifier.size(16.dp),
            )
        }
        var filterOpen by remember { mutableStateOf(false) }
        Box {
            ShelfPillButton(onClick = { filterOpen = true }, archivo = archivo) {
                Text(filter.label, color = ShelfTextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                LibraryFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label, color = if (option == filter) ShelfText else ShelfTextDim, fontFamily = archivo,
                                fontWeight = if (option == filter) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        onClick = { viewModel.filter.value = option; filterOpen = false },
                    )
                }
            }
        }
        ShelfPillButton(onClick = viewModel::cycleViewMode, archivo = archivo) {
            Text(
                viewMode.name.lowercase().replaceFirstChar { it.uppercase() },
                color = ShelfTextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ShelfPillButton(onClick: () -> Unit, archivo: FontFamily, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(ShelfPanel).border(1.dp, ShelfPanelBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ShelfHeaderRow(filter: LibraryFilter, count: Int, archivo: FontFamily, anton: FontFamily) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 4.dp, top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(filter.label, color = ShelfText, fontFamily = anton, fontSize = 22.sp)
        Text(
            "$count " + if (count == 1) "TITLE" else "TITLES",
            color = ShelfTextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun ShelfGrid(
    cards: List<LibraryCard>,
    titleLanguage: TitleLanguage,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        gridItems(cards, key = { it.id }) { c -> ShelfCard(c, titleLanguage, onClick, onLongClick, archivo, anton) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    c: LibraryCard,
    titleLanguage: TitleLanguage,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    archivo: FontFamily,
    anton: FontFamily,
) {
    val title = c.displayTitle(titleLanguage)
    val readCount = c.chapterCount - c.unreadCount
    val percent = if (c.chapterCount > 0) readCount * 100 / c.chapterCount else 0
    val isContinuing = c.unreadCount in 1 until c.chapterCount

    Column(Modifier.combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))) {
            ShelfCoverImage(title, c.coverModel, c.id, c.externalId, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))),
            )
            if (isContinuing) {
                Text(
                    "CONTINUE", color = Color.White, fontFamily = archivo, fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.TopStart)
                        .offset(x = (-30).dp, y = 10.dp)
                        .graphicsLayer(rotationZ = -45f)
                        .background(ShelfAccent)
                        .padding(horizontal = 34.dp, vertical = 3.dp),
                )
            }
            Text(
                "$readCount/${c.chapterCount}", color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(9.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            MetadataStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp, end = 6.dp), size = 18.dp)
            Text(
                title, color = Color.White, fontFamily = anton, fontSize = 16.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 11.dp, end = 30.dp, bottom = 15.dp),
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(ShelfAccent))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                c.author?.uppercase() ?: "", color = ShelfTextMuted2, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("$percent%", color = ShelfAccent, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

/** Real cover art via the existing Coil pipeline, layered over a deterministic two-tone
 * gradient (picked from the design's own mocked palette) instead of a flat placeholder color --
 * only visible while the real cover is loading or missing, same role [CoverPlaceholder]'s
 * letter tile plays for the other view modes. */
@Composable
private fun ShelfCoverImage(title: String, model: String?, seriesId: String?, externalId: String?, modifier: Modifier) {
    val (c1, c2) = remember(seriesId ?: title) { shelfGradientFor(seriesId ?: title) }
    Box(modifier.background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
        if (model == null) {
            Text(title.trim().take(1).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 32.sp, fontWeight = FontWeight.Black)
        } else {
            val cacheKey = if (seriesId != null) "$seriesId:${externalId ?: ""}" else model
            AsyncImage(
                model = MangaCover(model, seriesId, cacheKey),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private val ShelfGradientPalette = listOf(
    Color(0xFF2E4B4A) to Color(0xFF16302F),
    Color(0xFF3A2E52) to Color(0xFF221A33),
    Color(0xFF2B2F3A) to Color(0xFF181B23),
    Color(0xFF4A3A22) to Color(0xFF2C2113),
    Color(0xFF6B2F2A) to Color(0xFF411916),
    Color(0xFF23303B) to Color(0xFF141D24),
    Color(0xFF33323A) to Color(0xFF1E1D23),
    Color(0xFF4A2733) to Color(0xFF2B141C),
)

private fun shelfGradientFor(key: String): Pair<Color, Color> =
    ShelfGradientPalette[(key.hashCode() and 0x7FFFFFFF) % ShelfGradientPalette.size]
