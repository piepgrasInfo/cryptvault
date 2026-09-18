package info.piepgras.cryptvault.share

import kage.Age
import kage.crypto.scrypt.ScryptRecipient
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.api.MessageEncryptionMechanism
import org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation
import org.bouncycastle.openpgp.operator.PBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.StreamEncoding
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.util.Passphrase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Date

/**
 * PGPainless over Bouncy Castle with one change: the passphrase S2K of a symmetric message is
 * iterated-and-salted **SHA-256 at the maximum count** (encoded 0xFF = 65 011 712 iterations),
 * where BC's default is SHA-1 at 65 536. Everything else (v4 SKESK, SEIPDv1, no AEAD) is the
 * library's default for a passphrase-only message.
 */
object CryptVaultPgp {
    private val implementation = object : BcOpenPGPImplementation() {
        override fun pbeKeyEncryptionMethodGenerator(messagePassphrase: CharArray): PBEKeyEncryptionMethodGenerator =
            BcPBEKeyEncryptionMethodGenerator(messagePassphrase, BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256), S2K_COUNT_MAX)
    }
    private const val S2K_COUNT_MAX = 0xFF
    val api: PGPainless by lazy { PGPainless(implementation, PGPainless.getInstance().algorithmPolicy) }
}

/** One thing that goes into a container: a name and a way to open its bytes. */
class ShareEntry(val name: String, val size: Long, val open: () -> InputStream)

/**
 * The three container writers of BUILD_BRIEF.md §7.2, with exactly the parameters the brief
 * fixes so that the common tools open the result:
 *
 * - **ZIP**: AES-256, AE-2 (zip4j), entries under one top-level folder, DEFLATE except for
 *   already-compressed media (STORE). ZipCrypto cannot be produced.
 * - **age**: a passphrase (scrypt) recipient at age's default work factor; several entries
 *   travel inside an uncompressed, unencrypted ZIP first (`<title>.zip.age`).
 * - **OpenPGP**: symmetric only — v4 SKESK, AES-256, SEIPDv1 with MDC, binary output, the file
 *   name in the literal packet. Never v6/SEIPDv2/AEAD (GnuPG rejects them).
 */
object ContainerWriter {

    /** Writes [entries] as a [kind] container to [out]; [title] names the folder inside a ZIP. */
    fun write(kind: ContainerKind, entries: List<ShareEntry>, passphrase: CharArray, out: File, title: String, tempDir: File = out.parentFile!!) {
        require(entries.isNotEmpty()) { "nothing to share" }
        when (kind) {
            ContainerKind.ZIP -> writeZip(entries, passphrase, out, topFolder = title.ifBlank { "cryptvault" })
            ContainerKind.AGE, ContainerKind.PGP -> {
                if (entries.size == 1) {
                    val e = entries.single()
                    e.open().use { input -> if (kind == ContainerKind.AGE) writeAge(input, passphrase, out) else writePgp(input, passphrase, out, e.name) }
                } else {
                    // several files (or a file plus its note): an uncompressed, unencrypted ZIP first
                    val inner = File(tempDir, out.name + ".inner.zip")
                    try {
                        writeZip(entries, null, inner, topFolder = null, store = true)
                        FileInputStream(inner).use { input ->
                            if (kind == ContainerKind.AGE) writeAge(input, passphrase, out)
                            else writePgp(input, passphrase, out, out.name.removeSuffix(".gpg"))
                        }
                    } finally {
                        inner.delete()
                    }
                }
            }
        }
    }

    /** [passphrase] null → an unencrypted ZIP (the inner archive of age/PGP); [store] forces STORE for all. */
    fun writeZip(entries: List<ShareEntry>, passphrase: CharArray?, out: File, topFolder: String?, store: Boolean = false) {
        out.delete()
        val zip = if (passphrase != null) ZipFile(out, passphrase) else ZipFile(out)
        zip.use { z ->
            val used = HashSet<String>()
            for (e in entries) {
                var name = e.name
                var n = 1
                while (!used.add(name.lowercase())) { name = uniqueName(e.name, ++n) }
                val params = ZipParameters().apply {
                    fileNameInZip = if (topFolder != null) "$topFolder/$name" else name
                    entrySize = e.size
                    compressionMethod = if (store || SharePolicy.isAlreadyCompressed(name)) CompressionMethod.STORE else CompressionMethod.DEFLATE
                    if (passphrase != null) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        aesVersion = AesVersion.TWO
                    }
                }
                e.open().use { input -> z.addStream(input, params) }
            }
        }
    }

    private fun uniqueName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    fun writeAge(input: InputStream, passphrase: CharArray, out: File) {
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(out).use { output ->
                Age.encryptStream(listOf(ScryptRecipient(bytes)), input, output, false)
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun writePgp(input: InputStream, passphrase: CharArray, out: File, fileName: String, date: Date = Date()) {
        val options = ProducerOptions.encrypt(
            EncryptionOptions.get(CryptVaultPgp.api)
                .addMessagePassphrase(Passphrase.fromPassword(String(passphrase)))
                .overrideEncryptionMechanism(MessageEncryptionMechanism.integrityProtected(SymmetricKeyAlgorithmTags.AES_256)),
        )
            .setAsciiArmor(false)
            .setFileName(fileName)
            .setModificationDate(date)
            .setEncoding(StreamEncoding.BINARY)
        FileOutputStream(out).use { output ->
            val enc = CryptVaultPgp.api.generateMessage().onOutputStream(output).withOptions(options)
            enc.use { input.copyTo(it) }
        }
    }
}
