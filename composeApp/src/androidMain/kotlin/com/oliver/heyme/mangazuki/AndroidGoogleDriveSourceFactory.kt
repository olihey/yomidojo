package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthManager

/**
 * [GoogleDriveSourceFactory] implementation that builds a [GoogleDriveMangaSource]
 * using the Google Drive authentication manager.
 */
class AndroidGoogleDriveSourceFactory(
    private val authManager: GoogleAuthManager,
) : GoogleDriveSourceFactory {
    
    override fun build(): MangaSource = GoogleDriveMangaSource(authManager::accessToken)
    
    override fun isSignedIn(): Boolean = authManager.isSignedIn()
    
    override fun signOut() {
        authManager.signOut()
    }
}