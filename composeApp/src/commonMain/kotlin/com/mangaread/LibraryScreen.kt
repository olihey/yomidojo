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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaread.core.data.LibraryCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPickFolder: () -> Unit,
    onSeriesClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    titleLanguage: TitleLanguage,
) {
    val progress by viewModel.progress.collectAsState()
    val enriching by viewModel.enriching.collectAsState()
    val canRescan by viewModel.canRescan.collectAsState()
    val needsReGrant by viewModel.needsReGrant.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val query by viewModel.query.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()

    var showAddSourceChooser by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }
    val openChooser = { showAddSourceChooser = true }

    if (showAddSourceChooser) {
        AddSourceChooserDialog(
            onDismiss = { showAddSourceChooser = false },
            onPickLocalFolder = { showAddSourceChooser = false; onPickFolder() },
            onPickSmbShare = { showAddSourceChooser = false; showSmbDialog = true },
        )
    }
    if (showSmbDialog) {
        SmbConnectDialog(viewModel = viewModel, onDismiss = { showSmbDialog = false })
    }

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
                        } else if (enriching) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Text("Fetching metadata…")
                            }
                        } else {
                            Text("Library (${cards.size})")
                        }
                    },
                    actions = {
                        TextButton(onClick = viewModel::cycleViewMode) { Text(viewMode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        if (canRescan && progress == null && !enriching) TextButton(onClick = viewModel::rescan) { Text("Re-scan") }
                        TextButton(onClick = onSettingsClick) { Text("Settings") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (needsReGrant) ReGrantBanner(openChooser)
            if (!canRescan && !needsReGrant) {
                // No source configured at all (PLAN.md §7.1) — the only way to add one now,
                // replacing the old always-visible "+" FAB with something that only shows up
                // when it's actually the next thing to do.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No library source configured yet.", Modifier.padding(bottom = 16.dp))
                        Button(onClick = openChooser) { Text("+ Add source") }
                    }
                }
            } else if (cards.isEmpty() && progress == null && query.isBlank() && !needsReGrant) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No series found in this library yet.", Modifier.padding(24.dp))
                }
            } else {
                if (!selectionMode) LibraryControls(viewModel, query, sort, ascending, filter)
                val onClick: (String) -> Unit = { id ->
                    if (selectionMode) viewModel.toggleSelected(id) else onSeriesClick(id)
                }
                val onLongClick: (String) -> Unit = { id ->
                    if (!selectionMode) viewModel.enterSelectionMode(id)
                }
                when (viewMode) {
                    ViewMode.LIST -> ListLayout(cards, selectionMode, selectedIds, onClick, onLongClick, titleLanguage)
                    ViewMode.GRID -> GridLayout(cards, selectionMode, selectedIds, onClick, onLongClick, titleLanguage)
                    ViewMode.DETAILED -> DetailedLayout(cards, selectionMode, selectedIds, onClick, onLongClick, titleLanguage)
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
private fun ReGrantBanner(onReconnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Source access was lost. Re-grant to keep your library updated.", modifier = Modifier.weight(1f))
            Button(onClick = onReconnect) { Text("Re-grant") }
        }
    }
}

/** Entry point for the "+" FAB and the re-grant banner (PLAN.md §6) — a source is either a
 * local SAF folder or an SMB share; picking either replaces the single configured root. */
