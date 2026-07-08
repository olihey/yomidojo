package com.oliver.heyme.mangazuki.core.sync

import android.content.Context

/**
 * The one secret the OneDrive manga source needs on-device (PLAN.md §6.3) — AppAuth's serialized
 * `AuthState` JSON for the Microsoft account. Its own prefs file, so OneDrive sign-out never
 * touches Google Drive sync's tokens (and vice versa). See [EncryptedAuthStateStore] for the
 * shared storage mechanics.
 */
class MicrosoftAuthStore(context: Context) : EncryptedAuthStateStore(context, "microsoft_auth_state")
