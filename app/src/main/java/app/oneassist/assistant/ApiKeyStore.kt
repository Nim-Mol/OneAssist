package app.oneassist.assistant

import android.content.Context
import android.util.Base64
import app.oneassist.security.KeystoreCipher

/**
 * Stores the user's Anthropic API key encrypted with an AES-GCM Android Keystore key.
 * SharedPreferences holds only the ciphertext. The key is never logged.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    fun has(): Boolean = prefs.contains(PREF_KEY)

    fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API key is empty" }
        val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** The key, or null if none is saved or it can no longer be decrypted. */
    fun get(): String? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Unreadable (for example the Keystore key was reset): forget it so the user re-enters it.
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    private companion object {
        const val PREFS = "assistant_secure_prefs"
        const val PREF_KEY = "anthropic_api_key_enc"
        const val KEY_ALIAS = "oneassist_api_key_wrap"
    }
}
