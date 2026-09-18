package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.VaultStorage
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** A snapshot as the restore wizard lists it. */
data class SnapshotInfo(val seq: Long, val createdAt: String, val fileCount: Int, val totalSize: Long, val installation: String, val snapshot: Snapshot)

/**
 * The restore of docs/VAULT_LAYOUT.md §6.3, into a fresh local vault directory: meta files
 * first, the password checked locally against them, then the chosen snapshot's files, each
 * verified by hash on arrival. Resumable: a file already present with the right hash is skipped.
 */
class RestoreRunner(
    private val remote: RemoteStore,
    private val target: VaultStorage,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Step 1–2: fetch the meta files and open the vault with the password; returns the raw masterkey. */
    fun fetchMetaAndUnlock(password: CharArray): ByteArray {
        for (name in RemoteLayout.META_FILES) {
            if (remote.stat(name) == null) throw IOException("no $name in this folder — not a CryptVault backup")
            download(name, name, null)
        }
        val vault = CryptomatorVault.open(target, password, random)
        return try { vault.rawKey() } finally { vault.close() }
    }

    /** Step 3: the retained snapshots, newest first. */
    fun listSnapshots(snapshotKey: ByteArray): List<SnapshotInfo> {
        val seqs = remote.list(RemoteLayout.SNAPSHOTS).mapNotNull { RemoteLayout.snapshotSeq(it.name) }.sortedDescending()
        return seqs.mapNotNull { seq ->
            runCatching {
                val blob = remote.readAll(RemoteLayout.snapshotPath(seq))
                Snapshot.decode(SnapshotCrypto.decrypt(snapshotKey, blob).toString(Charsets.UTF_8))
            }.getOrNull()?.let { SnapshotInfo(it.seq, it.createdAt, it.files.size, it.totalSize, it.installation, it) }
        }
    }

    /** Step 4: download every file of [snapshot]; [newest] locates bytes still in the mirror. */
    fun restore(
        snapshot: Snapshot,
        newest: Snapshot,
        isCancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ) {
        val index = Locate.mirrorIndex(newest)
        val total = snapshot.files.sumOf { it.size }
        var done = 0L
        for ((i, file) in snapshot.files.withIndex()) {
            if (isCancelled()) throw BackupCancelledException()
            onProgress(BackupProgress(i, snapshot.files.size, done, total, file.path))
            val existing = target.stat(file.path)
            if (existing != null && !existing.isDirectory && existing.size == file.size && LocalScan.sha256(target, file.path) == file.sha256) {
                done += file.size; continue
            }
            // An interrupted later run may already have moved these bytes into versions/ (or not
            // yet): try every place they can legitimately be.
            val candidates = listOf(Locate.remotePath(file, index), RemoteLayout.versionPath(file.sha256), file.path).distinct()
            val source = candidates.firstOrNull { remote.stat(it)?.isDirectory == false }
                ?: throw IOException("bytes of ${file.path} are missing on the remote")
            download(source, file.path, file.sha256)
            done += file.size
        }
        onProgress(BackupProgress(snapshot.files.size, snapshot.files.size, done, total))
    }

    private fun download(remotePath: String, localPath: String, expectedSha: String?) {
        val parent = localPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) target.createDirectory(parent)
        val tmp = localPath + CryptomatorVault.TEMP_SUFFIX
        val md = MessageDigest.getInstance("SHA-256")
        remote.download(remotePath).use { input ->
            target.writeChannel(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) out.write(bb)
                }
            }
        }
        val actual = md.digest().toHex()
        if (expectedSha != null && actual != expectedSha) {
            runCatching { target.delete(tmp) }
            throw IOException("hash mismatch downloading $remotePath")
        }
        target.move(tmp, localPath)
    }
}
