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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.data.LibraryCard
import com.oliver.heyme.mangazuki.core.data.RecentChapterCard
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The masthead's headline doubles as a two-way tab switcher -- local-only UI state, not
 * persisted or known to [LibraryViewModel], since [YourPageContent] derives everything it shows
 * straight from the view model's own flows and needs nothing tab-specific remembered. Still
 * needs [rememberSaveable] rather than plain `remember`, though: nav-compose tears down and
 * rebuilds the "library" destination's whole composition on every visit (not just the first),
 * so a plain `remember` reset back to LIBRARY every time the user returned from a chapter
 * opened via "Resume"/"Fresh chapters" on this tab. */
private enum class LibraryTab { LIBRARY, YOUR_PAGE }

private val LibraryTabSaver = Saver<LibraryTab, String>(save = { it.name }, restore = { LibraryTab.valueOf(it) })

@Composable
private fun SortMode.label(): String = when (this) {
    SortMode.NAME -> stringResource(Res.string.sort_name)
    SortMode.RECENTLY_ADDED -> stringResource(Res.string.sort_recently_added)
    SortMode.RECENTLY_READ -> stringResource(Res.string.sort_recently_read)
    SortMode.RELEASE_START -> stringResource(Res.string.sort_release_start)
}

@Composable
private fun LibraryFilter.label(): String = when (this) {
    LibraryFilter.SHOW_ALL -> stringResource(Res.string.filter_show_all)
    LibraryFilter.HIDE_READ -> stringResource(Res.string.filter_hide_read)
    LibraryFilter.HIDE_MATCHED -> stringResource(Res.string.filter_hide_matched)
}

