package info.piepgras.cryptvault.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `index/<vaultId>/` in the app's private directory: everything a backup run remembers between
 * runs (docs/VAULT_LAYOUT.md §4). Never uploaded, never backed up by the platform; holds
 * ciphertext paths and hashes only.
 *
 * - `hashes.json` — the [HashCache]
 * - `state.json` — the last sequence number this installation committed, and when
 * - `journal.json` — the steps of an interrupted run that already completed (idempotent replay)
 * - `snapshots/<seq>.json` — plaintext copies of the manifests this installation wrote, so
 *   retention needs no downloads
 */
class BackupIndex(val dir: File) {

    @Serializable
    data class State(
        val lastOwnSeq: Long = 0,
        val lastSnapshotAt: String? = null,
        val lastRunAt: String? = null,
        val lastError: String? = null,
        val lastUploadBytes: Long = 0,
    )

    @Serializable
    data class Journal(val seq: Long, val done: Set<String> = emptySet())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val hashes: HashCache by lazy { HashCache(File(dir, "hashes.json")) }
    private val stateFile = File(dir, "state.json")
    private val journalFile = File(dir, "journal.json")
    private val snapshotsDir = File(dir, "snapshots")

    var state: State = if (stateFile.exists()) runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State()) else State()
        private set

    fun saveState(next: State) {
        state = next
        write(stateFile, json.encodeToString(State.serializer(), next))
    }

    fun journal(): Journal? = if (journalFile.exists()) runCatching { json.decodeFromString(Journal.serializer(), journalFile.readText()) }.getOrNull() else null
    fun saveJournal(j: Journal) = write(journalFile, json.encodeToString(Journal.serializer(), j))
    fun clearJournal() { journalFile.delete() }

    fun snapshot(seq: Long): Snapshot? {
        val f = File(snapshotsDir, "$seq.json")
        return if (f.exists()) runCatching { Snapshot.decode(f.readText()) }.getOrNull() else null
    }

    fun saveSnapshot(s: Snapshot) = write(File(snapshotsDir, "${s.seq}.json"), s.encode())

    fun pruneSnapshots(keep: Set<Long>) {
        snapshotsDir.listFiles()?.forEach { f ->
            val seq = f.name.removeSuffix(".json").toLongOrNull()
            if (seq != null && seq !in keep) f.delete()
        }
    }

    /** Forgets everything: after "Disconnect", or when the vault is deleted. */
    fun wipe() { dir.deleteRecursively() }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
