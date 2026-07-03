package com.oliver.heyme.mangazuki

/** Google Drive sign-in status for cross-device sync (PLAN.md §10) — commonMain-safe (the
 * actual `GoogleAuthManager`/AppAuth machinery is Android-only, core:sync's androidMain) so
 * [AppGraph]/[SettingsScreen] can observe it without depending on platform-specific auth types,
 * the same way [AppGraph] never holds the SAF-specific folder-picker launcher directly. */
sealed interface SyncState {
    data object SignedOut : SyncState
    data object SigningIn : SyncState
    data object SignedIn : SyncState
    data class Error(val message: String) : SyncState
}
