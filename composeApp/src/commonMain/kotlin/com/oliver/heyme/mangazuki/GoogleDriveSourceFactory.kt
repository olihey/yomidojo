package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.source.MangaSource

/**
 * Platform seam for building a Google Drive-backed [MangaSource] (PLAN.md §6.4) — similar to
 * [OneDriveSourceFactory]. `LibraryViewModel` lives in `commonMain` and can't reference
 * AppAuth/`EncryptedSharedPreferences` directly (Android-only today); this interface is the only
 * thing it depends on. Null means Google Drive isn't available on this platform yet.
 *
 * No `savePassword`-style methods: token persistence happens inside `GoogleAuthManager` /
 * `GoogleAuthStore` as a side effect of the sign-in flow, which is driven by the Activity
 * (an OAuth redirect needs an Intent) rather than through this seam.
 */
interface GoogleDriveSourceFactory {
    /** A source whose token supplier is baked in — no per-build credentials. */
    fun build(): MangaSource

    fun isSignedIn(): Boolean

    /** Settings -> Reset library (PLAN.md §7.1) forgets the stored Google Drive tokens too. */
    fun signOut()
}