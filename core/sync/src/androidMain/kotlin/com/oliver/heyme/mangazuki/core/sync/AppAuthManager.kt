package com.oliver.heyme.mangazuki.core.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume

/**
 * Wraps AppAuth's callback-based API (PLAN.md §10) behind a small suspend surface: build the
 * sign-in intent, complete it from the redirect, and fetch an always-fresh access token.
 * AppAuth handles PKCE (code verifier/challenge) and the CSRF state param automatically when
 * building the request -- neither is done by hand here.
 *
 * Provider-agnostic: endpoints, scope, and client-auth style are constructor parameters, with
 * one thin subclass per identity provider ([GoogleAuthManager], [MicrosoftAuthManager]) pinning
 * its own well-known values. The subtle parts live here exactly once -- re-saving the
 * [AuthState] after every [accessToken] call (a refresh may rotate tokens) and the
 * null-response vs. exception split in [handleSignInResult] -- so a fix never needs to be
 * applied to two forks.
 *
 * [clientId]/[clientSecret]/[redirectUri] are plain constructor parameters rather than read from
 * anywhere in this module -- this class is deliberately unaware of where they're sourced from (a
 * Gradle-injected BuildConfig field backed by `local.properties`, in this app's case).
 */
open class AppAuthManager(
    context: Context,
    private val clientId: String,
    clientSecret: String?,
    private val redirectUri: String,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    private val scope: String,
    private val authStore: AuthStateStore,
) {
    private val service = AuthorizationService(context.applicationContext)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(authorizationEndpoint),
        Uri.parse(tokenEndpoint),
    )

    private val clientAuth: ClientAuthentication =
        clientSecret?.let { ClientSecretPost(it) } ?: NoClientAuthentication.INSTANCE

    /** The intent to launch via an `ActivityResultLauncher<Intent>` to start sign-in. */
    fun signInIntent(): Intent {
        val request = AuthorizationRequest.Builder(serviceConfig, clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
            .setScope(scope)
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Call from the launcher's result callback with the returned [Intent]. Completes the
     * code-for-token exchange and persists the resulting [AuthState] on success. */
    suspend fun handleSignInResult(data: Intent): Result<Unit> {
        val response = AuthorizationResponse.fromIntent(data)
        val authException = AuthorizationException.fromIntent(data)
        if (response == null) {
            return Result.failure(authException ?: IllegalStateException("Sign-in returned no response"))
        }

        val state = AuthState(serviceConfig)
        state.update(response, authException)

        return suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest(), clientAuth) { tokenResponse, tokenException ->
                state.update(tokenResponse, tokenException)
                authStore.save(state.jsonSerializeString())
                if (tokenResponse != null) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(tokenException ?: IllegalStateException("Token exchange failed")))
                }
            }
        }
    }

    /** A valid access token, transparently refreshed via the stored [AuthState] if the cached
     * one has expired -- callers never need to think about refresh themselves. Null if never
     * signed in, or if the refresh itself fails (e.g. the user revoked access). */
    suspend fun accessToken(): String? {
        val state = authStore.load()?.let { AuthState.jsonDeserialize(it) } ?: return null
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service, clientAuth) { token, _, ex ->
                authStore.save(state.jsonSerializeString()) // a refresh may have rotated tokens
                cont.resume(if (ex != null) null else token)
            }
        }
    }

    fun isSignedIn(): Boolean = authStore.load()?.let { AuthState.jsonDeserialize(it) }?.isAuthorized == true

    fun signOut() = authStore.clear()
}
