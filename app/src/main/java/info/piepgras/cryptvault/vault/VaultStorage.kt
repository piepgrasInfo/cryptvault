package info.piepgras.cryptvault.vault

import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.streams.asSequence

/** One entry of a ciphertext directory listing. */
data class StorageEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * The bytes under a vault: a directory tree of ciphertext files. Paths are relative to the
 * vault root, '/'-separated, "" being the root itself. Two implementations exist — the app's
 * private directory (java.nio) and a user-chosen Storage Access Framework tree — and
 * [CryptomatorVault] never knows which one it is talking to.
 *
 * Every method throws [IOException] on failure; nothing here retries.
 */
interface VaultStorage {
    fun list(dir: String): List<StorageEntry>
    fun stat(path: String): StorageEntry?
    fun readChannel(path: String): SeekableByteChannel
    /** Creates or truncates. The parent directory must exist. */
    fun writeChannel(path: String): WritableByteChannel
    /** Creates the directory and any missing parents; no error if it already exists. */
    fun createDirectory(path: String)
    /** Deletes a file or an empty directory. */
    fun delete(path: String)
    fun deleteRecursively(path: String)
    /** Renames or moves within this storage, replacing an existing file at [to]. */
    fun move(from: String, to: String)
}

/** [VaultStorage] over a java.nio directory: the app's private vault directory, or a temp dir in tests. */
class PathVaultStorage(val root: Path) : VaultStorage {

    private fun resolve(path: String): Path {
        if (path.isEmpty()) return root
        require(!path.startsWith("/") && !path.split('/').contains("..")) { "bad relative path: $path" }
        return root.resolve(path)
    }

    override fun list(dir: String): List<StorageEntry> {
        val p = resolve(dir)
        if (!Files.isDirectory(p)) throw IOException("not a directory: $dir")
        return Files.list(p).use { stream ->
            stream.asSequence().map { child ->
                val isDir = Files.isDirectory(child)
                StorageEntry(child.fileName.toString(), isDir, if (isDir) 0 else Files.size(child))
            }.toList()
        }
    }

    override fun stat(path: String): StorageEntry? {
        val p = resolve(path)
        if (!Files.exists(p)) return null
        val isDir = Files.isDirectory(p)
        return StorageEntry(p.fileName?.toString() ?: "", isDir, if (isDir) 0 else Files.size(p))
    }

    override fun readChannel(path: String): SeekableByteChannel =
        Files.newByteChannel(resolve(path), StandardOpenOption.READ)

    override fun writeChannel(path: String): WritableByteChannel =
        Files.newByteChannel(
            resolve(path),
            StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )

    override fun createDirectory(path: String) {
        Files.createDirectories(resolve(path))
    }

    override fun delete(path: String) {
        Files.delete(resolve(path))
    }

    override fun deleteRecursively(path: String) {
        val p = resolve(path)
        if (!Files.exists(p)) return
        Files.walk(p).use { stream ->
            stream.asSequence().toList().asReversed().forEach { Files.delete(it) }
        }
    }

    override fun move(from: String, to: String) {
        val src = resolve(from)
        val dst = resolve(to)
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
