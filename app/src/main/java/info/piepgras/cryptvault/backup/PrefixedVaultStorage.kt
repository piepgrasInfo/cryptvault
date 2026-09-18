package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.StorageEntry
import info.piepgras.cryptvault.vault.VaultStorage
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel

/** A [VaultStorage] rooted at a subdirectory of another one: `CryptVault/<vault folder>/` inside a picked tree. */
class PrefixedVaultStorage(private val inner: VaultStorage, prefix: String) : VaultStorage {
    private val prefix = prefix.trim('/')
    private fun map(path: String): String = if (path.isEmpty()) prefix else if (prefix.isEmpty()) path else "$prefix/$path"

    override fun list(dir: String): List<StorageEntry> = inner.list(map(dir))
    override fun stat(path: String): StorageEntry? = inner.stat(map(path))
    override fun readChannel(path: String): SeekableByteChannel = inner.readChannel(map(path))
    override fun writeChannel(path: String): WritableByteChannel = inner.writeChannel(map(path))
    override fun createDirectory(path: String) = inner.createDirectory(map(path))
    override fun delete(path: String) = inner.delete(map(path))
    override fun deleteRecursively(path: String) = inner.deleteRecursively(map(path))
    override fun move(from: String, to: String) = inner.move(map(from), map(to))
}
