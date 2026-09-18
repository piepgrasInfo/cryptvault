package info.piepgras.cryptvault.backup

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption of the snapshot manifests (`cryptvault/snapshots/<seq>.json.enc`).
 *
 * The key is derived from the vault's 64-byte masterkey (HKDF-SHA256, info
 * `cryptvault-snapshot-v1`), so a restore recovers it from the password alone; the app keeps
 * that derived key Keystore-wrapped (`secrets/<vaultId>.snapshotkey`) so the backup worker can
 * write manifests while the vault is locked. A manifest holds ciphertext paths, sizes and
 * hashes — what the remote listing shows anyway — never a cleartext name (docs/THREAT_MODEL.md S9).
 *
 * Format: `CVS1` ‖ 12-byte IV ‖ AES-256-GCM(ciphertext ‖ 16-byte tag), AAD = the magic.
 */
object SnapshotCrypto {
    private val MAGIC = "CVS1".toByteArray(Charsets.US_ASCII)
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val INFO = "cryptvault-snapshot-v1".toByteArray(Charsets.US_ASCII)

    /** HKDF-SHA256(masterkey) → 32 bytes. [rawKey] is the vault's raw masterkey, not modified. */
    fun deriveKey(rawKey: ByteArray): ByteArray {
        require(rawKey.size == 64) { "masterkey must be 64 bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        // extract with a fixed salt, expand one block
        mac.init(SecretKeySpec("cryptvault-snapshot-salt".toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        val prk = mac.doFinal(rawKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO)
        mac.update(1.toByte())
        val out = mac.doFinal()
        prk.fill(0)
        return out
    }

    fun encrypt(key: ByteArray, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        val body = cipher.doFinal(plain)
        return MAGIC + iv + body
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < MAGIC.size + IV_LEN + TAG_BITS / 8 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("not a CryptVault snapshot")
        }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(blob, MAGIC.size + IV_LEN, blob.size - MAGIC.size - IV_LEN)
        } catch (e: GeneralSecurityException) {
            throw IOException("snapshot does not decrypt with this vault's key", e)
        }
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
