package com.oliver.heyme.mangazuki

import android.content.Context
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthManager
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthStore

/**
 * Single place constructing [GoogleAuthManager] (PLAN.md §10) so [MainActivity] and
 * [SyncWorker] (which builds its own throwaway object graph per run, same pattern as its
 * source selection) never drift on the client id / redirect URI. The redirect scheme here
 * MUST match the `appAuthRedirectScheme` manifest placeholder (`composeApp/build.gradle.kts`).
 */
fun createGoogleAuthManager(context: Context): GoogleAuthManager = GoogleAuthManager(
    context = context,
    clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
    redirectUri = "com.oliver.heyme.mangazuki:/oauth2redirect",
    authStore = GoogleAuthStore(context),
)
