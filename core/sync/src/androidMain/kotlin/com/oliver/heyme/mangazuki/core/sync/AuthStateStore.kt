package com.oliver.heyme.mangazuki.core.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistence seam for AppAuth's serialized `AuthState` JSON (access + refresh tokens) —
 * one store per identity provider, each in its own encrypted prefs file, so signing out of
 * one provider can never touch another's tokens. Never written to the plain SQLite database.
 */
interface AuthStateStore {
    fun save(authStateJson: String)
    fun load(): String?
    fun clear()
}

/**
 * The shared implementation behind every provider's store — `EncryptedSharedPreferences`,
 * AES256-GCM via the Android Keystore, single key matching the app's single-account-per-provider
 * model. Mirrors `SmbCredentialStore` (`composeApp`) exactly.
 */
open class EncryptedAuthStateStore(context: Context, fileName: String) : AuthStateStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun save(authStateJson: String) {
        prefs.edit().putString(KEY_AUTH_STATE, authStateJson).apply()
    }

    override fun load(): String? = prefs.getString(KEY_AUTH_STATE, null)

    override fun clear() {
        prefs.edit().remove(KEY_AUTH_STATE).apply()
    }

    private companion object {
        const val KEY_AUTH_STATE = "auth_state"
    }
}
