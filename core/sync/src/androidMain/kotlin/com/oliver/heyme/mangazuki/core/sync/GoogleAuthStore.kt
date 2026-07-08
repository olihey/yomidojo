package com.oliver.heyme.mangazuki.core.sync

import android.content.Context

/**
 * The one secret Google Drive sync needs on-device (PLAN.md §10) — AppAuth's serialized
 * `AuthState` JSON (holds the access/refresh tokens). See [EncryptedAuthStateStore] for the
 * shared storage mechanics.
 */
class GoogleAuthStore(context: Context) : EncryptedAuthStateStore(context, "google_auth_state")
