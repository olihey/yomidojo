package com.oliver.heyme.mangazuki.core.sync

import android.content.Context

/**
 * [AppAuthManager] pinned to Google's fixed, well-known OAuth2 endpoints (verified live against
 * Google's own developer docs, not assumed -- no per-app discovery step needed) and this app's
 * two Drive scopes (PLAN.md §10, §6.4), requested together in one sign-in:
 * - `drive.appdata` -- the app-private folder `GoogleDriveSyncBackend` mirrors
 *   `progress.json`/`metadata_aliases.json`/`favorites.json` into.
 * - `drive.readonly` -- read access to the user's actual Drive files, so `GoogleDriveMangaSource`
 *   can browse/read an existing manga folder there as a library source.
 *
 * One combined sign-in on purpose (2026-07-16 decision): both features are the same Google
 * account either way, and a single consent screen is simpler than asking twice. A user who
 * signed in before `drive.readonly` was added here has a token scoped to `drive.appdata` only --
 * `GoogleDriveMangaSource` calls will 403 until they sign in again, which requests (and Google's
 * consent screen shows) the current combined scope and replaces the stored token.
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
    scope = SCOPES,
    authStore = authStore,
) {
    private companion object {
        const val SCOPES =
            "https://www.googleapis.com/auth/drive.appdata https://www.googleapis.com/auth/drive.readonly"
    }
}
