package info.piepgras.cryptvault.backup

import android.content.Context
import info.piepgras.cryptvault.items.Iso8601
import info.piepgras.cryptvault.vault.BackupConfig
import info.piepgras.cryptvault.vault.PathVaultStorage
import info.piepgras.cryptvault.vault.VaultLocation
import info.piepgras.cryptvault.vault.VaultRecord
import info.piepgras.cryptvault.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The restore wizard's back end (docs/VAULT_LAYOUT.md §6.3): a remote vault folder becomes a
 * **new** private vault; nothing existing is ever overwritten. The password is checked against
 * the downloaded meta files before anything else is fetched.
 */
class RestoreService(
    private val context: Context,
    private val registry: VaultRegistry,
    private val backup: BackupService,
    private val privateRoot: File,
) {
    /** A restore in progress: the new vault directory and what the remote holds. */
    class Session internal constructor(
        val target: BackupTarget,
        val folder: String,
        val newId: String,
        internal val dir: File,
        internal val remote: RemoteStore,
        internal val runner: RestoreRunner,
        val snapshotKey: ByteArray,
        val snapshots: List<SnapshotInfo>,
    ) {
        val vaultName: String get() = snapshots.firstOrNull()?.snapshot?.vaultName ?: folder
    }

    /** Steps 1–3: meta files, password check, snapshot list. Throws WrongPasswordException etc. */
    suspend fun begin(target: BackupTarget, folder: String, password: CharArray): Session = withContext(Dispatchers.IO) {
        val newId = UUID.randomUUID().toString()
        val dir = File(privateRoot, newId).apply { mkdirs() }
        val remote = backup.storeFor(target, folder)
        val runner = RestoreRunner(remote, PathVaultStorage(dir.toPath()))
        val raw = try {
            runner.fetchMetaAndUnlock(password)
        } catch (e: Exception) {
            dir.deleteRecursively(); throw e
        }
        val key = try { SnapshotCrypto.deriveKey(raw) } finally { raw.fill(0) }
        val snapshots = runner.listSnapshots(key)
        if (snapshots.isEmpty()) { dir.deleteRecursively(); throw java.io.IOException("no restorable snapshot in this folder") }
        Session(target, folder, newId, dir, remote, runner, key, snapshots)
    }

    /** Step 4: downloads [snapshot] and registers the new vault; returns its record. */
    suspend fun restore(session: Session, snapshot: SnapshotInfo, isCancelled: () -> Boolean = { false }, onProgress: (BackupProgress) -> Unit = {}): VaultRecord = withContext(Dispatchers.IO) {
        session.runner.restore(snapshot.snapshot, session.snapshots.first().snapshot, isCancelled, onProgress)
        val name = context.getString(info.piepgras.cryptvault.R.string.restore_vault_name, session.vaultName, Iso8601.format(System.currentTimeMillis()).take(10))
        val record = VaultRecord(session.newId, name, VaultLocation.Private(session.newId), Iso8601.format(System.currentTimeMillis()))
        registry.add(record)
        backup.snapshotKeys.storeDerived(session.newId, session.snapshotKey)
        record
    }

    /** Throws the half-restored directory away. */
    fun abandon(session: Session) {
        session.snapshotKey.fill(0)
        session.dir.deleteRecursively()
    }

    /**
     * "Make this device the writer": the restored vault backs up into the same remote folder
     * from now on; the next run adopts the remote commit point (take-over).
     */
    fun adoptAsWriter(record: VaultRecord, session: Session, keep: Int, unmeteredOnly: Boolean) {
        registry.update(record.copy(backup = BackupConfig(session.target.id, session.folder, Retention.clampKeep(keep), unmeteredOnly, Iso8601.format(System.currentTimeMillis()))))
        backup.enqueueManual(record.id, takeOver = true)
    }
}
