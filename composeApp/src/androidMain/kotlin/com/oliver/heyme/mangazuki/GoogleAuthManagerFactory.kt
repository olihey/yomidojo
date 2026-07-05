package com.oliver.heyme.mangazuki

import android.content.Context
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthManager
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthStore

/**
 * Single place constructing [GoogleAuthManager] (PLAN.md §10, §18) so [MainActivity] and
 * [SyncWorker] (which builds its own throwaway object graph per run, same pattern as its
 * source selection) never drift on the client id / redirect URI. Both
 * [BuildConfig.GOOGLE_OAUTH_CLIENT_ID] and [BuildConfig.GOOGLE_OAUTH_REDIRECT_SCHEME] are
 * derived from the same `local.properties` value in `composeApp/build.gradle.kts`, so they and
 * the `appAuthRedirectScheme` manifest placeholder can never drift out of sync.
 */
fun createGoogleAuthManager(context: Context): GoogleAuthManager = GoogleAuthManager(
    context = context,
    clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
    clientSecret = BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET.ifBlank { null },
    redirectUri = "${BuildConfig.GOOGLE_OAUTH_REDIRECT_SCHEME}:/oauth2redirect",
    authStore = GoogleAuthStore(context),
)
