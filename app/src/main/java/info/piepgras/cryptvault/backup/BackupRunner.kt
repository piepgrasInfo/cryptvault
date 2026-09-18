package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.VaultStorage
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/** Progress of a run, for the notification and the settings screen. */
data class BackupProgress(val step: Int, val steps: Int, val bytesDone: Long, val bytesTotal: Long, val currentPath: String? = null)

sealed interface BackupOutcome {
    /** Committed snapshot [seq]; [gcError] when garbage collection failed after the commit. */
    data class Done(val seq: Long, val uploadedBytes: Long, val steps: Int, val gcError: String? = null) : BackupOutcome
    /** Nothing changed since the last snapshot; no run was written. */
    data class NothingToDo(val seq: Long) : BackupOutcome
    /** Another installation wrote here (docs/VAULT_LAYOUT.md §6.2 step 1); nothing was changed. */
    data class Foreign(val remoteSeq: Long, val lastOwnSeq: Long) : BackupOutcome
}

class BackupCancelledException : IOException("backup cancelled")

/**
 * One backup run of one vault against one target — the executor of docs/VAULT_LAYOUT.md §6.2.
 * Works on the ciphertext alone (the vault may be locked); the snapshot manifest is encrypted
 * with the derived [snapshotKey]. Every remote step is idempotent and journaled, so a run the
 * process did not survive is completed by the next one; the commit point `latest` is written
 * last, conditionally on the version read at the start.
 */
