package com.mangaread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaread.core.data.ChapterCard
import com.mangaread.core.domain.Series
import com.mangaread.core.metadata.RemoteWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onBack: () -> Unit,
    onChapterClick: (String) -> Unit,
    titleLanguage: TitleLanguage,
) {
    val series by viewModel.series.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val metadataSearchOpen by viewModel.metadataSearchOpen.collectAsState()
    val nextUnread = chapters.firstOrNull { !it.completed }

    if (metadataSearchOpen) {
        FixMetadataDialog(viewModel)
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        TextButton(onClick = viewModel::selectAll) { Text("All") }
                        TextButton(onClick = viewModel::selectNone) { Text("None") }
                        TextButton(onClick = { viewModel.markSelectedRead(true) }) { Text("Read") }
                        TextButton(onClick = { viewModel.markSelectedRead(false) }) { Text("Unread") }
                        TextButton(onClick = viewModel::exitSelectionMode) { Text("Done") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(series?.displayTitle(titleLanguage) ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    },
                    actions = {
                        // Top-right, above the banner — the header below is purely informational now.
                        if (nextUnread != null) {
                            TextButton(onClick = { onChapterClick(nextUnread.id) }) { Text("Continue") }
                        }
                        TextButton(onClick = viewModel::openMetadataSearch) { Text("Fix metadata") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            series?.let { s ->
                if (!selectionMode) {
                    SeriesHeader(series = s, titleLanguage = titleLanguage)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Chapters are already ordered by volume/number (§7.3); no header/grouping, so a
                // series with volume metadata looks like one continuous grid, same as one without.
                items(chapters, key = { it.id }) { chapter ->
                    ChapterCell(
                        chapter = chapter,
                        selectionMode = selectionMode,
                        selected = chapter.id in selectedIds,
                        onClick = { if (selectionMode) viewModel.toggleSelected(chapter.id) else onChapterClick(chapter.id) },
                        onLongClick = { if (!selectionMode) viewModel.enterSelectionMode(chapter.id) },
                    )
                }
            }
        }
    }
}

/**
 * Series screen header: the AniList banner as a full-bleed backdrop, fading into the screen
 * background, with the cover straddling the seam (partly over the banner, partly over the
 * content below) — release year/status sit under the cover, title/author/genres/description
 * beside it. The Continue action lives in the top app bar instead (above the banner), so this
 * header is purely informational. Falls back to a plain surface-colored backdrop and a
 * placeholder cover when unmatched.
 */
@Composable
private fun SeriesHeader(
    series: Series,
    titleLanguage: TitleLanguage,
) {
    val bannerHeight = 150.dp
    // The cover's gap above it (into the banner) and its gap to the right (before the title
    // column) are the same value, so the cover reads as evenly inset rather than off-center.
    val coverGap = 12.dp
    val coverWidth = 168.dp
    val coverHeight = 240.dp
    // How far the cover+title row is pulled up into the banner, chosen so its remaining visible
    // gap above the cover equals coverGap — also exactly where the banner gives way to the solid
    // background, which is why the title column below is nudged down by the same amount.
    val overlap = bannerHeight - coverGap

    Column {
        Box(Modifier.fillMaxWidth().height(bannerHeight)) {
            val bannerPath = series.bannerPath
            if (bannerPath != null) {
                AsyncImage(
                    model = MangaCover(bannerPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            MetadataAttributionLabel(
                series.metadataProvider,
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
        // overlapAbove shifts this row up into the banner and shrinks the space it reserves by
        // the same amount, so the next screen element starts right where the row visually ends —
        // sized to the row's real content (cover column vs. the text column, whichever is
        // taller) instead of a hand-guessed fixed height.
        Row(Modifier.fillMaxWidth().overlapAbove(overlap).padding(horizontal = 16.dp)) {
            Column {
                Box(
                    Modifier
                        .width(coverWidth)
                        .height(coverHeight)
                        .shadow(6.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val coverPath = series.coverPath
                    if (coverPath != null) {
                        AsyncImage(
                            model = MangaCover(coverPath),
                            contentDescription = series.displayTitle(titleLanguage),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                StatusRow(series.status, series.startYear)
            }
            Spacer(Modifier.width(coverGap))
            Column(Modifier.weight(1f).padding(top = overlap)) {
                Text(
                    series.displayTitle(titleLanguage),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                series.author?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (series.genres.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        series.genres.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                series.description?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Shifts this layout up by [overlap] and reports [overlap] less height to its parent, so a
 * sibling placed after it starts exactly where the shifted content visually ends — the dynamic
 * equivalent of a negative bottom margin. `Modifier.padding` throws `IllegalArgumentException`
 * on a negative value at runtime, so this goes through a custom layout instead.
 */
private fun Modifier.overlapAbove(overlap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlapPx = overlap.roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - overlapPx) {
        placeable.placeRelative(0, -overlapPx)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterCell(
    chapter: ChapterCard,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            ChapterCoverPlaceholder(chapter.displayName, Modifier.fillMaxSize(), chapter.coverModel)
            ReadStatusOverlay(chapter, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Text(
            chapter.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChapterCoverPlaceholder(title: String, modifier: Modifier, model: String?) {
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

/**
 * Unread → nothing; in progress → a ring filled to the percentage with the number inside;
 * finished → check disc (PLAN.md §7.2), as a corner overlay on the chapter cover.
 */
@Composable
private fun ReadStatusOverlay(chapter: ChapterCard, modifier: Modifier = Modifier) {
    if (chapter.completed) {
        Box(
            modifier
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    val percent = chapter.pageCount?.takeIf { it > 0 }?.let { count ->
        (((chapter.lastPageIndex + 1).coerceAtMost(count)) * 100 / count)
    }
    if (percent == null || chapter.lastPageIndex <= 0) return

    Box(
        modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)),
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

/** Fix Metadata (PLAN.md §9.1): an editable, title-prefilled search with a cover + title +
 * year candidate list — picking one rebinds external_id and re-enriches. The provider chips
 * (§9.3) default to the global setting but only affect this one lookup — switching here
 * never changes the app-wide default in Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixMetadataDialog(viewModel: SeriesViewModel) {
    val query by viewModel.metadataSearchQuery.collectAsState()
    val results by viewModel.metadataSearchResults.collectAsState()
    val loading by viewModel.metadataSearchLoading.collectAsState()
    val provider by viewModel.metadataSearchProvider.collectAsState()

    AlertDialog(
        onDismissRequest = viewModel::dismissMetadataSearch,
        title = { Text("Fix metadata") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataProviderChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = choice == provider,
                            onClick = { viewModel.setMetadataSearchProvider(choice) },
                            label = { Text(choice.label()) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::updateMetadataQuery,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Title") },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearMetadataQuery) { Text("✕") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchMetadata() }),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = viewModel::searchMetadata) { Text("Search") }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        results.isEmpty() -> Text(
                            "No matches",
                            Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> LazyColumn {
                            lazyItems(results, key = { it.externalId }) { work ->
                                MetadataCandidateRow(work, onClick = { viewModel.applyMetadataMatch(work) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissMetadataSearch) { Text("Cancel") }
        },
    )
}

@Composable
private fun MetadataCandidateRow(work: RemoteWork, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (work.coverUrl != null) {
                AsyncImage(
                    model = work.coverUrl,
                    contentDescription = work.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(work.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            work.startYear?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
