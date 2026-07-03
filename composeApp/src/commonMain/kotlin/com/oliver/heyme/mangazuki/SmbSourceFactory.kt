package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.source.MangaSource

/**
 * Platform seam for building an SMB-backed [MangaSource] and storing its password
 * securely (PLAN.md §6). `LibraryViewModel` lives in `commonMain` and can't reference
 * `smbj`/`EncryptedSharedPreferences` directly (Android-only today) — this interface is
 * the only thing it depends on. Null means SMB isn't available on this platform yet.
 */
interface SmbSourceFactory {
    fun build(host: String, share: String, username: String, password: String): MangaSource
    fun savePassword(password: String)
    fun loadPassword(): String?
    /** Settings -> Reset library (PLAN.md §7.1) forgets any saved SMB credentials too. */
    fun clearPassword()
}
