package com.oliverheyme.yomidojo

import com.oliverheyme.yomidojo.core.source.MangaSource
import com.oliverheyme.yomidojo.core.sync.MicrosoftAuthManager

/**
 * [OneDriveSourceFactory] backed by a shared [MicrosoftAuthManager] — the *same* instance
 * `MainActivity` drives the sign-in launcher with, so tokens saved by `handleSignInResult`
 * are immediately visible to [build]'s supplier without any re-plumbing.
 */
class AndroidOneDriveSourceFactory(private val auth: MicrosoftAuthManager) : OneDriveSourceFactory {

    override fun build(): MangaSource = OneDriveMangaSource(auth::accessToken)

    override fun isSignedIn(): Boolean = auth.isSignedIn()

    override fun signOut() = auth.signOut()
}
