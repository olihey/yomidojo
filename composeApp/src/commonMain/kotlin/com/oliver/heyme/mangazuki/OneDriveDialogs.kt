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

/**
 * OneDrive connect flow (PLAN.md §6.3), two steps in one dialog slot: a sign-in step while
 * [oneDriveAuthState] isn't `SignedIn` (the OAuth intent itself is Activity-owned, reached via
 * [onOneDriveSignIn]), auto-advancing into the folder browser the moment sign-in lands. Follows
 * [SmbConnectDialog]'s conventions: `AlertDialog`, inline errors, dismiss locked while a
 * connect is in flight.
 */
@Composable
internal fun OneDriveConnectDialog(
    viewModel: LibraryViewModel,
    oneDriveAuthState: StateFlow<OneDriveAuthState>,
    onOneDriveSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val authState by oneDriveAuthState.collectAsState()
    // Both checks on purpose: the Activity-owned state flow only moves on sign-in flow events,
    // so it still says SignedIn after "Reset library" cleared the stored tokens mid-session --
    // the token store (via isOneDriveSignedIn) is the ground truth, while the flow contributes
    // the in-flight SigningIn/Error presentation. Without the second check, a post-reset
    // reconnect skipped straight to a folder browser whose every listing failed signed-out.
    if (authState is OneDriveAuthState.SignedIn && viewModel.isOneDriveSignedIn()) {
        OneDriveFolderPickerDialog(viewModel, onDismiss)
    } else {
        OneDriveSignInDialog(authState, onOneDriveSignIn, onDismiss)
    }
}

@Composable
private fun OneDriveSignInDialog(
    authState: OneDriveAuthState,
    onOneDriveSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.onedrive_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.onedrive_sign_in_hint))
                if (authState is OneDriveAuthState.SigningIn) CircularProgressIndicator()
                (authState as? OneDriveAuthState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOneDriveSignIn, enabled = authState !is OneDriveAuthState.SigningIn) {
                Text(stringResource(Res.string.onedrive_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** The in-app folder browser (the locked §6.3 UX): starts at the drive root, drill down by
 * tapping a folder, back up via the up-row, commit the *current* level with "Use this folder".
 * Listings go through the view model's candidate source — nothing is persisted until the
 * commit succeeds. */
@Composable
private fun OneDriveFolderPickerDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, reloadKey) {
        loading = true
        loadError = false
        viewModel.listOneDriveFolders(path).fold(
            onSuccess = { folders = it },
            onFailure = { loadError = true },
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text(stringResource(Res.string.onedrive_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "/$path",
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
                            Text(stringResource(Res.string.onedrive_error_list), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { reloadKey++ }) { Text(stringResource(Res.string.onedrive_picker_retry)) }
                        }
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            if (path.isNotEmpty()) {
                                item(key = "..") {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable(enabled = !connecting) { path = path.substringBeforeLast('/', "") }
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
                                        .clickable(enabled = !connecting) { path = folder.locator }
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
                        val failure = viewModel.connectOneDrive(path, "OneDrive: /$path")
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
