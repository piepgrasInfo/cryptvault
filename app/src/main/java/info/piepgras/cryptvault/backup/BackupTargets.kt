package info.piepgras.cryptvault.backup

import android.content.Context
import info.piepgras.cryptvault.security.AppKeystore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A connected backup target and its credential. Stored as one Keystore-encrypted JSON file,
 * `secrets/providers.enc` (docs/VAULT_LAYOUT.md §4); nothing here rests in plaintext.
 */
@Serializable
sealed class BackupTarget {
    abstract val id: String
    abstract val label: String
    abstract val kind: Kind

    @Serializable
    enum class Kind { WEBDAV, FOLDER, DROPBOX, ONEDRIVE, DRIVE }

    /** A WebDAV server: [url] is the user's files root, e.g. `…/remote.php/dav/files/<user>/`. */
    @Serializable
    @SerialName("webdav")
    data class WebDav(override val id: String, override val label: String, val url: String, val user: String, val password: String) : BackupTarget() {
        override val kind get() = Kind.WEBDAV
    }

    /** A folder the user picked with the system picker (a tree URI with persisted permission). */
    @Serializable
    @SerialName("folder")
    data class Folder(override val id: String, override val label: String, val treeUri: String) : BackupTarget() {
        override val kind get() = Kind.FOLDER
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** The registry of connected targets. Every read decrypts the file; it is tiny. */
class BackupTargetStore(context: Context) {
    private val file = File(File(context.filesDir, "secrets"), "providers.enc")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val lock = Any()

    @Serializable
    private data class TargetsFile(val schema: Int = 1, val targets: List<BackupTarget> = emptyList())

    fun all(): List<BackupTarget> = synchronized(lock) { load().targets }

    fun get(id: String): BackupTarget? = all().firstOrNull { it.id == id }

    fun put(target: BackupTarget) = synchronized(lock) {
        val current = load()
        save(current.copy(targets = current.targets.filter { it.id != target.id } + target))
    }

    fun remove(id: String) = synchronized(lock) {
        val current = load()
        save(current.copy(targets = current.targets.filter { it.id != id }))
    }

    private fun load(): TargetsFile {
        if (!file.exists()) return TargetsFile()
        return runCatching { json.decodeFromString(TargetsFile.serializer(), AppKeystore.decrypt(file.readBytes()).toString(Charsets.UTF_8)) }
            .getOrDefault(TargetsFile())
    }

    private fun save(t: TargetsFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(AppKeystore.encrypt(json.encodeToString(TargetsFile.serializer(), t).toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

/** The derived snapshot key of a vault, Keystore-wrapped so the worker can use it while the vault is locked. */
class SnapshotKeyStore(context: Context) {
    private val dir = File(context.filesDir, "secrets")
    private fun file(vaultId: String) = File(dir, "$vaultId.snapshotkey")

    fun has(vaultId: String): Boolean = file(vaultId).exists()

    fun store(vaultId: String, rawMasterkey: ByteArray) {
        val derived = SnapshotCrypto.deriveKey(rawMasterkey)
        try {
            dir.mkdirs()
            file(vaultId).writeBytes(AppKeystore.encrypt(derived))
        } finally {
            derived.fill(0)
        }
    }

    /** Stores an already-derived key (a restore has it before any masterkey is in memory). */
    fun storeDerived(vaultId: String, derived: ByteArray) {
        dir.mkdirs()
        file(vaultId).writeBytes(AppKeystore.encrypt(derived))
    }

    /** The 32-byte key, or null when backup was never set up for this vault. Zero it after use. */
    fun load(vaultId: String): ByteArray? {
        val f = file(vaultId)
        if (!f.exists()) return null
        return runCatching { AppKeystore.decrypt(f.readBytes()) }.getOrNull()
    }

    fun remove(vaultId: String) { file(vaultId).delete() }
}
