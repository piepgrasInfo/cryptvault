package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.items.Names
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The snapshot manifest a backup run writes to `cryptvault/snapshots/<seq>.json.enc`
 * (docs/VAULT_LAYOUT.md §6.1). It lists the vault's ciphertext files — paths relative to the
 * vault folder, sizes and SHA-256 of the ciphertext — never a cleartext name or content.
 */
@Serializable
data class Snapshot(
    val schema: Int = SCHEMA,
    val seq: Long,
    val vaultId: String,
    val vaultName: String,
    val createdAt: String,
    /** Random per-installation id: identifies the writer, not the person. */
    val installation: String,
    val manifestGeneration: Long = 0,
    /** `vault.cryptomator` and `masterkey.cryptomator`. */
    val meta: Map<String, SnapshotMeta> = emptyMap(),
    val files: List<SnapshotFile> = emptyList(),
) {
    val totalSize: Long get() = files.sumOf { it.size } + meta.values.sumOf { it.size }

    companion object {
        const val SCHEMA = 1
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
        fun decode(text: String): Snapshot = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

@Serializable
data class SnapshotMeta(val size: Long, val sha256: String)

@Serializable
data class SnapshotFile(
    /** Ciphertext path relative to the vault folder, e.g. `d/AB/CDEF…/xyz.c9r`. */
    val path: String,
    val size: Long,
    val sha256: String,
    /** Where the bytes were when the snapshot was written. A restore locates content by hash (see [RemoteLayout]). */
    val stored: Stored = Stored.MIRROR,
)

@Serializable
enum class Stored {
    @SerialName("mirror") MIRROR,
    @SerialName("versions") VERSIONS,
}

/** The commit point `cryptvault/latest`: `"<seq>\n<sha256 of snapshots/<seq>.json.enc>\n"`. */
data class Latest(val seq: Long, val snapshotSha256: String) {
    fun format(): String = "$seq\n$snapshotSha256\n"

    companion object {
        fun parse(text: String): Latest {
            val lines = text.trim().lines()
            require(lines.size == 2) { "latest: expected two lines" }
            val seq = lines[0].trim().toLongOrNull() ?: throw IllegalArgumentException("latest: bad seq")
            val sha = lines[1].trim()
            require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "latest: bad hash" }
            return Latest(seq, sha)
        }
    }
}

/** Names inside a vault's remote folder (docs/VAULT_LAYOUT.md §6). */
object RemoteLayout {
    const val SIDECAR = "cryptvault"
    const val LATEST = "$SIDECAR/latest"
    const val SNAPSHOTS = "$SIDECAR/snapshots"
    const val VERSIONS = "$SIDECAR/versions"
    const val VAULT_CONFIG = "vault.cryptomator"
    const val MASTERKEY = "masterkey.cryptomator"
    val META_FILES = listOf(VAULT_CONFIG, MASTERKEY)

    fun snapshotPath(seq: Long): String = "$SNAPSHOTS/${seq.toString().padStart(8, '0')}.json.enc"
    fun versionPath(sha256: String): String = "$VERSIONS/$sha256.c9r"

    /** `00000012.json.enc` → 12, anything else → null. */
    fun snapshotSeq(name: String): Long? =
        if (name.endsWith(".json.enc")) name.removeSuffix(".json.enc").toLongOrNull() else null

    /** `<sha256>.c9r` → the hash, anything else → null. */
    fun versionSha(name: String): String? =
        if (name.endsWith(".c9r") && name.length == 68) name.removeSuffix(".c9r").takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' } } else null

    /** The vault's folder inside the target's app area: `<slug>-<first 8 hex of the id>`. */
    fun vaultFolderName(vaultName: String, vaultId: String): String = Names.slug(vaultName, vaultId)
}
