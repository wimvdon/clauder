package to.wim.clauder.claudeai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Never backed up (only backed_up_settings is, see res/xml/data_extraction_rules.xml). */
private val Context.claudeAiLogin: DataStore<Preferences> by preferencesDataStore(name = "claude_ai_login")

/**
 * The claude.ai sign-in (the `sessionKey` cookie), encrypted with a key that stays in this phone's
 * Android Keystore. It is never backed up and never sent anywhere but claude.ai.
 */
class ClaudeAiLogin(context: Context) {
    private val store = context.applicationContext.claudeAiLogin
    private val sealedKey = stringPreferencesKey("sealed_session_key")

    val isConnected: Flow<Boolean> = store.data.map { it[sealedKey] != null }

    suspend fun save(sessionKey: String) {
        val sealed = SealedText.seal(keystoreKey(), sessionKey)
        store.edit { it[sealedKey] = sealed }
    }

    /** The session key, or null when there is none or it can no longer be read. */
    suspend fun sessionKey(): String? {
        val sealed = store.data.first()[sealedKey] ?: return null
        return SealedText.open(keystoreKey(), sealed)
    }

    /** Forgets the sign-in here and in the sign-in page's WebView. */
    suspend fun clear() {
        store.edit { it.remove(sealedKey) }
        forgetWebLogin()
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "claude_ai_login"

        /** Removes the cookies and site data the sign-in page left, so only the encrypted copy remains. */
        fun forgetWebLogin() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}
