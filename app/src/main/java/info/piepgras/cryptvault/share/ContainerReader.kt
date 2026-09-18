package info.piepgras.cryptvault.share

import kage.Age
import kage.crypto.scrypt.ScryptIdentity
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.util.Passphrase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** The passphrase did not open the container. */
class WrongPassphraseException : IOException("wrong passphrase")

/** The container's key derivation needs more memory than this device grants the app (age scrypt at a high work factor). */
class ContainerTooLargeException : IOException("not enough memory to open this container")

/**
 * Opens a received container (BUILD_BRIEF.md §7.4): the kind is decided by content
 * ([ContainerSniff]), never by name; the result is a list of plain files in a directory the
 * caller wipes afterwards. An inner ZIP (several files inside age/PGP) is unpacked.
 */
object ContainerReader {

    fun detect(file: File): ContainerKind? {
        val head = ByteArray(ContainerSniff.HEAD_BYTES)
        val n = FileInputStream(file).use { it.read(head) }
        return if (n <= 0) null else ContainerSniff.sniff(head.copyOf(n))
    }

    /** Decrypts [file] of [kind] into [into]; returns the extracted files (flat, unique names). */
    fun open(kind: ContainerKind, file: File, passphrase: CharArray, into: File): List<File> {
        into.mkdirs()
        return when (kind) {
            ContainerKind.ZIP -> extractZip(file, passphrase, into)
            ContainerKind.AGE, ContainerKind.PGP -> {
                val plain = File(into, "payload.bin")
                val name = if (kind == ContainerKind.AGE) decryptAge(file, passphrase, plain) else decryptPgp(file, passphrase, plain)
                val inner = detect(plain)
                if (inner == ContainerKind.ZIP) {
                    try { extractZip(plain, null, into) } finally { plain.delete() }
                } else {
                    val target = File(into, uniqueIn(into, (name ?: file.name.removeSuffix(".${kind.extension}")).substringAfterLast('/').ifBlank { "shared" }))
                    if (!plain.renameTo(target)) { plain.copyTo(target, overwrite = true); plain.delete() }
                    listOf(target)
                }
            }
        }
    }

    private fun extractZip(file: File, passphrase: CharArray?, into: File): List<File> {
        val out = ArrayList<File>()
        try {
            val zip = if (passphrase != null) ZipFile(file, passphrase) else ZipFile(file)
            zip.use { z ->
                if (passphrase == null && z.isEncrypted) throw WrongPassphraseException()
                for (header in z.fileHeaders) {
                    if (header.isDirectory) continue
                    // flatten and defuse zip-slip: only the entry's own name survives
                    val name = uniqueIn(into, header.fileName.trimEnd('/').substringAfterLast('/').ifBlank { "entry" })
                    z.extractFile(header, into.path, name)
                    out += File(into, name)
                }
            }
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD || e.message?.contains("password", ignoreCase = true) == true) throw WrongPassphraseException()
            throw IOException("the archive could not be read: ${e.message}", e)
        }
        return out
    }

    /** Returns null: age carries no file name. */
    private fun decryptAge(file: File, passphrase: CharArray, plain: File): String? {
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(plain).use { output ->
                    try {
                        Age.decryptStream(listOf(ScryptIdentity(bytes)), input, output)
                    } catch (e: OutOfMemoryError) {
                        throw ContainerTooLargeException()
                    } catch (e: Exception) {
                        throw WrongPassphraseException().initCause(e) as IOException
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
        return null
    }

    /** Returns the file name from the literal packet, when the sender set one. */
    private fun decryptPgp(file: File, passphrase: CharArray, plain: File): String? {
        val options = ConsumerOptions.get().addMessagePassphrase(Passphrase.fromPassword(String(passphrase)))
        return try {
            FileInputStream(file).use { input ->
                val dec = CryptVaultPgp.api.processMessage().onInputStream(input).withOptions(options)
                FileOutputStream(plain).use { output -> dec.use { it.copyTo(output) } }
                dec.metadata.filename?.takeIf { it.isNotBlank() }
            }
        } catch (e: WrongPassphraseException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ContainerTooLargeException()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("passphrase", ignoreCase = true) || msg.contains("password", ignoreCase = true) || msg.contains("decrypt", ignoreCase = true) || msg.contains("session key", ignoreCase = true)) {
                throw WrongPassphraseException().initCause(e) as IOException
            }
            throw IOException("the message could not be read: $msg", e)
        }
    }

    private fun uniqueIn(dir: File, name: String): String {
        var candidate = name
        var n = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)} (${++n})${name.substring(dot)}" else "$name (${++n})"
        }
        return candidate
    }
}
