package info.piepgras.cryptvault.unlock

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The biometric shortcut (BUILD_BRIEF.md §4.3, docs/VAULT_LAYOUT.md §4): a copy of the vault's
 * 64-byte masterkey, AES-256-GCM-wrapped by a hardware-backed Keystore key that can only be used
 * within [AUTH_WINDOW_SECONDS] of a strong biometric (or, on API 30+, device credential)
 * authentication. The key never leaves the Keystore; the wrapped bytes live in the app's private
 * `secrets/` directory, which is excluded from backups.
 *
 * The password path never depends on this: losing the key (new enrolment, reinstall, new phone)
 * only loses the shortcut, which is re-created after the next password unlock.
 *
 * Pattern after Cryptomator Android's BiometricAuthCryptor (GPL-3.0): GCM, 12-byte IV prepended,
 * setUserAuthenticationRequired + setInvalidatedByBiometricEnrollment.
 */
class BiometricWrap(private val context: Context) {

    companion object {
        const val AUTH_WINDOW_SECONDS = 300
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "cryptvault.biometric."
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val SECRETS_DIR = "secrets"
    }

    class InvalidatedException : Exception("the biometric key was invalidated")
    class NotAuthenticatedException : Exception("authentication required")

    private fun file(vaultId: String): File = File(File(context.filesDir, SECRETS_DIR), "$vaultId.biometric")
    private fun alias(vaultId: String) = ALIAS_PREFIX + vaultId
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun isEnabled(vaultId: String): Boolean = file(vaultId).exists() && runCatching { keyStore().containsAlias(alias(vaultId)) }.getOrDefault(false)

    /** A cipher for [BiometricPrompt.CryptoObject]-free use inside the auth window; throws when the window has passed. */
    private fun cipherFor(vaultId: String, mode: Int, iv: ByteArray? = null): Cipher {
        val key = keyStore().getKey(alias(vaultId), null) as? SecretKey ?: throw InvalidatedException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            if (mode == Cipher.ENCRYPT_MODE) cipher.init(mode, key) else cipher.init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(vaultId)
            throw InvalidatedException()
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            throw NotAuthenticatedException()
        }
        return cipher
    }

    /** Creates (or replaces) the Keystore key for a vault. Hardware-backed; StrongBox when present. */
    private fun generateKey(vaultId: String) {
        val builder = KeyGenParameterSpec.Builder(alias(vaultId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(AUTH_WINDOW_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(builder.setIsStrongBoxBacked(true).build())
                return
            } catch (e: StrongBoxUnavailableException) {
                // fall through to the TEE
            } catch (e: Exception) {
                // some devices report StrongBox but fail on generation; the TEE is fine
            }
            builder.setIsStrongBoxBacked(false)
        }
        generate(builder.build())
    }

    private fun generate(spec: KeyGenParameterSpec) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /**
     * Enables the shortcut: wraps [rawKey] (zeroed by the caller) after a fresh authentication.
     * @throws NotAuthenticatedException when no authentication happened within the window
     */
    fun enable(vaultId: String, rawKey: ByteArray) {
        if (!keyStore().containsAlias(alias(vaultId))) generateKey(vaultId)
        val cipher = cipherFor(vaultId, Cipher.ENCRYPT_MODE)
        val wrapped = cipher.doFinal(rawKey)
        val out = cipher.iv + wrapped
        val f = file(vaultId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /**
     * Unwraps the masterkey after an authentication within the window. The caller zeroes the result.
     * @throws NotAuthenticatedException, InvalidatedException
     */
    fun unwrap(vaultId: String): ByteArray {
        val bytes = file(vaultId).takeIf { it.exists() }?.readBytes() ?: throw InvalidatedException()
        if (bytes.size <= IV_LENGTH + 16) throw InvalidatedException()
        val cipher = cipherFor(vaultId, Cipher.DECRYPT_MODE, bytes.copyOfRange(0, IV_LENGTH))
        return try {
            cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
        } catch (e: javax.crypto.AEADBadTagException) {
            disable(vaultId)
            throw InvalidatedException()
        }
    }

    /** Removes the wrapped copy and the Keystore key. */
    fun disable(vaultId: String) {
        runCatching { file(vaultId).delete() }
        runCatching { keyStore().deleteEntry(alias(vaultId)) }
    }

    /** True when the device has a strong biometric or a credential enrolled that the prompt can use. */
    fun canUse(): Boolean {
        val manager = androidx.biometric.BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        return manager.canAuthenticate(authenticators) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
}
