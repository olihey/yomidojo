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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
                        TextButton(onClick = viewModel::openMetadataSearch) { Text("Fix metadata") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            series?.let { s ->
                if (!selectionMode) {
                    SeriesHeader(
                        series = s,
                        titleLanguage = titleLanguage,
                        nextUnread = nextUnread,
                        onContinueClick = onChapterClick,
                    )
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
 * content below) — title, author, status/release row, and description sit beside/under it.
 * Falls back to a plain surface-colored backdrop and a placeholder cover when unmatched.
 */
@Composable
private fun SeriesHeader(
    series: Series,
    titleLanguage: TitleLanguage,
    nextUnread: ChapterCard?,
    onContinueClick: (String) -> Unit,
) {
    val bannerHeight = 150.dp
    val coverWidth = 108.dp
    val coverHeight = 154.dp
    val overlap = 56.dp

    Column {
        // Banner + overlapping cover share one fixed-height Box (bannerHeight below the banner's
        // own bottom, plus however much of the cover pokes below that seam) — Modifier.padding
        // rejects negative values, so the overlap is expressed as absolute positioning inside a
        // correctly-sized Box instead of the more usual negative-offset/negative-padding trick.
        Box(Modifier.fillMaxWidth().height(bannerHeight + coverHeight - overlap)) {
            Box(Modifier.fillMaxWidth().height(bannerHeight).align(Alignment.TopStart)) {
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
            }
            Row(Modifier.align(Alignment.TopStart).padding(top = bannerHeight - overlap, start = 16.dp, end = 16.dp)) {
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
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 20.dp)) {
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
                    Spacer(Modifier.height(6.dp))
                    SeriesStatusRow(series.status, series.startYear)
                }
            }
        }
        series.description?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (nextUnread != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onContinueClick(nextUnread.id) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Continue: ${nextUnread.displayName}")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Release year + AniList status (§9) as a colored dot + label, e.g. "2021 · ● Releasing". */
@Composable
private fun SeriesStatusRow(status: String?, startYear: Int?) {
    val presentation = statusPresentation(status)
    if (presentation == null && startYear == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        startYear?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (presentation != null) {
                Text(
                    "   •   ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        presentation?.let { (label, color) ->
            Text("●", color = color, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** AniList `MediaStatus` -> (display label, color). Null for an unmatched series or a status
 * value AniList hasn't documented (future-proofing rather than crashing on an unknown enum). */
private fun statusPresentation(status: String?): Pair<String, Color>? = when (status) {
    "FINISHED" -> "Finished" to Color(0xFF4CAF50)
    "RELEASING" -> "Releasing" to Color(0xFF2196F3)
    "NOT_YET_RELEASED" -> "Not yet released" to Color(0xFFFF9800)
    "CANCELLED" -> "Cancelled" to Color(0xFFF44336)
    "HIATUS" -> "Hiatus" to Color(0xFFFFC107)
    else -> null
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

/** Fix Metadata (PLAN.md §9.1): an editable, title-prefilled AniList search with a
 * cover + title + year candidate list — picking one rebinds external_id and re-enriches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixMetadataDialog(viewModel: SeriesViewModel) {
    val query by viewModel.metadataSearchQuery.collectAsState()
    val results by viewModel.metadataSearchResults.collectAsState()
    val loading by viewModel.metadataSearchLoading.collectAsState()

    AlertDialog(
        onDismissRequest = viewModel::dismissMetadataSearch,
        title = { Text("Fix metadata") },
        text = {
            Column(Modifier.fillMaxWidth()) {
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
