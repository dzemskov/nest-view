package app.nestview.core.sdm

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Google Device Access project settings. */
data class SdmConfig(
    val projectId: String,
    val clientId: String,
    val clientSecret: String?,
    val redirectUri: String,
) {
    val isComplete: Boolean
        get() = projectId.isNotBlank() && clientId.isNotBlank() && redirectUri.isNotBlank()

    /**
     * Web OAuth clients can only redirect to an https page, which the app can't intercept; the
     * user copies that page's address back into the app instead.
     */
    val usesManualRedirect: Boolean get() = redirectUri.startsWith("https://")

    val partnerConnectionsUrl: String
        get() = "https://nestservices.google.com/partnerconnections/$projectId/auth"
}

/** Build-time defaults (from local.properties), provided by the app module. */
data class DefaultSdmConfig(val value: SdmConfig)

/** Current settings: build-time defaults, optionally overridden in Settings. */
@Singleton
class SdmConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val defaults: DefaultSdmConfig,
) {
    private val prefs = context.getSharedPreferences("sdm_config", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<SdmConfig> = _config.asStateFlow()

    fun update(config: SdmConfig) {
        prefs.edit {
            putString(KEY_PROJECT, config.projectId.trim())
            putString(KEY_CLIENT, config.clientId.trim())
            putString(KEY_SECRET, config.clientSecret?.trim().orEmpty())
            putString(KEY_REDIRECT, config.redirectUri.trim())
        }
        _config.value = load()
    }

    fun resetToDefaults() {
        prefs.edit { clear() }
        _config.value = load()
    }

    private fun load(): SdmConfig {
        val d = defaults.value
        return SdmConfig(
            projectId = prefs.getString(KEY_PROJECT, null)?.takeIf { it.isNotBlank() } ?: d.projectId,
            clientId = prefs.getString(KEY_CLIENT, null)?.takeIf { it.isNotBlank() } ?: d.clientId,
            clientSecret = (prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() } ?: d.clientSecret)
                ?.takeIf { it.isNotBlank() },
            redirectUri = prefs.getString(KEY_REDIRECT, null)?.takeIf { it.isNotBlank() } ?: d.redirectUri,
        )
    }

    private companion object {
        const val KEY_PROJECT = "project_id"
        const val KEY_CLIENT = "client_id"
        const val KEY_SECRET = "client_secret"
        const val KEY_REDIRECT = "redirect_uri"
    }
}
