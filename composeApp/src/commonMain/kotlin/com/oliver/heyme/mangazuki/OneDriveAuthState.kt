package com.oliver.heyme.mangazuki

/**
 * Where the OneDrive sign-in stands (PLAN.md §6.3), as far as the UI needs to know — mirrors
 * [SyncState]'s role for Google Drive sync: `commonMain`-safe so `LibraryScreen`'s connect
 * dialog can observe it without depending on Android auth types. Owned by `MainActivity`,
 * which is the only place that can actually launch the OAuth intent and handle its redirect.
 */
sealed interface OneDriveAuthState {
    data object SignedOut : OneDriveAuthState
    data object SigningIn : OneDriveAuthState
    data object SignedIn : OneDriveAuthState
    data class Error(val message: String) : OneDriveAuthState
}
