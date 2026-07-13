package com.oliver.heyme.mangazuki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oliver.heyme.mangazuki.core.data.LibraryCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPickFolder: () -> Unit,
    onSeriesClick: (String) -> Unit,
    onChapterClick: (seriesId: String, chapterId: String) -> Unit,
    onSettingsClick: () -> Unit,
    titleLanguage: TitleLanguage,
    /** OneDrive sign-in state/trigger (PLAN.md §6.3) — Activity-owned, see [App]. */
    oneDriveAuthState: StateFlow<OneDriveAuthState> = MutableStateFlow(OneDriveAuthState.SignedOut),
    onOneDriveSignIn: () -> Unit = {},
) {
    // The redesigned dark full-bleed look (PLAN.md, "Manga Library Tablet" Claude Design) is
    // meant to run edge-to-edge -- otherwise the system status/nav bars sit on top of it as
    // opaque overlays instead of the transparent scrim the design assumes. Bars stay reachable
    // via a swipe (ImmersiveMode.android.kt), same behavior as the Reader.
    ImmersiveMode(enabled = true)

    val progress by viewModel.progress.collectAsState()
    val enrichProgress by viewModel.enrichProgress.collectAsState()
    val canRescan by viewModel.canRescan.collectAsState()
    val needsReGrant by viewModel.needsReGrant.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val query by viewModel.query.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val resumeChapters by viewModel.resumeChapters.collectAsState()
    val recentChapters by viewModel.recentChapters.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()

    var showAddSourceChooser by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }
    var showOneDriveDialog by remember { mutableStateOf(false) }
    val openChooser = { showAddSourceChooser = true }

    if (showAddSourceChooser) {
        AddSourceChooserDialog(
            onDismiss = { showAddSourceChooser = false },
            onPickLocalFolder = { showAddSourceChooser = false; onPickFolder() },
            onPickSmbShare = { showAddSourceChooser = false; showSmbDialog = true },
            onPickOneDrive = { showAddSourceChooser = false; showOneDriveDialog = true },
        )
    }
    if (showSmbDialog) {
        SmbConnectDialog(viewModel = viewModel, onDismiss = { showSmbDialog = false })
    }
    if (showOneDriveDialog) {
        OneDriveConnectDialog(
            viewModel = viewModel,
            oneDriveAuthState = oneDriveAuthState,
            onOneDriveSignIn = onOneDriveSignIn,
            onDismiss = { showOneDriveDialog = false },
        )
    }

    // The redesigned "Manga Shelf" look (Claude Design, imported 2026-07-06) covers both normal
    // browsing and selection mode (long-press, bulk mark read/unread) -- the latter used to fall
    // back to a plain Material grid, which made every long-press feel like a jarring screen
    // swap; selection is layered onto the same grid instead (PLAN.md).
    MangaShelfGrid(
        viewModel = viewModel,
        progress = progress,
        enrichProgress = enrichProgress,
        canRescan = canRescan,
        needsReGrant = needsReGrant,
        cards = cards,
        query = query,
        sort = sort,
        ascending = ascending,
        filter = filter,
        inProgress = inProgress,
        favorites = favorites,
        resumeChapters = resumeChapters,
        recentChapters = recentChapters,
        titleLanguage = titleLanguage,
        selectionMode = selectionMode,
        selectedIds = selectedIds,
        onAddSource = openChooser,
        onSeriesClick = { id -> if (selectionMode) viewModel.toggleSelected(id) else onSeriesClick(id) },
        onChapterClick = onChapterClick,
        onSettingsClick = onSettingsClick,
        onLongClickSeries = { id -> if (!selectionMode) viewModel.enterSelectionMode(id) },
        onSelectAll = viewModel::selectAll,
        onSelectNone = viewModel::selectNone,
        onMarkRead = { viewModel.markSelectedRead(true) },
        onMarkUnread = { viewModel.markSelectedRead(false) },
        onExitSelectionMode = viewModel::exitSelectionMode,
    )
}

// AddSourceChooserDialog lives in AddSourceDialog.kt (the "Add Source Dialog" Claude Design).

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
        title = { Text(stringResource(Res.string.smb_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, label = { Text(stringResource(Res.string.smb_field_host)) },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = share, onValueChange = { share = it }, label = { Text(stringResource(Res.string.smb_field_share)) },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(stringResource(Res.string.smb_field_username)) },
                    singleLine = true, enabled = !connecting, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text(stringResource(Res.string.smb_field_password)) },
                    singleLine = true, enabled = !connecting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rootPath, onValueChange = { rootPath = it },
                    label = { Text(stringResource(Res.string.smb_field_root_path)) },
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
            ) { Text(stringResource(Res.string.smb_action_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * AniList match status (PLAN.md §9.2): a matched series (`externalId` set) shows nothing — the
 * common case shouldn't clutter every cover — "?" means enrichment hasn't reached this series
 * yet, "✕" means it ran and found nothing good enough (Fix Metadata, §9.1, is the way out of
 * that state). Shared by [MangaShelfGrid]'s cover grid.
 */
@Composable
internal fun MetadataStatusOverlay(card: LibraryCard, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (card.externalId != null) return
    // "?" (not checked yet) is a neutral pending state, not a problem — yellow, distinct from
    // "✕" (checked, no good candidate found), which keeps the red also used for the status row's
    // Cancelled dot (StatusRow.kt) so it doesn't read as "no match" before enrichment even ran.
    val checked = card.metadataCheckedAt != null
    val symbol = if (checked) "✕" else "?"
    val color = if (checked) Color(0xFFF44336) else Color(0xFFFBC02D)
    Box(
        modifier.size(size).clip(RoundedCornerShape(50)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        // White reads fine on the red "✕"; the yellow "?" needs a dark glyph for contrast.
        val symbolColor = if (checked) Color.White else Color.Black
        Text(symbol, color = symbolColor, style = MaterialTheme.typography.labelSmall)
    }
}