@Composable
private fun AddSourceChooserDialog(onDismiss: () -> Unit, onPickLocalFolder: () -> Unit, onPickSmbShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add library source") },
        text = {
            Column {
                TextButton(onClick = onPickLocalFolder, modifier = Modifier.fillMaxWidth()) { Text("Local folder") }
                TextButton(onClick = onPickSmbShare, modifier = Modifier.fillMaxWidth()) { Text("SMB share") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** SMB connect dialog (PLAN.md §6) — mirrors [SeriesScreen.kt]'s `FixMetadataDialog`
 * convention (`AlertDialog` + `OutlinedTextField`s + `TextButton`s). Runs
 * [LibraryViewModel.connectSmb] in its own scope so a bad host/credentials shows an
 * inline error instead of silently failing or dismissing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbConnectDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text("Connect to SMB share") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text("Host") },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = share, onValueChange = { share = it }, label = { Text("Share") },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username (blank for anonymous)") },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") },
                    singleLine = true, enabled = !connecting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootPath, onValueChange = { rootPath = it },
                    label = { Text("Folder within share (optional)") },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                if (connecting) CircularProgressIndicator(Modifier.padding(top = 4.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !connecting && host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    connecting = true
                    error = null
                    scope.launch {
                        val displayName = "smb://$host/$share"
                        val failure = viewModel.connectSmb(host.trim(), share.trim(), username.trim(), password, rootPath.trim(), displayName)
                        connecting = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryControls(
    viewModel: LibraryViewModel,
    query: String,
    sort: SortMode,
    ascending: Boolean,
    filter: LibraryFilter,
) {
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.query.value = it },
            placeholder = { Text("Search") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.query.value = "" }) { Text("✕") }
                }
            },
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
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { filterOpen = true }) { Text(filter.label) }
                DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    LibraryFilter.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            viewModel.filter.value = option; filterOpen = false
                        })
                    }
                }
            }
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
    titleLanguage: TitleLanguage,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(cards, key = { it.id }) { c ->
            val title = c.displayTitle(titleLanguage)
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) }),
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${c.chapterCount} chapters · ${c.unreadCount} unread") },
                leadingContent = {
                    if (selectionMode) {
                        Checkbox(checked = c.id in selectedIds, onCheckedChange = { onClick(c.id) })
                    } else {
                        Box {
                            CoverPlaceholder(title, Modifier.size(40.dp, 56.dp), c.coverModel, c.id, c.externalId)
                            SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd), size = 16.dp)
                            MetadataStatusOverlay(c, Modifier.align(Alignment.BottomStart), size = 16.dp)
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
    titleLanguage: TitleLanguage,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(cards, key = { it.id }) { c ->
            val title = c.displayTitle(titleLanguage)
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
                        CoverPlaceholder(title, Modifier.size(64.dp, 90.dp), c.coverModel, c.id, c.externalId)
                        SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(2.dp))
                        MetadataStatusOverlay(c, Modifier.align(Alignment.BottomStart).padding(2.dp))
                    }
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    c.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("${c.chapterCount} chapters · ${c.unreadCount} unread", style = MaterialTheme.typography.bodySmall)
                    StatusRow(c.status, c.startYear, Modifier.padding(top = 2.dp))
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
    titleLanguage: TitleLanguage,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(165.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(cards, key = { it.id }) { c ->
            val title = c.displayTitle(titleLanguage)
            Column(Modifier.combinedClickable(onClick = { onClick(c.id) }, onLongClick = { onLongClick(c.id) })) {
                Box {
                    CoverPlaceholder(title, Modifier.fillMaxWidth().aspectRatio(0.7f), c.coverModel, c.id, c.externalId)
                    SeriesReadStatusOverlay(c, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    MetadataStatusOverlay(c, Modifier.align(Alignment.BottomStart).padding(4.dp))
                    if (selectionMode) {
                        Checkbox(
                            checked = c.id in selectedIds,
                            onCheckedChange = { onClick(c.id) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
 * AniList match status (PLAN.md §9.2), opposite corner from [SeriesReadStatusOverlay]: a
 * matched series (`externalId` set) shows nothing — the common case shouldn't clutter every
 * cover — "?" means enrichment hasn't reached this series yet, "✕" means it ran and found
 * nothing good enough (Fix Metadata, §9.1, is the way out of that state).
 */
@Composable
private fun MetadataStatusOverlay(card: LibraryCard, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp) {
    if (card.externalId != null) return
    val (symbol, color) = if (card.metadataCheckedAt != null) {
        "✕" to MaterialTheme.colorScheme.error
    } else {
        "?" to MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(50)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Cover = first page of the series (extracted from the folder/CBZ by the platform cover
 * fetcher), layered over a tinted letter placeholder that shows while loading or on failure.
 */
@Composable
private fun CoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    model: String? = null,
    seriesId: String? = null,
    externalId: String? = null,
) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.trim().take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        if (model != null) {
            // Stable across "just persisted the live-extracted cover" (§9.4) — only external_id
            // changing (an actual match) should make Coil treat this as a new image to load.
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
