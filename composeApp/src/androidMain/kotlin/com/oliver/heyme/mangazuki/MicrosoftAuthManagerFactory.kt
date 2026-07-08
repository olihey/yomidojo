package com.oliver.heyme.mangazuki

import android.content.Context
import com.oliver.heyme.mangazuki.core.sync.MicrosoftAuthManager
import com.oliver.heyme.mangazuki.core.sync.MicrosoftAuthStore

/**
 * Single place constructing [MicrosoftAuthManager] (PLAN.md §6.3) so [MainActivity] and
 * [ScanWorker] (which builds its own throwaway object graph per run, same pattern as its
 * source selection) never drift on the client id / redirect URI — the mirror of
 * [createGoogleAuthManager]. The redirect URI is a fixed constant defined once in
 * `composeApp/build.gradle.kts` and consumed both here and by the manifest's extra
 * `RedirectUriReceiverActivity` intent-filter.
 */
fun createMicrosoftAuthManager(context: Context): MicrosoftAuthManager = MicrosoftAuthManager(
    context = context,
    clientId = BuildConfig.MICROSOFT_OAUTH_CLIENT_ID,
    redirectUri = BuildConfig.MICROSOFT_OAUTH_REDIRECT_URI,
    authStore = MicrosoftAuthStore(context),
)
