package info.piepgras.cryptvault.vault

import com.google.common.io.BaseEncoding
import org.cryptomator.cryptolib.api.AuthenticationFailedException
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.CryptorProvider
import org.cryptomator.cryptolib.api.FileHeader
import org.cryptomator.cryptolib.api.InvalidPassphraseException
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.DecryptingReadableByteChannel
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class WrongPasswordException : Exception("wrong password")
class VaultFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class EntryKind { FILE, DIRECTORY, SYMLINK }

/**
 * One entry of a vault directory, in the clear. [entryPath] is the ciphertext entry as listed
 * (`….c9r` file or directory, or a `….c9s` directory for a shortened name); [contentPath] is the
 * ciphertext file holding a FILE's bytes; [dirId] is a DIRECTORY's id; [size] is the cleartext
 * size of a FILE (-1 when the ciphertext length is impossible, i.e. the file is corrupt).
 */
data class VaultEntry(
    val name: String,
    val kind: EntryKind,
    val entryPath: String,
    val contentPath: String? = null,
    val size: Long = 0,
    val dirId: String? = null,
) {
    val isShortened: Boolean get() = entryPath.endsWith(CryptomatorVault.SHORTENED_SUFFIX)
}

/**
 * A Cryptomator vault of format 8, byte for byte the format the desktop, iOS and Android
 * Cryptomator apps read (https://docs.cryptomator.org/en/latest/security/vault/), on any
 * [VaultStorage]. All cryptography is `org.cryptomator:cryptolib`; this class owns the
 * directory layout: hashed directory ids under `d/`, AES-SIV names with the parent directory
 * id as associated data, `dir.c9r` markers, `.c9s` shortening above the threshold, and the
 * `dirid.c9r` backup file in every directory.
 *
 * Paths handed to [VaultStorage] are ciphertext paths; names handed to callers are cleartext.
 * The instance holds the masterkey in memory until [close], which is what "locked" means.
 */
