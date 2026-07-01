package com.mangaread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaread.core.data.ChapterCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onBack: () -> Unit,
    onChapterClick: (String) -> Unit,
) {
    val series by viewModel.series.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val nextUnread = chapters.firstOrNull { !it.completed }

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
                    title = { Text(series?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!selectionMode) {
                series?.author?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                series?.description?.let {
                    Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (nextUnread != null) {
                    Button(onClick = { onChapterClick(nextUnread.id) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Continue: ${nextUnread.displayName}")
                    }
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
