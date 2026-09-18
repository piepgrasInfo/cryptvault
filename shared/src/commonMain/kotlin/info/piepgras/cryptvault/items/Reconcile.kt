package info.piepgras.cryptvault.items

/** One node of the cleartext tree as walked at unlock: a file with its size, or a folder. */
data class TreeNode(val path: String, val isFolder: Boolean, val size: Long = 0)

/**
 * The tree is authoritative, the manifest is an index (docs/VAULT_LAYOUT.md §1): files that
 * exist without an entry get one (they may have been added on the desktop); entries whose
 * file is gone are dropped; sizes follow the tree. Runs on every unlock. Pure, so it is tested
 * on the JVM with hand-made trees.
 */
object Reconcile {

    data class Result(val manifest: Manifest, val changed: Boolean, val added: Int, val removed: Int)

    /**
     * @param tree every file and folder in the cleartext tree, excluding `.cryptvault/`
     * @param newId produces an id for each synthesised item
     * @param now for the timestamps of synthesised items
     */
    fun apply(manifest: Manifest, tree: List<TreeNode>, newId: () -> String, now: Long): Result {
        val filesByPath = tree.filter { !it.isFolder }.associateBy { it.path }
        val folderPaths = tree.filter { it.isFolder }.map { it.path }.toSet()
        val stamp = Iso8601.format(now)

        var changed = false
        var removed = 0
        val kept = ArrayList<Item>(manifest.items.size)
        for (item in manifest.items) {
            val node = filesByPath[item.path]
            if (node == null) {
                removed++
                changed = true
                continue
            }
            if (node.size != item.size && node.size >= 0) {
                kept += item.copy(size = node.size, modifiedAt = stamp, sha256 = null)
                changed = true
            } else {
                kept += item
            }
        }

        val known = kept.map { it.path }.toHashSet()
        var added = 0
        for (node in tree) {
            if (node.isFolder || node.path in known || node.size < 0) continue
            val name = Names.last(node.path)
            kept += Item(
                id = newId(),
                path = node.path,
                kind = if (Names.isNoteName(name)) ItemKind.NOTE else ItemKind.FILE,
                mime = Names.mimeFromName(name),
                size = node.size,
                createdAt = stamp,
                modifiedAt = stamp,
            )
            added++
            changed = true
        }

        val folders = ArrayList<Folder>()
        val knownFolders = manifest.folders.associateBy { it.path }
        for (path in folderPaths.sorted()) {
            folders += knownFolders[path] ?: Folder(path).also { changed = true }
        }
        if (knownFolders.keys.any { it !in folderPaths }) changed = true

        val result = if (changed) {
            manifest.copy(items = kept.sortedBy { it.path }, folders = folders, generation = manifest.generation + 1, updatedAt = stamp)
        } else {
            manifest
        }
        return Result(result, changed, added, removed)
    }
}
