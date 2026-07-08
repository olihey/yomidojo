package com.oliver.heyme.mangazuki.core.sync

import android.content.Context

/**
 * [AppAuthManager] pinned to Google's fixed, well-known OAuth2 endpoints (verified live against
 * Google's own developer docs, not assumed -- no per-app discovery step needed) and the
 * Drive appdata scope (PLAN.md §10).
 *
 * [clientSecret] is required here despite PKCE (PLAN.md §18): Google's "Desktop app" client
 * type -- the only type that supports the custom-URI-scheme redirect this class uses -- rejects
 * the token exchange/refresh with "client_secret is missing" otherwise, unlike Android/iOS
 * client types which have no secret at all.
 */
class GoogleAuthManager(
    context: Context,
    clientId: String,
    clientSecret: String?,
    redirectUri: String,
    authStore: GoogleAuthStore,
) : AppAuthManager(
    context = context,
    clientId = clientId,
    clientSecret = clientSecret,
    redirectUri = redirectUri,
    authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
    tokenEndpoint = "https://oauth2.googleapis.com/token",
    scope = DRIVE_APPDATA_SCOPE,
    authStore = authStore,
) {
    private companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
