package com.oliver.heyme.mangazuki

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The one secret an SMB source needs that never goes into the plain SQLite `source` table
 * (PLAN.md §6) — everything else (host/share/username/rootPath) lives in [SmbConfig]'s blob.
 * Backed by `EncryptedSharedPreferences` (AES256-GCM, key material in the Android Keystore).
 * Single key, matching the app's single-configured-source model.
 */
class SmbCredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "smb_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun loadPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun clear() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val KEY_PASSWORD = "password"
    }
}
