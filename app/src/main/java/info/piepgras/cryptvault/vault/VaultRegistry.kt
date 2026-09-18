package info.piepgras.cryptvault.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a vault's ciphertext lives. */
@Serializable
sealed class VaultLocation {
    /** `filesDir/vaults/<dir>/`, invisible to other apps. */
    @Serializable
    @SerialName("private")
    data class Private(val dir: String) : VaultLocation()

    /** A Storage Access Framework tree the user picked. */
    @Serializable
    @SerialName("saf")
    data class Saf(val treeUri: String) : VaultLocation()
}

/** A vault's backup setting (BUILD_BRIEF.md §6): where it goes and what the last run said. */
@Serializable
data class BackupConfig(
    /** The connected target (`BackupTargetStore`). */
    val targetId: String,
    /** The vault folder inside the target's CryptVault area, `<slug>-<id8>`. */
    val folder: String,
    val keep: Int = 5,
    val unmeteredOnly: Boolean = true,
    val enabledAt: String,
    // status, copied from the index after every run so the list can show it without I/O
    val lastSeq: Long = 0,
    val lastSnapshotAt: String? = null,
    val lastRunAt: String? = null,
    val lastError: String? = null,
    /** Another installation wrote the remote last; the user must choose (restore / take over). */
    val foreignSeq: Long? = null,
    val failures: Int = 0,
)

/** One vault as the app knows it before unlocking (docs/VAULT_LAYOUT.md §4). */
@Serializable
data class VaultRecord(
    val id: String,
    val name: String,
    val location: VaultLocation,
    val createdAt: String,
    val biometric: Boolean = false,
    /** Seconds in the background before the vault locks; 0 = immediately. */
    val autoLockSeconds: Int = DEFAULT_AUTO_LOCK_SECONDS,
    val lastUnlockedAt: String? = null,
    val backup: BackupConfig? = null,
) {
    companion object {
        const val DEFAULT_AUTO_LOCK_SECONDS = 60
        val AUTO_LOCK_OPTIONS = listOf(30, 60, 300, 900, 3600)
    }
}

@Serializable
private data class RegistryFile(val schema: Int = 1, val vaults: List<VaultRecord> = emptyList())

/**
 * `vaults.json` in the app's private directory: the list of vaults and their settings. Never
 * backed up (the manifest excludes everything), never uploaded. Written atomically.
 */
class VaultRegistry(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; classDiscriminator = "type" }
    private val _records = MutableStateFlow<List<VaultRecord>>(emptyList())
    val records: StateFlow<List<VaultRecord>> get() = _records
    private val lock = Any()

    init {
        load()
    }

    private fun load() {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString(RegistryFile.serializer(), file.readText()).vaults else emptyList()
        }.getOrElse { emptyList() }
        _records.value = loaded
    }

    fun get(id: String): VaultRecord? = _records.value.firstOrNull { it.id == id }

    fun add(record: VaultRecord) = mutate { it + record }

    fun update(record: VaultRecord) = mutate { list -> list.map { if (it.id == record.id) record else it } }

    fun remove(id: String) = mutate { list -> list.filter { it.id != id } }

    /** Swaps a record's id (the manifest's vaultId wins over the registry's, once known). */
    fun reidentify(oldId: String, newId: String) = mutate { list -> list.map { if (it.id == oldId) it.copy(id = newId) else it } }

    private fun mutate(change: (List<VaultRecord>) -> List<VaultRecord>) {
        synchronized(lock) {
            val next = change(_records.value)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(RegistryFile.serializer(), RegistryFile(vaults = next)))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("cannot write ${file.name}")
            }
            _records.value = next
        }
    }
}