class CryptomatorVault private constructor(
    private val storage: VaultStorage,
    private val masterkey: Masterkey,
    private val cryptor: Cryptor,
    private val random: SecureRandom,
    val config: VaultConfig,
) : AutoCloseable {

    companion object {
        const val VAULT_CONFIG_FILE = "vault.cryptomator"
        const val MASTERKEY_FILE = "masterkey.cryptomator"
        const val DATA_DIR = "d"
        const val ROOT_DIR_ID = ""
        const val FORMAT = 8
        const val CIPHER_COMBO = "SIV_GCM"
        const val SHORTENING_THRESHOLD = 220
        const val ENTRY_SUFFIX = ".c9r"
        const val SHORTENED_SUFFIX = ".c9s"
        const val DIR_FILE = "dir.c9r"
        const val SYMLINK_FILE = "symlink.c9r"
        const val CONTENTS_FILE = "contents.c9r"
        const val LONG_NAME_FILE = "name.c9s"
        const val DIR_ID_BACKUP_FILE = "dirid.c9r"
        /** A file being written; invisible to every Cryptomator client until renamed on close. */
        const val TEMP_SUFFIX = ".tmp"

        // Every Cryptomator client uses an empty pepper; anything else would make the vault
        // unreadable elsewhere.
        private val PEPPER = ByteArray(0)
        private const val MASTERKEY_FILE_VERSION = 999

        // Instantiated directly rather than through CryptorProvider.forScheme, which goes through
        // ServiceLoader and META-INF/services — one more thing for R8 to get wrong on a release
        // build, for no gain when the scheme is fixed.
        private fun cryptorProvider(): CryptorProvider = org.cryptomator.cryptolib.v2.CryptorProviderImpl()

        /** True when [storage] holds the two files every format 8 vault has. */
        fun isVault(storage: VaultStorage): Boolean =
            storage.stat(VAULT_CONFIG_FILE) != null && storage.stat(MASTERKEY_FILE) != null

        /** Creates a new, empty vault in [storage] (which must not already hold one). */
        fun create(storage: VaultStorage, password: CharArray, random: SecureRandom = SecureRandom()): CryptomatorVault {
            if (storage.stat(VAULT_CONFIG_FILE) != null || storage.stat(MASTERKEY_FILE) != null) {
                throw IOException("storage already holds a vault")
            }
            val masterkey = Masterkey.generate(random)
            val access = MasterkeyFileAccess(PEPPER, random)
            Channels.newOutputStream(storage.writeChannel(MASTERKEY_FILE)).use { out ->
                access.persist(masterkey, out, CharBuffer.wrap(password), MASTERKEY_FILE_VERSION)
            }
            val config = VaultConfig(FORMAT, CIPHER_COMBO, SHORTENING_THRESHOLD, UUID.randomUUID().toString())
            val rawKey = masterkey.encoded.clone() // getEncoded() hands out the live key, not a copy
            try {
                writeText(storage, VAULT_CONFIG_FILE, VaultConfigToken.create(config, rawKey))
            } finally {
                rawKey.fill(0)
            }
            val vault = CryptomatorVault(storage, masterkey, cryptorProvider().provide(masterkey, random), random, config)
            vault.initDirectory(ROOT_DIR_ID)
            return vault
        }

        /**
         * Opens the vault in [storage].
         * @throws WrongPasswordException when the password does not unwrap the masterkey
         * @throws VaultFormatException when there is no format 8 vault here, or its config is invalid
         */
        fun open(storage: VaultStorage, password: CharArray, random: SecureRandom = SecureRandom()): CryptomatorVault {
            val token = readText(storage, VAULT_CONFIG_FILE)
                ?: throw VaultFormatException("no vault.cryptomator: not a Cryptomator vault of format 8")
            val keyId = VaultConfigToken.keyId(token)
            if (keyId != VaultConfigToken.KEY_ID) {
                throw VaultFormatException("vault is keyed by '$keyId', not by a masterkey file")
            }
            if (storage.stat(MASTERKEY_FILE) == null) throw VaultFormatException("masterkey.cryptomator is missing")
            val access = MasterkeyFileAccess(PEPPER, random)
            val masterkey = try {
                Channels.newInputStream(storage.readChannel(MASTERKEY_FILE)).use { access.load(it, CharBuffer.wrap(password)) }
            } catch (e: InvalidPassphraseException) {
                throw WrongPasswordException()
            }
            val rawKey = masterkey.encoded.clone() // getEncoded() hands out the live key, not a copy
            val config = try {
                VaultConfigToken.verify(token, rawKey)
            } catch (e: VaultConfigException) {
                masterkey.destroy()
                throw VaultFormatException(e.message ?: "invalid vault.cryptomator", e)
            } finally {
                rawKey.fill(0)
            }
            if (config.format != FORMAT) {
                masterkey.destroy()
                throw VaultFormatException("vault format ${config.format} is not supported, only format $FORMAT is")
            }
            if (config.cipherCombo != CIPHER_COMBO) {
                masterkey.destroy()
                throw VaultFormatException("cipher combo ${config.cipherCombo} is not supported, only $CIPHER_COMBO is")
            }
            return CryptomatorVault(storage, masterkey, cryptorProvider().provide(masterkey, random), random, config)
        }

        /**
         * Opens the vault with the 64 raw masterkey bytes (the biometric wrap's copy) instead of
         * the password; the config signature check guarantees the key belongs to this vault. The
         * caller zeroes [rawKey].
         */
        fun openWithRawKey(storage: VaultStorage, rawKey: ByteArray, random: SecureRandom = SecureRandom()): CryptomatorVault {
            val token = readText(storage, VAULT_CONFIG_FILE)
                ?: throw VaultFormatException("no vault.cryptomator: not a Cryptomator vault of format 8")
            val config = try {
                VaultConfigToken.verify(token, rawKey)
            } catch (e: VaultConfigException) {
                throw VaultFormatException("this key does not belong to this vault", e)
            }
            if (config.format != FORMAT || config.cipherCombo != CIPHER_COMBO) throw VaultFormatException("unsupported vault format")
            val masterkey = Masterkey(rawKey.copyOf())
            return CryptomatorVault(storage, masterkey, cryptorProvider().provide(masterkey, random), random, config)
        }

        /**
         * Replaces the masterkey file using the raw key from a recovery key. The key is checked
         * against `vault.cryptomator`'s signature first, so a recovery key for another vault is
         * refused before anything is written. The caller zeroes [rawKey].
         */
        fun resetPassword(storage: VaultStorage, rawKey: ByteArray, newPassword: CharArray, random: SecureRandom = SecureRandom()) {
            val token = readText(storage, VAULT_CONFIG_FILE)
                ?: throw VaultFormatException("no vault.cryptomator: not a Cryptomator vault of format 8")
            try {
                VaultConfigToken.verify(token, rawKey)
            } catch (e: VaultConfigException) {
                throw VaultFormatException("this recovery key does not belong to this vault", e)
            }
            Masterkey(rawKey.copyOf()).use { masterkey ->
                val tmp = "$MASTERKEY_FILE.tmp"
                Channels.newOutputStream(storage.writeChannel(tmp)).use { out ->
                    MasterkeyFileAccess(PEPPER, random).persist(masterkey, out, CharBuffer.wrap(newPassword), MASTERKEY_FILE_VERSION)
                }
                storage.move(tmp, MASTERKEY_FILE)
            }
        }

        private fun readText(storage: VaultStorage, path: String): String? {
            if (storage.stat(path) == null) return null
            return Channels.newInputStream(storage.readChannel(path)).use { String(it.readBytes(), Charsets.UTF_8) }
        }

        private fun writeText(storage: VaultStorage, path: String, text: String) {
            storage.writeChannel(path).use { it.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))) }
        }
    }

    val headerSize: Int get() = cryptor.fileHeaderCryptor().headerSize()
    val cleartextChunkSize: Int get() = cryptor.fileContentCryptor().cleartextChunkSize()
    val ciphertextChunkSize: Int get() = cryptor.fileContentCryptor().ciphertextChunkSize()

    /** A copy of the 64 raw masterkey bytes, for the recovery key. The caller zeroes it. */
    fun rawKey(): ByteArray = masterkey.encoded.clone()

    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        val tmp = "$MASTERKEY_FILE.tmp"
        try {
            Channels.newInputStream(storage.readChannel(MASTERKEY_FILE)).use { input ->
                Channels.newOutputStream(storage.writeChannel(tmp)).use { out ->
                    MasterkeyFileAccess(PEPPER, random).changePassphrase(
                        input, out, CharBuffer.wrap(oldPassword), CharBuffer.wrap(newPassword),
                    )
                }
            }
        } catch (e: InvalidPassphraseException) {
            runCatching { storage.delete(tmp) }
            throw WrongPasswordException()
        }
        storage.move(tmp, MASTERKEY_FILE)
    }

    override fun close() {
        cryptor.destroy()
        masterkey.destroy()
    }

    // ---- names and paths -------------------------------------------------------------------

    /** `d/<2>/<30>` for a directory id. */
    fun dirPath(dirId: String): String {
        val hash = cryptor.fileNameCryptor().hashDirectoryId(dirId)
        return "$DATA_DIR/${hash.substring(0, 2)}/${hash.substring(2)}"
    }

    private fun encryptName(dirId: String, name: String): String =
        cryptor.fileNameCryptor().encryptFilename(BaseEncoding.base64Url(), name, dirId.toByteArray(Charsets.UTF_8)) + ENTRY_SUFFIX

    private fun decryptName(dirId: String, ciphertextName: String): String? = try {
        cryptor.fileNameCryptor().decryptFilename(
            BaseEncoding.base64Url(), ciphertextName.removeSuffix(ENTRY_SUFFIX), dirId.toByteArray(Charsets.UTF_8),
        )
    } catch (e: AuthenticationFailedException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun shortenedName(ciphertextName: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(ciphertextName.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().encodeToString(sha1) + SHORTENED_SUFFIX
    }

    /** Where an entry named [name] in directory [dirId] lives, whether or not it exists yet. */
    private class Location(val parent: String, val entryName: String, val longName: String?) {
        val entryPath: String get() = "$parent/$entryName"
        val isShortened: Boolean get() = longName != null
    }

    private fun locate(dirId: String, name: String): Location {
        require(name.isNotEmpty() && !name.contains('/') && name != "." && name != "..") { "bad name: $name" }
        val enc = encryptName(dirId, name)
        val parent = dirPath(dirId)
        return if (enc.length > config.shorteningThreshold) Location(parent, shortenedName(enc), enc) else Location(parent, enc, null)
    }

    // ---- directories -----------------------------------------------------------------------

    private fun initDirectory(dirId: String) {
        storage.createDirectory(dirPath(dirId))
        val backup = "${dirPath(dirId)}/$DIR_ID_BACKUP_FILE"
        EncryptingChannel(storage.writeChannel(backup)).use {
            it.write(ByteBuffer.wrap(dirId.toByteArray(Charsets.UTF_8)))
        }
    }

    fun exists(dirId: String, name: String): Boolean = storage.stat(locate(dirId, name).entryPath) != null

    fun list(dirId: String): List<VaultEntry> {
        val parent = dirPath(dirId)
        val out = ArrayList<VaultEntry>()
        for (e in storage.list(parent)) {
            val entryPath = "$parent/${e.name}"
            when {
                e.name == DIR_ID_BACKUP_FILE -> Unit
                e.name.endsWith(ENTRY_SUFFIX) -> {
                    val name = decryptName(dirId, e.name) ?: continue
                    if (e.isDirectory) {
                        out += classifyEntryDir(name, entryPath, null) ?: continue
                    } else {
                        out += VaultEntry(name, EntryKind.FILE, entryPath, entryPath, cleartextSize(e.size))
                    }
                }
                e.name.endsWith(SHORTENED_SUFFIX) && e.isDirectory -> {
                    val longName = readText(storage, "$entryPath/$LONG_NAME_FILE") ?: continue
                    val name = decryptName(dirId, longName) ?: continue
                    out += classifyEntryDir(name, entryPath, storage.stat("$entryPath/$CONTENTS_FILE")) ?: continue
                }
            }
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }

    private fun classifyEntryDir(name: String, entryPath: String, contents: StorageEntry?): VaultEntry? {
        if (storage.stat("$entryPath/$DIR_FILE") != null) {
            val childId = readText(storage, "$entryPath/$DIR_FILE")?.trim() ?: return null
            return VaultEntry(name, EntryKind.DIRECTORY, entryPath, dirId = childId)
        }
        if (contents != null) {
            return VaultEntry(name, EntryKind.FILE, entryPath, "$entryPath/$CONTENTS_FILE", cleartextSize(contents.size))
        }
        if (storage.stat("$entryPath/$SYMLINK_FILE") != null) return VaultEntry(name, EntryKind.SYMLINK, entryPath)
        return null
    }

    /**
     * Cleartext size for a ciphertext file length, or -1 if no cleartext could produce that
     * length. A trailing empty chunk (28 bytes: what cryptolib's own writer emits after an
     * exact multiple of 32 KiB) counts as valid here, unlike in cryptolib's `cleartextSize`,
     * so a file another client wrote that way is listed rather than hidden.
     */
    fun cleartextSize(ciphertextSize: Long): Long {
        val payload = ciphertextSize - headerSize
        if (payload < 0) return -1
        val overhead = (ciphertextChunkSize - cleartextChunkSize).toLong()
        val full = payload / ciphertextChunkSize
        val rest = payload % ciphertextChunkSize
        return when {
            rest == 0L || rest == overhead -> full * cleartextChunkSize
            rest < overhead -> -1
            else -> full * cleartextChunkSize + (rest - overhead)
        }
    }

    /** Creates a directory and returns its id. */
    fun createDirectory(parentDirId: String, name: String): String {
        val loc = locate(parentDirId, name)
        if (storage.stat(loc.entryPath) != null) throw IOException("'$name' already exists")
        val newId = UUID.randomUUID().toString()
        storage.createDirectory(loc.entryPath)
        if (loc.longName != null) writeText(storage, "${loc.entryPath}/$LONG_NAME_FILE", loc.longName)
        writeText(storage, "${loc.entryPath}/$DIR_FILE", newId)
        initDirectory(newId)
        return newId
    }

    // ---- files -----------------------------------------------------------------------------

    /**
     * Opens a new file for writing; bytes are encrypted as they are written and the header on
     * close. The bytes go to a `.tmp` sibling that no client lists and are renamed into place
     * when the channel closes, so a process killed mid-write leaves a stray temp file (swept by
     * [sweepTemp]) and never a truncated entry. An existing file of that name is replaced.
     */
    fun writeFile(dirId: String, name: String): WritableByteChannel {
        val loc = locate(dirId, name)
        val contentPath = if (loc.longName != null) {
            storage.createDirectory(loc.entryPath)
            writeText(storage, "${loc.entryPath}/$LONG_NAME_FILE", loc.longName)
            "${loc.entryPath}/$CONTENTS_FILE"
        } else {
            loc.entryPath
        }
        val tempPath = contentPath + TEMP_SUFFIX
        return CommitOnClose(EncryptingChannel(storage.writeChannel(tempPath))) { storage.move(tempPath, contentPath) }
    }

    /** Deletes leftover `.tmp` files in a directory (a write the process did not survive). */
    fun sweepTemp(dirId: String): Int {
        val parent = dirPath(dirId)
        var n = 0
        for (e in storage.list(parent)) {
            if (!e.isDirectory && e.name.endsWith(TEMP_SUFFIX)) {
                runCatching { storage.delete("$parent/${e.name}") }.onSuccess { n++ }
            } else if (e.isDirectory && e.name.endsWith(SHORTENED_SUFFIX)) {
                for (c in storage.list("$parent/${e.name}")) {
                    if (!c.isDirectory && c.name.endsWith(TEMP_SUFFIX)) runCatching { storage.delete("$parent/${e.name}/${c.name}") }.onSuccess { n++ }
                }
            }
        }
        return n
    }

    private class CommitOnClose(private val inner: WritableByteChannel, private val commit: () -> Unit) : WritableByteChannel {
        private var closed = false
        override fun write(src: ByteBuffer): Int = inner.write(src)
        override fun isOpen(): Boolean = !closed
        override fun close() {
            if (closed) return
            closed = true
            inner.close()
            commit()
        }
    }

    /**
     * Encrypts as it writes: the header first, then one chunk per full 32 KiB and a final partial
     * chunk if there is one. Unlike cryptolib's `EncryptingWritableByteChannel`, whose `close()`
     * always encrypts the buffer, this never emits an empty trailing chunk — a file of exactly
     * n × 32 KiB (or of 0 bytes) otherwise gets 28 surplus bytes that `cleartextSize` rejects,
     * and Cryptomator desktop then shows it as empty.
     */
    private inner class EncryptingChannel(private val dest: WritableByteChannel) : WritableByteChannel {
        private val header: FileHeader = cryptor.fileHeaderCryptor().create()
        private val buffer: ByteBuffer = ByteBuffer.allocate(cleartextChunkSize)
        private var chunkNumber = 0L
        private var headerWritten = false
        private var open = true

        private fun writeHeaderOnce() {
            if (headerWritten) return
            headerWritten = true
            val h = cryptor.fileHeaderCryptor().encryptHeader(header)
            while (h.hasRemaining()) dest.write(h)
        }

        private fun flushChunk() {
            buffer.flip()
            if (buffer.hasRemaining()) {
                val c = cryptor.fileContentCryptor().encryptChunk(buffer, chunkNumber++, header)
                while (c.hasRemaining()) dest.write(c)
            }
            buffer.clear()
        }

        override fun write(src: ByteBuffer): Int {
            if (!open) throw IOException("channel is closed")
            writeHeaderOnce()
            var written = 0
            while (src.hasRemaining()) {
                val n = minOf(src.remaining(), buffer.remaining())
                val limit = src.limit()
                src.limit(src.position() + n)
                buffer.put(src)
                src.limit(limit)
                written += n
                if (!buffer.hasRemaining()) flushChunk()
            }
            return written
        }

        override fun isOpen(): Boolean = open

        override fun close() {
            if (!open) return
            open = false
            try {
                writeHeaderOnce()
                flushChunk()
            } finally {
                dest.close()
            }
        }
    }

    /** Streams a FILE entry's cleartext from the start, authenticating every chunk. */
    fun readFile(entry: VaultEntry): ReadableByteChannel {
        val path = entry.contentPath ?: throw IOException("'${entry.name}' is not a file")
        return DecryptingReadableByteChannel(storage.readChannel(path), cryptor, true)
    }

    /** Random-access decryption of a FILE entry, for the DocumentsProvider and previews. */
    fun openRandomAccess(entry: VaultEntry): RandomAccessReader {
        val path = entry.contentPath ?: throw IOException("'${entry.name}' is not a file")
        return RandomAccessReader(storage.readChannel(path))
    }

    inner class RandomAccessReader internal constructor(private val channel: SeekableByteChannel) : AutoCloseable {
        private val header: FileHeader
        private val ciphertextSize: Long = channel.size()
        val size: Long
        private var cachedChunk: Long = -1
        private var cachedCleartext: ByteBuffer = ByteBuffer.allocate(0)

        init {
            val buf = ByteBuffer.allocate(headerSize)
            channel.position(0)
            readFully(buf)
            if (buf.hasRemaining()) throw IOException("file shorter than its header")
            buf.flip()
            header = cryptor.fileHeaderCryptor().decryptHeader(buf)
            size = cleartextSize(ciphertextSize).also { if (it < 0) throw IOException("ciphertext length is not valid") }
        }

        /** Reads up to [dst].remaining() bytes at cleartext [offset]; returns bytes read, or -1 at EOF. */
        fun read(offset: Long, dst: ByteBuffer): Int {
            if (offset >= size) return -1
            var total = 0
            var pos = offset
            while (dst.hasRemaining() && pos < size) {
                val chunkIndex = pos / cleartextChunkSize
                val chunk = chunk(chunkIndex)
                val within = (pos - chunkIndex * cleartextChunkSize).toInt()
                if (within >= chunk.limit()) break
                val n = minOf(dst.remaining(), chunk.limit() - within)
                dst.put(chunk.array(), chunk.arrayOffset() + within, n)
                total += n
                pos += n
            }
            return total
        }

        private fun chunk(index: Long): ByteBuffer {
            if (index == cachedChunk) return cachedCleartext
            val buf = ByteBuffer.allocate(ciphertextChunkSize)
            channel.position(headerSize + index * ciphertextChunkSize)
            readFully(buf)
            buf.flip()
            val clear = cryptor.fileContentCryptor().decryptChunk(buf, index, header, true)
            cachedChunk = index
            cachedCleartext = clear
            return clear
        }

        /** Fills [buf] from the channel until it is full or the channel ends. */
        private fun readFully(buf: ByteBuffer) {
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) break
            }
        }

        override fun close() {
            channel.close()
        }
    }

    // ---- rename, move, delete --------------------------------------------------------------

    /** Moves or renames an entry; the target name gets encrypted under the target directory's id. */
    fun move(entry: VaultEntry, toDirId: String, newName: String) {
        val target = locate(toDirId, newName)
        if (storage.stat(target.entryPath) != null) throw IOException("'$newName' already exists")
        when (entry.kind) {
            EntryKind.FILE -> {
                val src = entry.contentPath!!
                if (target.longName != null) {
                    storage.createDirectory(target.entryPath)
                    writeText(storage, "${target.entryPath}/$LONG_NAME_FILE", target.longName)
                    storage.move(src, "${target.entryPath}/$CONTENTS_FILE")
                } else {
                    storage.move(src, target.entryPath)
                }
                if (entry.isShortened) storage.deleteRecursively(entry.entryPath)
            }
            EntryKind.DIRECTORY, EntryKind.SYMLINK -> {
                storage.move(entry.entryPath, target.entryPath)
                val longNameFile = "${target.entryPath}/$LONG_NAME_FILE"
                if (target.longName != null) writeText(storage, longNameFile, target.longName)
                else if (storage.stat(longNameFile) != null) storage.delete(longNameFile)
            }
        }
    }

    /** Deletes an entry; a directory's whole subtree goes with it. */
    fun delete(entry: VaultEntry) {
        when (entry.kind) {
            EntryKind.FILE -> {
                storage.delete(entry.contentPath!!)
                if (entry.isShortened) storage.deleteRecursively(entry.entryPath)
            }
            EntryKind.DIRECTORY -> {
                val dirId = entry.dirId!!
                for (child in list(dirId)) delete(child)
                storage.deleteRecursively(dirPath(dirId))
                storage.deleteRecursively(entry.entryPath)
            }
            EntryKind.SYMLINK -> storage.deleteRecursively(entry.entryPath)
        }
    }
}
