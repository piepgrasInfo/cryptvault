package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.VaultStorage
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Lists the ciphertext a backup mirrors — everything under `d/` plus the two meta files — with
 * sizes and SHA-256 hashes, re-hashing only what the [HashCache] does not know. Temp files of a
 * write in progress (`*.tmp`) are skipped; they are never part of a valid vault.
 */
object LocalScan {

    fun scan(storage: VaultStorage, cache: HashCache, manifestGeneration: Long = 0): LocalState {
        val files = ArrayList<LocalFile>()
        val seen = HashSet<String>()
        if (storage.stat(CryptomatorVault.DATA_DIR)?.isDirectory == true) {
            walk(storage, CryptomatorVault.DATA_DIR, cache, files, seen)
        }
        val meta = HashMap<String, LocalFile>()
        for (name in RemoteLayout.META_FILES) {
            val e = storage.stat(name) ?: continue
            if (e.isDirectory) continue
            meta[name] = hashed(storage, name, e.size, e.lastModified, cache)
            seen += name
        }
        cache.retainOnly(seen)
        files.sortBy { it.path }
        return LocalState(files, meta, manifestGeneration)
    }

    private fun walk(storage: VaultStorage, dir: String, cache: HashCache, out: MutableList<LocalFile>, seen: MutableSet<String>) {
        for (e in storage.list(dir)) {
            val path = "$dir/${e.name}"
            if (e.isDirectory) {
                walk(storage, path, cache, out, seen)
            } else {
                if (e.name.endsWith(CryptomatorVault.TEMP_SUFFIX)) continue
                out += hashed(storage, path, e.size, e.lastModified, cache)
                seen += path
            }
        }
    }

    private fun hashed(storage: VaultStorage, path: String, size: Long, mtime: Long, cache: HashCache): LocalFile {
        cache.lookup(path, size, mtime)?.let { return LocalFile(path, size, it) }
        val sha = sha256(storage, path)
        cache.put(path, size, mtime, sha)
        return LocalFile(path, size, sha)
    }

    fun sha256(storage: VaultStorage, path: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(256 * 1024)
        storage.readChannel(path).use { ch ->
            while (true) {
                buf.clear()
                val n = ch.read(buf)
                if (n < 0) break
                if (n == 0) throw IOException("read returned 0 bytes: $path")
                buf.flip()
                md.update(buf)
            }
        }
        return md.digest().toHex()
    }
}
