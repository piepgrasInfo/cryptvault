package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.VaultStorage
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * A [RemoteStore] over a [VaultStorage]: the "any folder" target (a Storage Access Framework
 * tree through `SafVaultStorage`) and, in tests and debug builds, a plain directory. No ETags,
 * so no conditional writes: the last writer wins, which the brief accepts for this target
 * (BUILD_BRIEF.md §6.1).
 */
class StorageRemoteStore(private val storage: VaultStorage) : RemoteStore {
    override val caps = RemoteCaps(conditionalWrite = false, serverSideMove = true, rangeGet = false)

    override fun list(dir: String): List<RemoteEntry> {
        if (dir.isNotEmpty() && storage.stat(dir)?.isDirectory != true) return emptyList()
        return storage.list(dir).map { RemoteEntry(it.name, it.isDirectory, it.size) }
    }

    override fun stat(path: String): RemoteEntry? = storage.stat(path)?.let { RemoteEntry(it.name, it.isDirectory, it.size) }

    override fun mkdirs(dir: String) { if (dir.isNotEmpty()) storage.createDirectory(dir) }

    override fun upload(path: String, size: Long, ifMatch: String?, source: () -> InputStream): RemoteEntry {
        val parent = RemotePaths.parent(path)
        if (parent.isNotEmpty()) storage.createDirectory(parent)
        val tmp = "$path.uploading"
        var written = 0L
        source().use { input ->
            storage.writeChannel(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) out.write(bb)
                    written += n
                }
            }
        }
        if (written != size) { runCatching { storage.delete(tmp) }; throw IOException("wrote $written of $size bytes: $path") }
        storage.move(tmp, path)
        return RemoteEntry(RemotePaths.name(path), false, size)
    }

    override fun download(path: String, offset: Long): InputStream {
        val ch = storage.readChannel(path)
        if (offset > 0) ch.position(offset)
        return Channels.newInputStream(ch)
    }

    override fun delete(path: String, ifMatch: String?) {
        val e = storage.stat(path) ?: return
        if (e.isDirectory) storage.deleteRecursively(path) else storage.delete(path)
    }

    override fun move(from: String, to: String) {
        val parent = RemotePaths.parent(to)
        if (parent.isNotEmpty()) storage.createDirectory(parent)
        storage.move(from, to)
    }
}
