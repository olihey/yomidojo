package com.oliver.heyme.mangazuki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import manga_reader.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** One level of the folder-browse breadcrumb (PLAN.md §6.4): [id] is what's actually passed to
 * [LibraryViewModel.listGoogleDriveFolders]/[LibraryViewModel.connectGoogleDrive] (a Drive file
 * id, Drive's own `root` semantics for the first entry's blank id); [name] is display-only. Kept
 * as a stack (not a single path string like [OneDriveConnectDialog]'s) because Drive locators
 * are opaque ids with no natural path rendering of their own. */
private data class GoogleDriveBreadcrumb(val id: String, val name: String)

/**
 * Google Drive connect flow (PLAN.md §6.4), mirroring [OneDriveConnectDialog]'s two-step shape:
 * a sign-in step while [syncState] isn't `SignedIn`, auto-advancing into the folder browser once
 * signed in. Deliberately reuses Drive *sync's* sign-in/[syncState] rather than a dedicated auth
 * flow -- both features share one Google account and one combined-scope sign-in (PLAN.md §10,
 * 2026-07-16 decision), so there's nothing separate to drive here.
 */
@Composable
internal fun GoogleDriveConnectDialog(
    viewModel: LibraryViewModel,
    syncState: StateFlow<SyncState>,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by syncState.collectAsState()
    // Both checks on purpose, same reasoning as OneDriveConnectDialog: the Activity-owned
    // syncState only moves on sign-in flow events, so it can still say SignedIn after a stored
    // token was cleared elsewhere -- isGoogleDriveSignedIn() (the token store) is the ground
    // truth, syncState contributes the in-flight SigningIn/Error presentation.
    if (state is SyncState.SignedIn && viewModel.isGoogleDriveSignedIn()) {
        GoogleDriveFolderPickerDialog(viewModel, onDismiss)
    } else {
        GoogleDriveSignInDialog(state, onSignIn, onDismiss)
    }
}

@Composable
private fun GoogleDriveSignInDialog(
    state: SyncState,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.googledrive_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.googledrive_sign_in_hint))
                if (state is SyncState.SigningIn) CircularProgressIndicator()
                (state as? SyncState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSignIn, enabled = state !is SyncState.SigningIn) {
                Text(stringResource(Res.string.googledrive_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** The in-app folder browser, mirroring [OneDriveFolderPickerDialog]: starts at the Drive root,
 * drill down by tapping a folder, back up via the up-row, commit the *current* level with "Use
 * this folder". Listings go through the view model's candidate source — nothing is persisted
 * until the commit succeeds. */
@Composable
private fun GoogleDriveFolderPickerDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val rootName = stringResource(Res.string.googledrive_root_name)
    var breadcrumbs by remember { mutableStateOf(listOf(GoogleDriveBreadcrumb("", rootName))) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = breadcrumbs.last()

    LaunchedEffect(current.id, reloadKey) {
        loading = true
        loadError = false
        viewModel.listGoogleDriveFolders(current.id).fold(
            onSuccess = { folders = it },
            onFailure = { loadError = true },
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text(stringResource(Res.string.googledrive_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    breadcrumbs.joinToString("/") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        loadError -> Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(Res.string.googledrive_error_list), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { reloadKey++ }) { Text(stringResource(Res.string.googledrive_picker_retry)) }
                        }
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            if (breadcrumbs.size > 1) {
                                item(key = "..") {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable(enabled = !connecting) { breadcrumbs = breadcrumbs.dropLast(1) }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                                        Text(stringResource(Res.string.onedrive_picker_up))
                                    }
                                }
                            }
                            if (folders.isEmpty()) {
                                item(key = "(empty)") {
                                    Text(
                                        stringResource(Res.string.onedrive_picker_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    )
                                }
                            }
                            items(folders, key = { it.locator }) { folder ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !connecting) { breadcrumbs = breadcrumbs + GoogleDriveBreadcrumb(folder.locator, folder.name) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
                if (connecting) CircularProgressIndicator()
                connectError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !connecting && !loading && !loadError,
                onClick = {
                    connecting = true
                    connectError = null
                    scope.launch {
                        val failure = viewModel.connectGoogleDrive(current.id, "Google Drive: ${breadcrumbs.joinToString("/") { it.name }}")
                        connecting = false
                        if (failure == null) onDismiss() else connectError = failure
                    }
                },
            ) { Text(stringResource(Res.string.onedrive_picker_use_folder)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