class BackupRunner(
    private val storage: VaultStorage,
    private val remote: RemoteStore,
    private val index: BackupIndex,
    private val snapshotKey: ByteArray,
    private val vaultId: String,
    private val vaultName: String,
    private val installation: String,
    private val keep: Int = Retention.DEFAULT_KEEP,
    private val manifestGeneration: () -> Long = { 0 },
    private val now: () -> String = { Instant.now().toString() },
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * [takeOver]: accept a foreign commit point as the new baseline (the user chose "Take over");
     * the next snapshot then supersedes it and this installation becomes the writer.
     */
    fun run(
        takeOver: Boolean = false,
        isCancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupOutcome {
        val startedAt = now()
        try {
            val outcome = runInner(takeOver, isCancelled, onProgress)
            val s = index.state
            index.saveState(s.copy(lastRunAt = startedAt, lastError = null))
            return outcome
        } catch (e: Exception) {
            index.saveState(index.state.copy(lastRunAt = startedAt, lastError = e.message ?: e.javaClass.simpleName))
            throw e
        } finally {
            runCatching { index.hashes.save() }
        }
    }

    private fun runInner(takeOver: Boolean, isCancelled: () -> Boolean, onProgress: (BackupProgress) -> Unit): BackupOutcome {
        remote.mkdirs(RemoteLayout.SIDECAR)
        val latestEntry = remote.stat(RemoteLayout.LATEST)
        val latest = latestEntry?.let { readLatest() }
        var lastOwnSeq = index.state.lastOwnSeq

        when (val check = checkWriter(latest, lastOwnSeq)) {
            is WriterCheck.Foreign -> {
                if (!takeOver) return BackupOutcome.Foreign(check.remoteSeq, check.lastOwnSeq)
                // Adopt the foreign snapshot as our baseline.
                val adopted = downloadSnapshot(check.remoteSeq)
                index.saveSnapshot(adopted)
                index.clearJournal()
                lastOwnSeq = check.remoteSeq
                index.saveState(index.state.copy(lastOwnSeq = lastOwnSeq))
            }
            is WriterCheck.FirstBackup -> if (lastOwnSeq != 0L) {
                // The remote folder was emptied (or is a new target): start over.
                index.clearJournal()
                lastOwnSeq = 0
                index.saveState(index.state.copy(lastOwnSeq = 0))
            }
            is WriterCheck.Ours -> Unit
        }

        val last: Snapshot? = if (lastOwnSeq == 0L) null else index.snapshot(lastOwnSeq) ?: runCatching { downloadSnapshot(lastOwnSeq) }.getOrNull()
        val local = LocalScan.scan(storage, index.hashes, manifestGeneration())
        val plan = plan(local, last)
        val journal = index.journal()?.takeIf { it.seq == plan.seq }
        if (plan.isEmpty && last != null && journal == null) return BackupOutcome.NothingToDo(last.seq)

        // Execute the steps, journaling each completed one.
        var done = journal?.done?.toMutableSet() ?: HashSet()
        val total = plan.uploadBytes
        var bytes = 0L
        var uploaded = 0L
        for ((i, step) in plan.steps.withIndex()) {
            if (isCancelled()) throw BackupCancelledException()
            onProgress(BackupProgress(i, plan.steps.size, bytes, total, (step as? Step.Upload)?.path))
            if (step.key in done) {
                if (step is Step.Upload) bytes += step.size
                continue
            }
            when (step) {
                is Step.Retire -> retire(step)
                is Step.Upload -> { uploadVerified(step.path, step.size, step.sha256); bytes += step.size; uploaded += step.size }
                is Step.Revive -> revive(step)
                is Step.UploadMeta -> { uploadVerified(step.name, step.size, step.sha256); bytes += step.size; uploaded += step.size }
            }
            done += step.key
            index.saveJournal(BackupIndex.Journal(plan.seq, done))
        }
        onProgress(BackupProgress(plan.steps.size, plan.steps.size, bytes, total))

        // Snapshot, then the commit point.
        val snapshot = snapshotFor(plan, local, vaultId, vaultName, installation, now())
        val blob = SnapshotCrypto.encrypt(snapshotKey, snapshot.encode().toByteArray(Charsets.UTF_8), random)
        remote.upload(RemoteLayout.snapshotPath(snapshot.seq), blob.size.toLong()) { blob.inputStream() }
        val latestText = Latest(snapshot.seq, SnapshotCrypto.sha256Hex(blob)).format().toByteArray(Charsets.UTF_8)
        val ifMatch = if (remote.caps.conditionalWrite) (latestEntry?.etag ?: "*") else null
        try {
            remote.upload(RemoteLayout.LATEST, latestText.size.toLong(), ifMatch) { latestText.inputStream() }
        } catch (e: RemoteConflictException) {
            val theirs = runCatching { readLatest() }.getOrNull()
            return BackupOutcome.Foreign(theirs?.seq ?: -1, lastOwnSeq)
        }
        index.saveSnapshot(snapshot)
        index.clearJournal()
        index.saveState(index.state.copy(lastOwnSeq = snapshot.seq, lastSnapshotAt = snapshot.createdAt, lastUploadBytes = uploaded))

        // Garbage collection, only after a successful commit.
        val gcError = try { collectGarbage(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
        return BackupOutcome.Done(snapshot.seq, uploaded, plan.steps.size, gcError)
    }

    private fun readLatest(): Latest = Latest.parse(remote.download(RemoteLayout.LATEST).use { it.readBytes() }.toString(Charsets.UTF_8))

    private fun downloadSnapshot(seq: Long): Snapshot {
        val blob = remote.download(RemoteLayout.snapshotPath(seq)).use { it.readBytes() }
        return Snapshot.decode(SnapshotCrypto.decrypt(snapshotKey, blob).toString(Charsets.UTF_8))
    }

    private fun retire(step: Step.Retire) {
        val dest = RemoteLayout.versionPath(step.sha256)
        val mirror = remote.stat(step.path)
        if (remote.stat(dest) != null) {
            if (mirror != null) remote.delete(step.path)
        } else if (mirror != null) {
            remote.mkdirs(RemoteLayout.VERSIONS)
            remote.move(step.path, dest)
        }
        // Neither exists: the bytes are gone from the remote (a tampered or partial folder); the
        // current file is uploaded by its own step, older snapshots lose this version.
    }

    private fun revive(step: Step.Revive) {
        val src = RemoteLayout.versionPath(step.sha256)
        if (remote.stat(src) != null) {
            remote.mkdirs(RemotePaths.parent(step.path))
            remote.move(src, step.path)
        } else {
            val mirror = remote.stat(step.path)
            if (mirror == null || mirror.size != step.size) uploadVerified(step.path, step.size, step.sha256)
        }
    }

    /** Streams the local file up, hashing on the way; a hash mismatch means it changed under us. */
    private fun uploadVerified(path: String, size: Long, sha256: String) {
        val md = MessageDigest.getInstance("SHA-256")
        val entry = remote.upload(path, size) {
            md.reset()
            DigestInputStream(Channels.newInputStream(storage.readChannel(path)), md)
        }
        val actual = md.digest().toHex()
        if (actual != sha256) throw IOException("file changed during upload: $path")
        if (entry.size != size) throw IOException("remote reports ${entry.size} bytes for a $size-byte upload: $path")
    }

    private fun collectGarbage() {
        val present = remote.list(RemoteLayout.SNAPSHOTS).mapNotNull { RemoteLayout.snapshotSeq(it.name) }
        val versions = remote.list(RemoteLayout.VERSIONS).mapNotNull { RemoteLayout.versionSha(it.name) }
        val keepSeqs = Retention.retainedSeqs(present, keep)
        val retained = keepSeqs.map { seq -> index.snapshot(seq) ?: downloadSnapshot(seq).also { index.saveSnapshot(it) } }
        val gc = Retention.gc(present, retained, keep, versions)
        for (seq in gc.deleteSnapshots) remote.delete(RemoteLayout.snapshotPath(seq))
        for (sha in gc.deleteVersions) remote.delete(RemoteLayout.versionPath(sha))
        index.pruneSnapshots(keepSeqs.toSet())
    }
}

/** Reads a whole remote file into memory; for the small sidecar files only. */
internal fun RemoteStore.readAll(path: String): ByteArray = download(path).use(InputStream::readBytes)
