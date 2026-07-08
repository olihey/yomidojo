package com.oliver.heyme.mangazuki.core.sync

import android.content.Context

/**
 * [AppAuthManager] pinned to the Microsoft identity platform's `consumers` endpoints (PLAN.md
 * §6.3) -- personal Microsoft accounts only, the locked product decision for the OneDrive manga
 * source; work/school accounts are a deferred item.
 *
 * Unlike [GoogleAuthManager] there is deliberately no client secret: the Azure registration is a
 * "Mobile and desktop applications" public client, which Microsoft's token endpoint accepts with
 * PKCE alone (`NoClientAuthentication` via the null secret). Scope is read-only Drive access plus
 * `offline_access`, which is what makes Microsoft return a refresh token at all.
 */
class MicrosoftAuthManager(
    context: Context,
    clientId: String,
    redirectUri: String,
    authStore: MicrosoftAuthStore,
) : AppAuthManager(
    context = context,
    clientId = clientId,
    clientSecret = null,
    redirectUri = redirectUri,
    authorizationEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
    tokenEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    scope = GRAPH_FILES_READ_SCOPE,
    authStore = authStore,
) {
    private companion object {
        const val GRAPH_FILES_READ_SCOPE = "Files.Read offline_access"
    }
}
