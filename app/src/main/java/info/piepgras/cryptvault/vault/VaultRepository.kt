package info.piepgras.cryptvault.vault

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import info.piepgras.cryptvault.items.Iso8601
import info.piepgras.cryptvault.items.OpenVault
import info.piepgras.cryptvault.items.ThumbnailMaker
import info.piepgras.cryptvault.recovery.RecoveryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The one door to vaults: the registry of known vaults, which of them are unlocked, and the
 * create / open / lock / delete operations. Screens reach it through a ViewModel. Everything
 * that touches disk runs on [Dispatchers.IO].
 */
class VaultRepository(
    private val resolver: ContentResolver,
    private val registry: VaultRegistry,
    private val privateRoot: File,
    private val thumbnails: ThumbnailMaker?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _open = MutableStateFlow<Map<String, OpenVault>>(emptyMap())
    /** Unlocked vaults by record id. */
    val open: StateFlow<Map<String, OpenVault>> get() = _open
    val records: StateFlow<List<VaultRecord>> get() = registry.records
    private val lock = Any()

    fun record(id: String): VaultRecord? = registry.get(id)
    fun openVault(id: String): OpenVault? = _open.value[id]
    fun isUnlocked(id: String): Boolean = _open.value.containsKey(id)

    fun storageFor(record: VaultRecord): VaultStorage = when (val loc = record.location) {
        is VaultLocation.Private -> PathVaultStorage(File(privateRoot, loc.dir).toPath())
        is VaultLocation.Saf -> SafVaultStorage(resolver, loc.treeUri.toUri())
    }

    /** Whether the SAF grant for a vault is still held; a private vault always is. */
    fun locationAvailable(record: VaultRecord): Boolean = when (val loc = record.location) {
        is VaultLocation.Private -> File(privateRoot, loc.dir).isDirectory
        is VaultLocation.Saf -> {
            val uri = loc.treeUri.toUri()
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission } &&
                runCatching { SafVaultStorage(resolver, uri).stat(CryptomatorVault.VAULT_CONFIG_FILE) != null }.getOrDefault(false)
        }
    }

    // ---- creating and adding ----------------------------------------------------------------

    suspend fun createPrivate(name: String, password: CharArray): VaultRecord = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(privateRoot, id)
        dir.mkdirs()
        val storage = PathVaultStorage(dir.toPath())
        CryptomatorVault.create(storage, password).close()
        val record = VaultRecord(id, name.trim().ifEmpty { "Vault" }, VaultLocation.Private(id), Iso8601.format(clock()))
        registry.add(record)
        record
    }

    /** Creates a vault inside a picked folder; the folder must be empty of vault files. */
    suspend fun createInFolder(name: String, password: CharArray, treeUri: Uri): VaultRecord = withContext(Dispatchers.IO) {
        takePersistable(treeUri)
        val storage = SafVaultStorage(resolver, treeUri)
        if (CryptomatorVault.isVault(storage)) throw IOException("that folder already holds a vault")
        SafVaultStorage.selfTest(resolver, treeUri)?.let { throw IOException(it) }
        CryptomatorVault.create(storage, password).close()
        val record = VaultRecord(UUID.randomUUID().toString(), name.trim().ifEmpty { "Vault" }, VaultLocation.Saf(treeUri.toString()), Iso8601.format(clock()))
        registry.add(record)
        record
    }

    /** Adds an existing Cryptomator vault (a picked folder holding `vault.cryptomator`). */
    suspend fun addExisting(name: String, treeUri: Uri): VaultRecord = withContext(Dispatchers.IO) {
        takePersistable(treeUri)
        val storage = SafVaultStorage(resolver, treeUri)
        if (!CryptomatorVault.isVault(storage)) throw VaultFormatException("no vault.cryptomator in that folder")
        if (registry.records.value.any { (it.location as? VaultLocation.Saf)?.treeUri == treeUri.toString() }) {
            throw IOException("that folder is already in the list")
        }
        SafVaultStorage.selfTest(resolver, treeUri)?.let { throw IOException(it) }
        val record = VaultRecord(UUID.randomUUID().toString(), name.trim().ifEmpty { "Vault" }, VaultLocation.Saf(treeUri.toString()), Iso8601.format(clock()))
        registry.add(record)
        record
    }

    private fun takePersistable(treeUri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** True when the picked tree is a cloud app's provider rather than local storage. */
    fun isCloudProvider(treeUri: Uri): Boolean {
        val authority = treeUri.authority ?: return false
        return authority != "com.android.externalstorage.documents" && authority != "com.android.providers.downloads.documents"
    }

    // ---- unlocking and locking --------------------------------------------------------------

    /**
     * @throws WrongPasswordException, VaultFormatException, IOException
     */
    suspend fun unlock(id: String, password: CharArray): OpenVault = withContext(Dispatchers.IO) {
        _open.value[id]?.let { return@withContext it }
        val record = registry.get(id) ?: throw IOException("unknown vault")
        val storage = storageFor(record)
        val vault = CryptomatorVault.open(storage, password)
        val open = OpenVault(record.id, vault, thumbnails, clock)
        try {
            open.initialize()
        } catch (e: Exception) {
            open.close()
            throw e
        }
        synchronized(lock) {
            _open.value[id]?.let { open.close(); return@withContext it }
            _open.value = _open.value + (id to open)
        }
        registry.update(record.copy(lastUnlockedAt = Iso8601.format(clock())))
        open
    }

    fun lock(id: String) {
        val v = synchronized(lock) {
            val v = _open.value[id] ?: return
            _open.value = _open.value - id
            v
        }
        runCatching { v.close() }
    }

    fun lockAll() {
        _open.value.keys.toList().forEach { lock(it) }
    }

    // ---- vault administration ---------------------------------------------------------------

    fun rename(id: String, newName: String) {
        val record = registry.get(id) ?: return
        registry.update(record.copy(name = newName.trim().ifEmpty { record.name }))
    }

    fun setAutoLock(id: String, seconds: Int) {
        val record = registry.get(id) ?: return
        registry.update(record.copy(autoLockSeconds = seconds))
    }

    suspend fun changePassword(id: String, old: CharArray, new: CharArray) = withContext(Dispatchers.IO) {
        val record = registry.get(id) ?: throw IOException("unknown vault")
        // A password change works on the masterkey file alone; an open session stays valid.
        CryptomatorVault.open(storageFor(record), old).use { it.changePassword(old, new) }
    }

    /** The 44 words, after the password has been checked. The caller shows them once. */
    suspend fun recoveryKey(id: String, password: CharArray): String = withContext(Dispatchers.IO) {
        val record = registry.get(id) ?: throw IOException("unknown vault")
        CryptomatorVault.open(storageFor(record), password).use { v ->
            val raw = v.rawKey()
            try {
                RecoveryKey.encode(raw)
            } finally {
                raw.fill(0)
            }
        }
    }

    /** @throws IllegalArgumentException when the words are not a recovery key for this vault. */
    suspend fun resetPassword(id: String, words: String, newPassword: CharArray) = withContext(Dispatchers.IO) {
        val record = registry.get(id) ?: throw IOException("unknown vault")
        val raw = RecoveryKey.decode(words) ?: throw IllegalArgumentException("not a valid recovery key")
        try {
            CryptomatorVault.resetPassword(storageFor(record), raw, newPassword)
        } finally {
            raw.fill(0)
        }
        lock(id)
    }

    /** Forgets the vault; with [deleteFiles], its ciphertext goes too. Remote backups are untouched. */
    suspend fun delete(id: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val record = registry.get(id) ?: return@withContext
        lock(id)
        if (deleteFiles) {
            runCatching { storageFor(record).deleteRecursively("") }
        }
        when (val loc = record.location) {
            is VaultLocation.Private -> if (deleteFiles) File(privateRoot, loc.dir).deleteRecursively()
            is VaultLocation.Saf -> runCatching {
                resolver.releasePersistableUriPermission(loc.treeUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        registry.remove(id)
    }

    /** Copies the ciphertext tree as-is into a picked folder: the manual way to a desktop. */
    suspend fun exportVaultFolder(id: String, targetTree: Uri, progress: ((Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val record = registry.get(id) ?: throw IOException("unknown vault")
        val src = storageFor(record)
        val dst = SafVaultStorage(resolver, targetTree)
        var count = 0
        fun copyDir(path: String) {
            for (e in src.list(path)) {
                val child = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.isDirectory) {
                    dst.createDirectory(child)
                    copyDir(child)
                } else {
                    src.readChannel(child).use { input ->
                        dst.writeChannel(child).use { out ->
                            val buf = ByteBuffer.allocate(256 * 1024)
                            while (input.read(buf) >= 0) {
                                buf.flip()
                                while (buf.hasRemaining()) out.write(buf)
                                buf.clear()
                            }
                        }
                    }
                    progress?.invoke(++count)
                }
            }
        }
        copyDir("")
    }

    /** A human-readable label for a SAF location, e.g. the provider's display of the folder. */
    fun locationLabel(record: VaultRecord): String = when (val loc = record.location) {
        is VaultLocation.Private -> ""
        is VaultLocation.Saf -> runCatching {
            val uri = loc.treeUri.toUri()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.substringAfter(':').ifEmpty { docId }
        }.getOrDefault(loc.treeUri)
    }
}
