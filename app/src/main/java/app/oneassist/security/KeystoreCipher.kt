package app.oneassist.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. Output format:
 * [1 byte IV length][IV][ciphertext + tag]. The key never leaves the Keystore.
 */
class KeystoreCipher(private val alias: String) {

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "empty blob" }
        val ivLength = blob[0].toInt() and 0xFF
        require(ivLength in 12..16 && blob.size > 1 + ivLength) { "bad blob" }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val body = blob.copyOfRange(1 + ivLength, blob.size)
        val key = existingKey() ?: throw KeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    fun deleteKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // No user authentication: the notification listener must write while the app is locked.
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKey()
    }

    /** The Keystore key is gone (for example after the app's keys were cleared). */
    class KeyMissingException : IllegalStateException("Keystore key missing")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
