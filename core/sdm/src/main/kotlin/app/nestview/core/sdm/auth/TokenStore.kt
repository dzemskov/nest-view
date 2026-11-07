package app.nestview.core.sdm.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the OAuth state (refresh token) encrypted with a key held in the Android Keystore. */
@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("nestview_auth", Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun read(): String? {
        val stored = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching {
            String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ASSOCIATED_DATA), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Could not decrypt stored sign-in; sign in again", it) }.getOrNull()
    }

    fun write(value: String?) {
        if (value == null) {
            prefs.edit { remove(KEY_STATE) }
            return
        }
        val encrypted = aead.encrypt(value.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        prefs.edit { putString(KEY_STATE, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }

    private companion object {
        const val TAG = "TokenStore"
        const val KEY_STATE = "auth_state"
        const val KEYSET_NAME = "nestview_keyset"
        const val KEYSET_PREFS = "nestview_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://nestview_master_key"
        val ASSOCIATED_DATA = "nestview-auth-state".toByteArray()
    }
}
