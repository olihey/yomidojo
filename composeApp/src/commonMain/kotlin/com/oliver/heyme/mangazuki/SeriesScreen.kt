package com.oliver.heyme.mangazuki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oliver.heyme.mangazuki.core.metadata.RemoteWork
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.foundation.background
import coil3.compose.AsyncImage
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun SeriesScreen(
    viewModel: SeriesViewModel,
    onBack: () -> Unit,
    onChapterClick: (String) -> Unit,
    titleLanguage: TitleLanguage,
) {
    // Same edge-to-edge reasoning as LibraryScreen -- the redesigned dark hero/banner is meant
    // to run under the system bars, not behind an opaque one.
    ImmersiveMode(enabled = true)

    val series by viewModel.series.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val metadataSearchOpen by viewModel.metadataSearchOpen.collectAsState()

    if (metadataSearchOpen) {
        FixMetadataDialog(viewModel, titleLanguage)
    }

    // Selection mode (long-press, bulk mark read/unread) is layered onto the same "Manga Detail"
    // screen rather than falling back to a different, plain-Material one -- same reasoning as
    // the Library screen's shelf grid: only the chapters header's right-hand actions and each
    // tile's own badge change; the screen itself never swaps out from under the user.
    series?.let { s ->
        MangaDetailScreen(
            series = s,
            chapters = chapters,
            titleLanguage = titleLanguage,
            onBack = onBack,
            selectionMode = selectionMode,
            selectedIds = selectedIds,
            onChapterClick = { id -> if (selectionMode) viewModel.toggleSelected(id) else onChapterClick(id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onFixMetadata = viewModel::openMetadataSearch,
            onLongClickChapter = { id -> if (!selectionMode) viewModel.enterSelectionMode(id) },
            onSelectAll = viewModel::selectAll,
            onSelectNone = viewModel::selectNone,
            onMarkRead = { viewModel.markSelectedRead(true) },
            onMarkUnread = { viewModel.markSelectedRead(false) },
            onExitSelectionMode = viewModel::exitSelectionMode,
        )
    }
}

/** Fix Metadata (PLAN.md §9.1): an editable, title-prefilled search with a cover + title +
 * year candidate list — picking one rebinds external_id and re-enriches. The provider chips
 * (§9.3) default to the global setting but only affect this one lookup — switching here
 * never changes the app-wide default in Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixMetadataDialog(viewModel: SeriesViewModel, titleLanguage: TitleLanguage) {
    val query by viewModel.metadataSearchQuery.collectAsState()
    val results by viewModel.metadataSearchResults.collectAsState()
    val loading by viewModel.metadataSearchLoading.collectAsState()
    val provider by viewModel.metadataSearchProvider.collectAsState()

    AlertDialog(
        onDismissRequest = viewModel::dismissMetadataSearch,
        title = { Text(stringResource(Res.string.fix_metadata_dialog_title)) },
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
                        label = { Text(stringResource(Res.string.fix_metadata_field_title)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearMetadataQuery) { Text("✕") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchMetadata() }),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = viewModel::searchMetadata) { Text(stringResource(Res.string.fix_metadata_action_search)) }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        results.isEmpty() -> Text(
                            stringResource(Res.string.fix_metadata_no_matches),
                            Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> LazyColumn {
                            lazyItems(results, key = { it.externalId }) { work ->
                                MetadataCandidateRow(work, titleLanguage, onClick = { viewModel.applyMetadataMatch(work) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissMetadataSearch) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun MetadataCandidateRow(work: RemoteWork, titleLanguage: TitleLanguage, onClick: () -> Unit) {
    val title = work.displayTitle(titleLanguage)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(4.dp)).background(colorScheme.surfaceVariant)) {
            if (work.coverUrl != null) {
                AsyncImage(
                    model = work.coverUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatPill(work.format)
                if (work.format != null && work.startYear != null) Spacer(Modifier.width(6.dp))
                work.startYear?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
