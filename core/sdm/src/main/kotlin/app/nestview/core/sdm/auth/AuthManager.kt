package app.nestview.core.sdm.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.nestview.core.model.StreamApiException
import app.nestview.core.sdm.SdmConfig
import app.nestview.core.sdm.SdmConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import net.openid.appauth.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AuthStatus { SIGNED_OUT, SIGNED_IN, NEEDS_SIGN_IN }

/**
 * Google sign-in through the Nest Partner Connections Manager, where the user also picks which
 * cameras to share. Uses AppAuth with PKCE; tokens are refreshed on demand and stored encrypted.
 */
@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: SdmConfigRepository,
    private val tokenStore: TokenStore,
) {
    private val service by lazy { AuthorizationService(context) }
    private val mutex = Mutex()
    private var authState: AuthState = loadState()
    private var pendingManualRequest: AuthorizationRequest? = null

    private val _status = MutableStateFlow(if (authState.isAuthorized) AuthStatus.SIGNED_IN else AuthStatus.SIGNED_OUT)
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    private val config: SdmConfig get() = configRepository.config.value

    /** Intent that opens the consent page in a browser tab and returns to the app (custom-scheme redirect). */
    fun authorizationIntent(): Intent {
        val request = buildRequest(requireConfig())
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun handleAuthorizationResult(data: Intent?): Result<Unit> {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null) {
            return Result.failure(IllegalStateException(error?.errorDescription ?: error?.error ?: "Sign-in was cancelled"))
        }
        return exchange(response)
    }

    /** Manual flow for Web OAuth clients: returns the consent page address to open in a browser. */
    fun startManualAuthorization(): Uri {
        val request = buildRequest(requireConfig())
        pendingManualRequest = request
        return request.toUri()
    }

    /** Completes the manual flow from the address the browser ended on (it contains `code=`). */
    suspend fun completeManualAuthorization(redirectedUrl: String): Result<Unit> {
        val request = pendingManualRequest
            ?: return Result.failure(IllegalStateException("Open the Google page first, then paste the address."))
        val uri = Uri.parse(redirectedUrl.trim())
        uri.getQueryParameter("error")?.let { return Result.failure(IllegalStateException("Google returned: $it")) }
        val code = uri.getQueryParameter("code")
            ?: return Result.failure(IllegalStateException("That address has no authorization code in it."))
        val response = AuthorizationResponse.Builder(request)
            .setAuthorizationCode(code)
            .setState(uri.getQueryParameter("state") ?: request.state)
            .build()
        return exchange(response).onSuccess { pendingManualRequest = null }
    }

    /** A valid access token, refreshing it first when needed. */
    suspend fun freshAccessToken(): String = mutex.withLock {
        if (!authState.isAuthorized) throw StreamApiException.Unauthorized("Not signed in to Google")
        val token = suspendCancellableCoroutine { cont ->
            authState.performActionWithFreshTokens(service, clientAuthentication()) { accessToken, _, ex ->
                when {
                    accessToken != null -> cont.resume(accessToken)
                    ex != null && ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> {
                        _status.value = AuthStatus.NEEDS_SIGN_IN
                        cont.resumeWithException(StreamApiException.Unauthorized("Google sign-in expired: ${ex.errorDescription ?: ex.error}"))
                    }
                    else -> cont.resumeWithException(StreamApiException.Transient("Could not refresh sign-in: ${ex?.message}", ex))
                }
            }
        }
        persist()
        token
    }

    /** Forces a refresh on the next [freshAccessToken] (after the API rejected the token). */
    fun invalidateAccessToken() {
        authState.needsTokenRefresh = true
    }

    fun signOut() {
        authState = AuthState()
        tokenStore.write(null)
        _status.value = AuthStatus.SIGNED_OUT
    }

    private suspend fun exchange(response: AuthorizationResponse): Result<Unit> = runCatching {
        val state = AuthState(response, null)
        val tokenResponse = suspendCancellableCoroutine<TokenResponse> { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest(), clientAuthentication()) { tr, ex ->
                if (tr != null) cont.resume(tr) else cont.resumeWithException(
                    IllegalStateException(ex?.errorDescription ?: ex?.error ?: "Token exchange failed", ex),
                )
            }
        }
        state.update(tokenResponse, null)
        if (state.refreshToken == null) {
            Log.w(TAG, "No refresh token returned; you may need to sign in again in an hour")
        }
        mutex.withLock {
            authState = state
            persist()
        }
        _status.value = AuthStatus.SIGNED_IN
    }

    private fun buildRequest(config: SdmConfig): AuthorizationRequest {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(config.partnerConnectionsUrl),
            Uri.parse(TOKEN_ENDPOINT),
        )
        return AuthorizationRequest.Builder(serviceConfig, config.clientId, ResponseTypeValues.CODE, Uri.parse(config.redirectUri))
            .setScope(SDM_SCOPE)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
    }

    private fun requireConfig(): SdmConfig {
        val c = config
        if (!c.isComplete) throw StreamApiException.NotConfigured("Device Access project ID and OAuth client ID are not set")
        return c
    }

    private fun clientAuthentication(): ClientAuthentication =
        config.clientSecret?.takeIf { it.isNotBlank() }?.let { ClientSecretPost(it) } ?: NoClientAuthentication.INSTANCE

    private fun loadState(): AuthState =
        tokenStore.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() } ?: AuthState()

    private fun persist() {
        tokenStore.write(authState.jsonSerializeString())
    }

    companion object {
        private const val TAG = "AuthManager"
        const val SDM_SCOPE = "https://www.googleapis.com/auth/sdm.service"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }
}
