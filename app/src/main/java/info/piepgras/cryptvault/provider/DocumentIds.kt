package info.piepgras.cryptvault.provider

/**
 * Document ids of the vault DocumentsProvider: `<vaultId>:<cleartext path>`, the root document
 * of a vault being `<vaultId>:` (empty path). Vault ids are UUIDs, so the first colon is the
 * separator; cleartext paths may contain colons.
 */
data class DocumentId(val vaultId: String, val path: String) {
    val isRoot: Boolean get() = path.isEmpty()
    val name: String get() = path.substringAfterLast('/')
    val parentPath: String get() = path.substringBeforeLast('/', "")

    override fun toString(): String = "$vaultId:$path"

    fun child(name: String): DocumentId = DocumentId(vaultId, if (path.isEmpty()) name else "$path/$name")

    /** True when [other] is this document or lies below it. */
    fun contains(other: DocumentId): Boolean =
        other.vaultId == vaultId && (isRoot || other.path == path || other.path.startsWith("$path/"))

    companion object {
        fun parse(id: String): DocumentId {
            val i = id.indexOf(':')
            require(i > 0) { "not a document id: $id" }
            return DocumentId(id.substring(0, i), id.substring(i + 1))
        }

        fun root(vaultId: String): DocumentId = DocumentId(vaultId, "")
    }
}
