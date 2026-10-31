package app.oneassist.security

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Provides the SQLCipher passphrase: 32 random bytes, generated once, stored only as AES-GCM
 * ciphertext (wrapped by a Keystore key) in an app-private, no-backup file.
 */
class DatabaseKeyProvider(private val context: Context) {

    private val cipher = KeystoreCipher(KEY_ALIAS)
    private val file: File get() = File(context.noBackupFilesDir, KEY_FILE)

    /**
     * Returns the passphrase, creating it on first use. If the wrapped passphrase can never be
     * unwrapped again (Keystore key permanently gone), [onUnrecoverable] runs (it should delete the
     * now unreadable database) and a fresh passphrase is created.
     */
    @Synchronized
    fun getOrCreatePassphrase(onUnrecoverable: () -> Unit = {}): ByteArray {
        val f = file
        if (f.exists()) {
            try {
                return cipher.decrypt(f.readBytes())
            } catch (e: Exception) {
                if (!isPermanent(e)) throw e
                onUnrecoverable()
                f.delete()
                cipher.deleteKey()
            }
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = File(f.parentFile, "$KEY_FILE.tmp")
        tmp.writeBytes(cipher.encrypt(passphrase))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Could not store database key" }
        }
        return passphrase
    }

    private fun isPermanent(e: Exception): Boolean =
        e is KeystoreCipher.KeyMissingException ||
            e is AEADBadTagException ||
            e is java.security.UnrecoverableKeyException ||
            e is android.security.keystore.KeyPermanentlyInvalidatedException ||
            e is IllegalArgumentException // corrupt key file

    private companion object {
        const val KEY_ALIAS = "oneassist_db_wrap_key"
        const val KEY_FILE = "db_passphrase.bin"
    }
}
