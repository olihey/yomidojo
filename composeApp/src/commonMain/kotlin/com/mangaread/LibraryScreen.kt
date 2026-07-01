package com.mangaread

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaread.core.data.LibraryCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onPickFolder: () -> Unit, onSeriesClick: (String) -> Unit) {
    val progress by viewModel.progress.collectAsState()
    val canRescan by viewModel.canRescan.collectAsState()
    val needsReGrant by viewModel.needsReGrant.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val query by viewModel.query.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val unreadOnly by viewModel.unreadOnly.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onSelectAll = viewModel::selectAll,
                    onSelectNone = viewModel::selectNone,
                    onMarkRead = { viewModel.markSelectedRead(true) },
                    onMarkUnread = { viewModel.markSelectedRead(false) },
                    onDone = viewModel::exitSelectionMode,
                )
            } else {
                TopAppBar(
                    title = {
                        if (progress != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Text("Scanning… ${progress!!.seriesFound} series, ${progress!!.chaptersFound} chapters")
                            }
                        } else {
                            Text("Library (${cards.size})")
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::cycleViewMode) { Text(viewMode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        if (canRescan && progress == null) TextButton(onClick = viewModel::rescan) { Text("Re-scan") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) FloatingActionButton(onClick = onPickFolder) { Text("+", Modifier.padding(4.dp)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (needsReGrant) ReGrantBanner(onPickFolder)
            if (cards.isEmpty() && progress == null && query.isBlank() && !needsReGrant) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No series yet — tap + to pick your manga folder.", Modifier.padding(24.dp))
                }
            } else {
                if (!selectionMode) LibraryControls(viewModel, query, sort, ascending, unreadOnly)
                val onClick: (String) -> Unit = { id ->
                    if (selectionMode) viewModel.toggleSelected(id) else onSeriesClick(id)
                }
                val onLongClick: (String) -> Unit = { id ->
                    if (!selectionMode) viewModel.enterSelectionMode(id)
                }
                when (viewMode) {
                    ViewMode.LIST -> ListLayout(cards, selectionMode, selectedIds, onClick, onLongClick)
                    ViewMode.GRID -> GridLayout(cards, selectionMode, selectedIds, onClick, onLongClick)
                    ViewMode.DETAILED -> DetailedLayout(cards, selectionMode, selectedIds, onClick, onLongClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDone: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        actions = {
            TextButton(onClick = onSelectAll) { Text("All") }
            TextButton(onClick = onSelectNone) { Text("None") }
            TextButton(onClick = onMarkRead) { Text("Read") }
            TextButton(onClick = onMarkUnread) { Text("Unread") }
            TextButton(onClick = onDone) { Text("Done") }
        },
    )
}

@Composable
private fun ReGrantBanner(onPickFolder: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Folder access was lost. Re-grant to keep your library updated.", modifier = Modifier.weight(1f))
            Button(onClick = onPickFolder) { Text("Re-grant") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryControls(
    viewModel: LibraryViewModel,
    query: String,
    sort: SortMode,
    ascending: Boolean,
    unreadOnly: Boolean,
) {
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.query.value = it },
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text("Sort: ${sort.label}") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            viewModel.sort.value = mode; sortOpen = false
                        })
                    }
                }
            }
            TextButton(onClick = viewModel::toggleDirection) { Text(if (ascending) "↑ Asc" else "↓ Desc") }
            FilterChip(selected = unreadOnly, onClick = { viewModel.unreadOnly.value = !unreadOnly }, label = { Text("Hide read") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListLayout(
    cards: List<LibraryCard>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(cards, key = { it.id }) { c ->
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) }),
                headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${c.chapterCount} chapters · ${c.unreadCount} unread") },
                leadingContent = {
                    if (selectionMode) {
                        Checkbox(checked = c.id in selectedIds, onCheckedChange = { onClick(c.id) })
                    } else {
                        Box {
                            CoverPlaceholder(c.title, Modifier.size(40.dp, 56.dp), c.coverModel)
                            SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd), size = 16.dp)
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailedLayout(
    cards: List<LibraryCard>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(cards, key = { it.id }) { c ->
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) })
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(checked = c.id in selectedIds, onCheckedChange = { onClick(c.id) })
                } else {
                    Box {
                        CoverPlaceholder(c.title, Modifier.size(64.dp, 90.dp), c.coverModel)
                        SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(2.dp))
                    }
                }
                Column {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    c.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("${c.chapterCount} chapters · ${c.unreadCount} unread", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridLayout(
    cards: List<LibraryCard>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(165.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(cards, key = { it.id }) { c ->
            Column(Modifier.combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) })) {
                Box {
                    CoverPlaceholder(c.title, Modifier.fillMaxWidth().aspectRatio(0.7f), c.coverModel)
                    SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    if (selectionMode) {
                        Checkbox(
                            checked = c.id in selectedIds,
                            onCheckedChange = { onClick(c.id) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
                Text(c.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Series-level read status, mirroring the chapter-cover overlay (PLAN.md §7.2): unread → nothing;
 * some but not all chapters read → a ring filled to the percentage with the number inside;
 * fully read → a check disc.
 */
@Composable
private fun SeriesReadStatusOverlay(card: LibraryCard, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp) {
    if (card.chapterCount <= 0) return
    val readCount = card.chapterCount - card.unreadCount

    if (card.unreadCount == 0) {
        Box(
            modifier.size(size).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    if (readCount <= 0) return
    val percent = readCount * 100 / card.chapterCount

    Box(
        modifier.size(size).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxSize().padding(3.dp),
            strokeWidth = 2.dp,
            color = androidx.compose.ui.graphics.Color.White,
            trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
        )
        Text(
            "$percent",
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        )
    }
}

/**
 * Cover = first page of the series (extracted from the folder/CBZ by the platform cover
 * fetcher), layered over a tinted letter placeholder that shows while loading or on failure.
 */
@Composable
private fun CoverPlaceholder(title: String, modifier: Modifier = Modifier, model: String? = null) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.trim().take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        if (model != null) {
            AsyncImage(
                model = MangaCover(model),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
