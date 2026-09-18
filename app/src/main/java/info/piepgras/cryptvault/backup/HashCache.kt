package info.piepgras.cryptvault.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `index/<vaultId>.json`: ciphertext path → (size, mtime, SHA-256), so a run re-hashes only what
 * changed (docs/VAULT_LAYOUT.md §4). Purely a performance cache — delete it and the next run
 * rebuilds it. Holds no cleartext names.
 */
class HashCache(private val file: File) {

    @Serializable
    data class Entry(val size: Long, val mtime: Long, val sha256: String)

    @Serializable
    private data class CacheFile(val schema: Int = 1, val entries: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries: MutableMap<String, Entry> = HashMap()
    private var dirty = false

    init {
        if (file.exists()) {
            runCatching { json.decodeFromString(CacheFile.serializer(), file.readText()) }
                .onSuccess { entries.putAll(it.entries) }
        }
    }

    /** The cached hash when size and mtime still match, else null. */
    fun lookup(path: String, size: Long, mtime: Long): String? =
        entries[path]?.takeIf { it.size == size && it.mtime == mtime }?.sha256

    fun put(path: String, size: Long, mtime: Long, sha256: String) {
        entries[path] = Entry(size, mtime, sha256); dirty = true
    }

    /** Drops entries for paths no longer present. */
    fun retainOnly(paths: Set<String>) {
        if (entries.keys.retainAll(paths)) dirty = true
    }

    fun save() {
        if (!dirty) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(CacheFile.serializer(), CacheFile(entries = entries.toMap())))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        dirty = false
    }

    val size: Int get() = entries.size
}
