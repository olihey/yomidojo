package com.oliver.heyme.mangazuki.core.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The one secret Google Drive sync needs on-device (PLAN.md §10) — AppAuth's serialized
 * `AuthState` JSON (holds the access/refresh tokens), never written to the plain SQLite
 * database. Mirrors `SmbCredentialStore` (`composeApp`) exactly: `EncryptedSharedPreferences`,
 * AES256-GCM via the Android Keystore, single key matching the app's single-account model.
 */
class GoogleAuthStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "google_auth_state",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(authStateJson: String) {
        prefs.edit().putString(KEY_AUTH_STATE, authStateJson).apply()
    }

    fun load(): String? = prefs.getString(KEY_AUTH_STATE, null)

    fun clear() {
        prefs.edit().remove(KEY_AUTH_STATE).apply()
    }

    private companion object {
        const val KEY_AUTH_STATE = "auth_state"
    }
}
