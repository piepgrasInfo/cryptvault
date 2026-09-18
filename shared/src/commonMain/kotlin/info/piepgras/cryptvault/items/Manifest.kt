package info.piepgras.cryptvault.items

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `.cryptvault/manifest.json` inside a vault (docs/VAULT_LAYOUT.md §3.1): the index of items
 * with their titles, tags and notes. The vault tree is authoritative; this is what the tree
 * cannot carry. Unknown keys are ignored so a newer app can add fields without breaking an
 * older one; [schema] is bumped only for incompatible changes.
 */
@Serializable
data class Manifest(
    val schema: Int = SCHEMA,
    val vaultId: String,
    val generation: Long = 0,
    val updatedAt: String = "",
    val items: List<Item> = emptyList(),
    val folders: List<Folder> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
        const val META_DIR = ".cryptvault"
        const val FILE = "manifest.json"
        const val BACKUP_FILE = "manifest.json.bak"
        const val TEMP_FILE = "manifest.json.tmp"
        const val THUMBS_DIR = "thumbs"
        const val DEFAULT_NOTES_FOLDER = "Notes"

        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        fun encode(manifest: Manifest): String = json.encodeToString(serializer(), manifest)

        /** Null when the text is not a manifest at all; a newer schema still decodes what it can. */
        fun decode(text: String): Manifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }

    fun item(id: String): Item? = items.firstOrNull { it.id == id }
    fun itemAt(path: String): Item? = items.firstOrNull { it.path == path }
}

@Serializable
enum class ItemKind {
    @SerialName("file") FILE,
    @SerialName("note") NOTE,
}

/** One item: a file in the cleartext tree plus what the manifest adds to it. */
@Serializable
data class Item(
    val id: String,
    /** Cleartext path relative to the vault root, '/'-separated, e.g. `Documents/passport.pdf`. */
    val path: String,
    val kind: ItemKind = ItemKind.FILE,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val note: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val createdAt: String = "",
    val modifiedAt: String = "",
    /** SHA-256 of the cleartext, hex; null when the file was too large to hash at import. */
    val sha256: String? = null,
    /** Vault path of the JPEG thumbnail, or null. */
    val thumb: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', "")
    val displayTitle: String get() = title.ifBlank { if (kind == ItemKind.NOTE) name.removeSuffix(".md") else name }
}

@Serializable
data class Folder(
    val path: String,
    val tags: List<String> = emptyList(),
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}
