package com.mangaread

import android.content.Context
import com.mangaread.core.source.MangaSource

/** Android [SmbSourceFactory]: `smbj` for the connection, `EncryptedSharedPreferences`
 * (via [SmbCredentialStore]) for the password. */
class AndroidSmbSourceFactory(context: Context) : SmbSourceFactory {

    private val credentialStore = SmbCredentialStore(context)

    override fun build(host: String, share: String, username: String, password: String): MangaSource =
        SmbMangaSource(host, share, username, password)

    override fun savePassword(password: String) = credentialStore.savePassword(password)

    override fun loadPassword(): String? = credentialStore.loadPassword()
}