/**
 * The "Manga Library Tablet" design (Claude Design, imported 2026-07-06) applied to normal
 * library browsing, plus the "Manga Welcome Tablet" design ([YourPageContent], imported
 * 2026-07-07) behind the YOUR PAGE tab. Selection mode (long-press, bulk mark read/unread) is
 * layered onto this same screen rather than falling back to a different, plain-Material one --
 * only the masthead's right-hand actions and each cover's own checkbox change; the grid itself
 * never swaps out from under the user.
 *
 * Uses real cover art via the existing [AsyncImage]/[MangaCover] pipeline, unlike the design's
 * mocked flat-gradient placeholders -- those are reused here only for the loading/no-cover
 * fallback state.
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
    inProgress: List<LibraryCard>,
    resumeChapters: Map<String, ChapterCard>,
    recentChapters: List<RecentChapterCard>,
    titleLanguage: TitleLanguage,
    /** One-time seed for [activeTab]'s initial value (Settings' "Start screen", PLAN.md) -- not
     * meant to be observed reactively, since changing the setting mid-session shouldn't yank the
     * user off whichever tab they're already on. */
    startScreen: StartScreen = StartScreen.LIBRARY,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    /** Opens the "Local folder vs. SMB share" chooser (PLAN.md §6) -- NOT the raw SAF picker
     * directly. Both the re-grant banner and the "no source" empty state need the same choice
     * a fresh setup does, not just a folder re-pick, since the previously-configured source
     * could equally have been an SMB share. */
    onAddSource: () -> Unit,
    /** Pre-composed by the caller: toggles selection when [selectionMode] is on, navigates
     * otherwise -- this composable only needs to know the resulting boolean/set, not re-derive
     * click semantics itself. */
    onSeriesClick: (String) -> Unit,
    onChapterClick: (seriesId: String, chapterId: String) -> Unit,
    onSettingsClick: () -> Unit,
    onLongClickSeries: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onExitSelectionMode: () -> Unit,
) {
    val archivo = mangaArchivo()
    val anton = mangaAnton()
    val initialTab = if (startScreen == StartScreen.YOUR_PAGE) LibraryTab.YOUR_PAGE else LibraryTab.LIBRARY
    var activeTab by rememberSaveable(stateSaver = LibraryTabSaver) { mutableStateOf(initialTab) }

    Column(Modifier.fillMaxSize().background(MangaColors.Bg)) {
        ShelfMasthead(
            progress, enrichProgress, canRescan, onRescan = viewModel::rescan, onSettingsClick, activeTab, onTabChange = { activeTab = it },
            selectionMode, selectedIds.size, onSelectAll, onSelectNone, onMarkRead, onMarkUnread, onExitSelectionMode, archivo, anton,
        )
        if (activeTab == LibraryTab.YOUR_PAGE) {
            YourPageContent(inProgress, resumeChapters, recentChapters, titleLanguage, onSeriesClick, onChapterClick)
        } else {
            if (needsReGrant) ShelfReGrantBanner(onAddSource, archivo)
            when {
                !canRescan && !needsReGrant -> ShelfEmptyState(stringResource(Res.string.shelf_empty_no_source), archivo) {
                    Button(onClick = onAddSource, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Accent)) {
                        Text(stringResource(Res.string.shelf_add_source), fontFamily = archivo, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                cards.isEmpty() && progress == null && query.isBlank() && !needsReGrant ->
                    ShelfEmptyState(stringResource(Res.string.shelf_empty_no_series), archivo)
                else -> {
                    // Always rendered, even in selection mode -- hiding it used to shift every
                    // cover up by its height the instant a long-press entered selection mode.
                    ShelfToolbar(viewModel, query, sort, ascending, filter, archivo)
                    ShelfHeaderRow(filter, cards.size, archivo, anton)
                    ShelfGrid(cards, titleLanguage, selectionMode, selectedIds, onSeriesClick, onLongClickSeries, archivo, anton)
                }
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
    activeTab: LibraryTab,
    onTabChange: (LibraryTab) -> Unit,
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title block (two lines, ~34.sp headline) and the scan/enrich/selection labels (one
        // shorter line each) don't naturally take the same height -- swapping between them under
        // a plain `when` made this row (and everything below it, Library's grid or Your Page's
        // sections alike) visibly jump up and down every time a background scan started/finished
        // or selection mode was entered/exited. Always composing the title block here too,
        // invisibly, pins this Box to its height regardless of which branch is actually showing;
        // a Box reports the max size of all its children even when one of them is alpha-0'd out.
        Box(contentAlignment = Alignment.CenterStart) {
            MastheadTitleBlock(activeTab, onTabChange = {}, archivo, anton, modifier = Modifier.alpha(0f))
            when {
                // Selection mode wins over an in-flight scan/enrich pass, which can legitimately
                // keep running quietly in the background while the user is selecting -- there's
                // nothing useful to show for both at once, and the selection is the thing the
                // user is actively doing right now.
                selectionMode -> ShelfProgressLabel(
                    stringResource(Res.string.masthead_selected_label),
                    pluralStringResource(Res.plurals.selection_title_count, selectedCount, selectedCount),
                    archivo, anton,
                )
                progress != null -> ShelfProgressLabel(
                    stringResource(Res.string.masthead_scanning_label),
                    // A big first series can take a while to fully list/process -- show
                    // directories checked in the meantime rather than sitting on "0 series, 0
                    // chapters" with no visible movement (PLAN.md §5).
                    if (progress.seriesFound > 0) stringResource(Res.string.masthead_scan_progress, progress.seriesFound, progress.chaptersFound)
                    else stringResource(Res.string.masthead_scan_folders_checked, progress.directoriesScanned),
                    archivo, anton,
                )
                enrichProgress != null -> ShelfProgressLabel(
                    stringResource(Res.string.masthead_fetching_metadata_label),
                    stringResource(Res.string.masthead_fetch_progress, enrichProgress.done, enrichProgress.total),
                    archivo, anton,
                )
                else -> MastheadTitleBlock(activeTab, onTabChange, archivo, anton)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
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
            } else {
                if (canRescan && progress == null) {
                    ShelfIconButton(onClick = onRescan, icon = Icons.Default.Refresh, background = MangaColors.Panel, tint = MangaColors.TextDim, border = MangaColors.PanelBorder)
                }
                ShelfIconButton(onClick = onSettingsClick, icon = Icons.Default.Settings, background = MangaColors.Accent, tint = Color.White)
            }
        }
    }
}

@Composable
private fun MastheadTitleBlock(activeTab: LibraryTab, onTabChange: (LibraryTab) -> Unit, archivo: FontFamily, anton: FontFamily, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "YOMIDOJO", color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 3.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            MastheadTab(stringResource(Res.string.tab_library), active = activeTab == LibraryTab.LIBRARY, onClick = { onTabChange(LibraryTab.LIBRARY) }, anton)
            MastheadTab(stringResource(Res.string.tab_your_page), active = activeTab == LibraryTab.YOUR_PAGE, onClick = { onTabChange(LibraryTab.YOUR_PAGE) }, anton)
        }
    }
}

@Composable
private fun MastheadTab(label: String, active: Boolean, onClick: () -> Unit, anton: FontFamily) {
    Text(
        label, color = if (active) MangaColors.Text else MangaColors.TextMuted, fontFamily = anton,
        fontSize = 34.sp, lineHeight = 34.sp, modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Mirrors [MastheadTitleBlock]'s own shape (a small accent caption over a big headline-weight
 * line) rather than the single small line it used to be -- both larger and, since it now matches
 * the title block's structure, no longer the odd one out size-wise while a scan or enrichment
 * pass is running. */
@Composable
private fun ShelfProgressLabel(label: String, detail: String, archivo: FontFamily, anton: FontFamily) {
    Column {
        Text(
            label.uppercase(), color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp, letterSpacing = 3.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), color = MangaColors.Accent, strokeWidth = 3.dp)
            Text(detail, color = MangaColors.Text, fontFamily = anton, fontSize = 26.sp, lineHeight = 26.sp)
        }
    }
}

@Composable
internal fun ShelfIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, tint: Color, border: Color? = null) {
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
        Modifier.fillMaxWidth().background(MangaColors.Accent.copy(alpha = 0.16f)).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.regrant_banner_text),
            color = MangaColors.Text, fontFamily = archivo, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Button(onClick = onReconnect, colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Accent)) {
            Text(stringResource(Res.string.regrant_action), fontFamily = archivo, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun ShelfEmptyState(text: String, archivo: FontFamily, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
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
    archivo: FontFamily,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).widthIn(max = 380.dp)
                .clip(RoundedCornerShape(13.dp)).background(MangaColors.Panel).border(1.dp, MangaColors.PanelBorder, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MangaColors.TextMuted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(Res.string.search_placeholder), color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MangaColors.Text, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    cursorBrush = SolidColor(MangaColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close, contentDescription = stringResource(Res.string.content_desc_clear_search), tint = MangaColors.TextMuted,
                    modifier = Modifier.size(16.dp).clickable { viewModel.query.value = "" },
                )
            }
        }
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            ShelfPillButton(onClick = { sortOpen = true }) {
                Text(stringResource(Res.string.sort_prefix, sort.label()), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.label(), color = if (mode == sort) MangaColors.Text else MangaColors.TextDim, fontFamily = archivo,
                                fontWeight = if (mode == sort) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        onClick = { viewModel.sort.value = mode; sortOpen = false },
                        trailingIcon = { if (mode == sort) Icon(Icons.Default.Check, contentDescription = null, tint = MangaColors.Accent) },
                    )
                }
            }
        }
        ShelfPillButton(onClick = viewModel::toggleDirection) {
            Icon(
                if (ascending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (ascending) stringResource(Res.string.content_desc_ascending) else stringResource(Res.string.content_desc_descending),
                tint = MangaColors.TextDim, modifier = Modifier.size(16.dp),
            )
        }
        var filterOpen by remember { mutableStateOf(false) }
        Box {
            ShelfPillButton(onClick = { filterOpen = true }) {
                Text(filter.label(), color = MangaColors.TextDim, fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                LibraryFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label(), color = if (option == filter) MangaColors.Text else MangaColors.TextDim, fontFamily = archivo,
                                fontWeight = if (option == filter) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        onClick = { viewModel.filter.value = option; filterOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShelfPillButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MangaColors.Panel).border(1.dp, MangaColors.PanelBorder, RoundedCornerShape(12.dp))
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
        Text(filter.label(), color = MangaColors.Text, fontFamily = anton, fontSize = 22.sp)
        Text(
            pluralStringResource(Res.plurals.shelf_title_count, count, count),
            color = MangaColors.TextMuted, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun ShelfGrid(
    cards: List<LibraryCard>,
    titleLanguage: TitleLanguage,
    selectionMode: Boolean,
    selectedIds: Set<String>,
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
        gridItems(cards, key = { it.id }) { c ->
            ShelfCard(c, titleLanguage, selectionMode, c.id in selectedIds, onClick, onLongClick, archivo, anton)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    c: LibraryCard,
    titleLanguage: TitleLanguage,
    selectionMode: Boolean,
    selected: Boolean,
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
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(10.dp))
                .let { if (selected) it.border(2.dp, MangaColors.Accent, RoundedCornerShape(10.dp)) else it },
        ) {
            ShelfCoverImage(title, c.coverModel, c.id, c.externalId, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize()
                    .background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))),
            )
            // Dims unselected covers while picking, same role a checkbox-only treatment would
            // play, but readable at a glance across a whole grid rather than per-corner.
            if (selectionMode && !selected) {
                Box(Modifier.matchParentSize().background(MangaColors.Bg.copy(alpha = 0.55f)))
            }
            if (isContinuing) {
                Text(
                    stringResource(Res.string.continue_badge), color = Color.White, fontFamily = archivo, fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.TopStart)
                        .offset(x = (-30).dp, y = 10.dp)
                        .graphicsLayer(rotationZ = -45f)
                        .background(MangaColors.Accent)
                        .padding(horizontal = 34.dp, vertical = 3.dp),
                )
            }
            if (selectionMode) {
                ShelfSelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(9.dp))
            } else {
                Text(
                    "$readCount/${c.chapterCount}", color = Color.White, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(9.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            MetadataStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp, end = 6.dp), size = 18.dp)
            Text(
                title, color = Color.White, fontFamily = anton, fontSize = 16.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 11.dp, end = 30.dp, bottom = 15.dp),
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(MangaColors.Accent))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                c.author?.uppercase() ?: "", color = MangaColors.TextMuted2, fontFamily = archivo, fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("$percent%", color = MangaColors.Accent, fontFamily = archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

/** Selection-mode's stand-in for the read/total badge (same top-end corner) -- a filled accent
 * disc with a checkmark when selected, an outlined one otherwise, so the state reads at a glance
 * without needing the [selectionMode] dim treatment on the cover to also do that job. Shared with
 * [MangaDetailScreen]'s chapter tiles, which use the same visual language for the same reason. */
@Composable
internal fun ShelfSelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) MangaColors.Accent else Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, if (selected) MangaColors.Accent else Color.White.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(Res.string.content_desc_selected), tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Real cover art via the existing Coil pipeline, layered over a deterministic two-tone
 * gradient (picked from the design's own mocked palette) instead of a flat placeholder color --
 * only visible while the real cover is loading or missing, same role [CoverPlaceholder]'s
 * letter tile plays in the selection-mode fallback grid. */
@Composable
internal fun ShelfCoverImage(title: String, model: String?, seriesId: String?, externalId: String?, modifier: Modifier) {
    val (c1, c2) = remember(seriesId ?: title) { mangaGradientFor(seriesId ?: title) }
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
