package info.piepgras.cryptvault.items

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.EntryKind
import info.piepgras.cryptvault.vault.VaultEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.security.MessageDigest
import java.util.UUID

/** What the browser shows for one folder. */
data class FolderListing(val path: String, val folders: List<Folder>, val items: List<Item>)

/**
 * An unlocked vault as the app sees it: the item index (`.cryptvault/manifest.json`) on top of
 * the cleartext tree of a [CryptomatorVault]. Every mutation goes through [commit], which
 * writes the manifest atomically (temp → `.bak` ← current ← temp) and bumps its generation.
 * No Android class is referenced, so the whole thing is unit-tested on a temp directory.
 *
 * Thread-safety: every public method takes [lock]; long copies happen inside it, so callers run
 * imports on an IO dispatcher and the UI reads [manifest] as a flow.
 */
class OpenVault(
    /** The registry id; the manifest's `vaultId` wins once one exists (see [initialize]). */
    val registryId: String,
    private val vault: CryptomatorVault,
    private val thumbnails: ThumbnailMaker? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {

    companion object {
        /** Files above this are imported without a cleartext hash (docs/VAULT_LAYOUT.md §3.1). */
        const val HASH_LIMIT: Long = 64L * 1024 * 1024
        private const val COPY_BUFFER = 256 * 1024
    }

    private val lock = Any()
    private val _manifest = MutableStateFlow(Manifest(vaultId = registryId))
    val manifest: StateFlow<Manifest> get() = _manifest
    val vaultId: String get() = _manifest.value.vaultId

    /** Cleartext folder path → directory id; "" → root. Rebuilt by the tree walk. */
    private val dirIds = HashMap<String, String>().apply { put("", CryptomatorVault.ROOT_DIR_ID) }
    private var metaDirId: String? = null
    private var thumbsDirId: String? = null

    // ---- unlock -----------------------------------------------------------------------------

    /**
     * Reads the manifest (or its backup, or starts a fresh one), walks the tree and reconciles
     * the two. Called once right after the vault is opened; returns what changed.
     */
    fun initialize(): Reconcile.Result = synchronized(lock) {
        val tree = ArrayList<TreeNode>()
        dirIds.clear()
        dirIds[""] = CryptomatorVault.ROOT_DIR_ID
        metaDirId = null
        thumbsDirId = null
        walk("", CryptomatorVault.ROOT_DIR_ID, tree)
        val loaded = readManifest()
        val result = Reconcile.apply(loaded, tree, newId, clock())
        _manifest.value = result.manifest
        if (result.changed || loaded.generation == 0L && loaded.updatedAt.isEmpty()) writeManifest(result.manifest)
        result
    }

    private fun walk(path: String, dirId: String, out: MutableList<TreeNode>) {
        for (e in vault.list(dirId)) {
            val childPath = Names.join(path, e.name)
            when (e.kind) {
                EntryKind.DIRECTORY -> {
                    if (path.isEmpty() && e.name == Manifest.META_DIR) {
                        metaDirId = e.dirId
                        thumbsDirId = vault.list(e.dirId!!).firstOrNull { it.name == Manifest.THUMBS_DIR && it.kind == EntryKind.DIRECTORY }?.dirId
                        continue
                    }
                    dirIds[childPath] = e.dirId!!
                    out += TreeNode(childPath, true)
                    walk(childPath, e.dirId, out)
                }
                EntryKind.FILE -> out += TreeNode(childPath, false, e.size)
                EntryKind.SYMLINK -> Unit
            }
        }
    }

    private fun readManifest(): Manifest {
        val meta = metaDirId ?: return Manifest(vaultId = registryId)
        val entries = vault.list(meta)
        for (name in listOf(Manifest.FILE, Manifest.BACKUP_FILE)) {
            val entry = entries.firstOrNull { it.name == name && it.kind == EntryKind.FILE } ?: continue
            val text = runCatching { readText(entry) }.getOrNull() ?: continue
            val decoded = Manifest.decode(text) ?: continue
            return decoded
        }
        return Manifest(vaultId = registryId)
    }

    private fun ensureMetaDirs() {
        if (metaDirId == null) {
            metaDirId = vault.list(CryptomatorVault.ROOT_DIR_ID)
                .firstOrNull { it.name == Manifest.META_DIR && it.kind == EntryKind.DIRECTORY }?.dirId
                ?: vault.createDirectory(CryptomatorVault.ROOT_DIR_ID, Manifest.META_DIR)
        }
        if (thumbsDirId == null) {
            thumbsDirId = vault.list(metaDirId!!)
                .firstOrNull { it.name == Manifest.THUMBS_DIR && it.kind == EntryKind.DIRECTORY }?.dirId
                ?: vault.createDirectory(metaDirId!!, Manifest.THUMBS_DIR)
        }
    }

    private fun writeManifest(m: Manifest) {
        ensureMetaDirs()
        val meta = metaDirId!!
        val text = Manifest.encode(m)
        vault.writeFile(meta, Manifest.TEMP_FILE).use { it.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))) }
        val entries = vault.list(meta)
        entries.firstOrNull { it.name == Manifest.BACKUP_FILE }?.let { vault.delete(it) }
        entries.firstOrNull { it.name == Manifest.FILE }?.let { vault.move(it, meta, Manifest.BACKUP_FILE) }
        val tmp = vault.list(meta).first { it.name == Manifest.TEMP_FILE }
        vault.move(tmp, meta, Manifest.FILE)
    }

    /** Applies [change] to the manifest, bumps generation and time, writes it, publishes it. */
    private fun commit(change: (Manifest) -> Manifest): Manifest {
        val next = change(_manifest.value).let { it.copy(generation = it.generation + 1, updatedAt = Iso8601.format(clock())) }
        writeManifest(next)
        _manifest.value = next
        return next
    }

    // ---- reading ----------------------------------------------------------------------------

    fun listing(folderPath: String): FolderListing = synchronized(lock) {
        val m = _manifest.value
        FolderListing(
            path = folderPath,
            folders = m.folders.filter { it.parent == folderPath && it.path != folderPath }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            items = m.items.filter { it.folder == folderPath },
        )
    }

    fun item(id: String): Item? = _manifest.value.item(id)

    fun folderExists(path: String): Boolean = path.isEmpty() || dirIds.containsKey(path)

    private fun dirIdOf(folderPath: String): String =
        dirIds[folderPath] ?: throw IOException("no such folder: '$folderPath'")

    private fun entryOf(path: String): VaultEntry {
        val dirId = dirIdOf(Names.parent(path))
        val name = Names.last(path)
        return vault.list(dirId).firstOrNull { it.name == name } ?: throw IOException("'$path' is not in the vault")
    }

    private fun readText(entry: VaultEntry): String =
        Channels.newInputStream(vault.readFile(entry)).use { String(it.readBytes(), Charsets.UTF_8) }

    /** A decrypting stream of the item, authenticated chunk by chunk. */
    fun open(item: Item): InputStream = synchronized(lock) { Channels.newInputStream(vault.readFile(entryOf(item.path))) }

    fun readChannel(item: Item): ReadableByteChannel = synchronized(lock) { vault.readFile(entryOf(item.path)) }

    fun openRandomAccess(item: Item): CryptomatorVault.RandomAccessReader = synchronized(lock) { vault.openRandomAccess(entryOf(item.path)) }

    fun writeTo(item: Item, out: OutputStream, progress: ((Long) -> Unit)? = null) {
        open(item).use { input -> copy(input, out, progress) }
    }

    fun readNote(item: Item): String = open(item).use { String(it.readBytes(), Charsets.UTF_8) }

    fun thumbnail(item: Item): ByteArray? = synchronized(lock) {
        val path = item.thumb ?: return null
        val thumbs = thumbsDirId ?: return null
        val entry = vault.list(thumbs).firstOrNull { it.name == Names.last(path) } ?: return null
        runCatching { Channels.newInputStream(vault.readFile(entry)).use { it.readBytes() } }.getOrNull()
    }

    // ---- importing and notes ----------------------------------------------------------------

    /**
     * Imports [source] as a new file item in [folderPath]. The name is sanitised and made unique;
     * the cleartext is hashed while it streams when [expectedSize] allows; a thumbnail is made
     * when the type has one. A failure mid-copy removes the partial file.
     */
    fun importFile(
        folderPath: String,
        wantedName: String,
        mime: String?,
        expectedSize: Long,
        source: InputStream,
        progress: ((Long) -> Unit)? = null,
    ): Item = synchronized(lock) {
        val dirId = dirIdOf(folderPath)
        val name = uniqueName(folderPath, Names.sanitize(wantedName))
        val path = Names.join(folderPath, name)
        val hash = if (expectedSize in 0..HASH_LIMIT) MessageDigest.getInstance("SHA-256") else null
        var written = 0L
        try {
            Channels.newOutputStream(vault.writeFile(dirId, name)).use { out ->
                written = copy(source, out, progress, hash)
            }
        } catch (e: Exception) {
            runCatching { vault.list(dirId).firstOrNull { it.name == name }?.let { vault.delete(it) } }
            throw e
        }
        val sha = if (hash != null && written <= HASH_LIMIT) hash.digest().toHex() else null
        val stamp = Iso8601.format(clock())
        val item = Item(
            id = newId(),
            path = path,
            kind = if (Names.isNoteName(name)) ItemKind.NOTE else ItemKind.FILE,
            title = "",
            mime = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: Names.mimeFromName(name),
            size = written,
            createdAt = stamp,
            modifiedAt = stamp,
            sha256 = sha,
        )
        val withThumb = item.copy(thumb = makeThumbnail(item))
        commit { m -> m.copy(items = m.items + withThumb) }
        withThumb
    }

    /** Creates a Markdown note item named after its title. */
    fun createNote(folderPath: String, title: String, body: String): Item = synchronized(lock) {
        val dirId = dirIdOf(folderPath)
        val cleanTitle = Names.sanitize(title, "Note")
        val name = uniqueName(folderPath, cleanTitle.removeSuffix(Names.NOTE_EXTENSION) + Names.NOTE_EXTENSION)
        val bytes = body.toByteArray(Charsets.UTF_8)
        vault.writeFile(dirId, name).use { it.write(ByteBuffer.wrap(bytes)) }
        val stamp = Iso8601.format(clock())
        val item = Item(
            id = newId(), path = Names.join(folderPath, name), kind = ItemKind.NOTE, title = cleanTitle,
            mime = "text/markdown", size = bytes.size.toLong(), createdAt = stamp, modifiedAt = stamp,
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        )
        commit { m -> m.copy(items = m.items + item) }
        item
    }

    /** Replaces an item's bytes (a note edit, or a save-back from an external editor). */
    fun replaceContent(item: Item, source: InputStream, expectedSize: Long): Item = synchronized(lock) {
        val current = _manifest.value.item(item.id) ?: throw IOException("item is gone")
        val dirId = dirIdOf(current.folder)
        val hash = if (expectedSize in 0..HASH_LIMIT) MessageDigest.getInstance("SHA-256") else null
        var written = 0L
        Channels.newOutputStream(vault.writeFile(dirId, current.name)).use { out -> written = copy(source, out, null, hash) }
        val updated = current.copy(
            size = written,
            modifiedAt = Iso8601.format(clock()),
            sha256 = if (hash != null && written <= HASH_LIMIT) hash.digest().toHex() else null,
        )
        val withThumb = updated.copy(thumb = makeThumbnail(updated) ?: updated.thumb?.also { deleteThumb(it) }?.let { null })
        commit { m -> m.copy(items = m.items.map { if (it.id == item.id) withThumb else it }) }
        withThumb
    }

    fun updateNote(item: Item, body: String): Item {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return replaceContent(item, bytes.inputStream(), bytes.size.toLong())
    }

    fun updateMeta(itemId: String, title: String? = null, tags: List<String>? = null, note: String? = null): Item = synchronized(lock) {
        var updated: Item? = null
        commit { m ->
            m.copy(items = m.items.map {
                if (it.id != itemId) it else it.copy(
                    title = title ?: it.title,
                    tags = tags?.map { t -> t.trim() }?.filter { t -> t.isNotEmpty() }?.distinct() ?: it.tags,
                    note = note ?: it.note,
                    modifiedAt = Iso8601.format(clock()),
                ).also { u -> updated = u }
            })
        }
        updated ?: throw IOException("item is gone")
    }

    // ---- rename, move, delete ---------------------------------------------------------------

    fun rename(item: Item, newName: String): Item = synchronized(lock) {
        val current = _manifest.value.item(item.id) ?: throw IOException("item is gone")
        var name = Names.sanitize(newName)
        if (current.kind == ItemKind.NOTE && !Names.isNoteName(name)) name += Names.NOTE_EXTENSION
        if (name == current.name) return current
        if (nameTaken(current.folder, name)) throw IOException("'$name' already exists")
        vault.move(entryOf(current.path), dirIdOf(current.folder), name)
        val updated = current.copy(path = Names.join(current.folder, name), modifiedAt = Iso8601.format(clock()))
        commit { m -> m.copy(items = m.items.map { if (it.id == item.id) updated else it }) }
        updated
    }

    fun move(items: List<Item>, toFolder: String): List<Item> = synchronized(lock) {
        val targetDir = dirIdOf(toFolder)
        val moved = ArrayList<Item>()
        for (item in items) {
            val current = _manifest.value.item(item.id) ?: continue
            if (current.folder == toFolder) continue
            val name = uniqueName(toFolder, current.name)
            vault.move(entryOf(current.path), targetDir, name)
            val updated = current.copy(path = Names.join(toFolder, name))
            moved += updated
            commit { m -> m.copy(items = m.items.map { if (it.id == item.id) updated else it }) }
        }
        moved
    }

    fun delete(items: List<Item>) = synchronized(lock) {
        val ids = HashSet<String>()
        for (item in items) {
            val current = _manifest.value.item(item.id) ?: continue
            runCatching { vault.delete(entryOf(current.path)) }
            current.thumb?.let { deleteThumb(it) }
            ids += current.id
        }
        if (ids.isNotEmpty()) commit { m -> m.copy(items = m.items.filter { it.id !in ids }) }
    }

    fun createFolder(parentPath: String, name: String): Folder = synchronized(lock) {
        val parentId = dirIdOf(parentPath)
        val clean = Names.sanitize(name, "Folder")
        if (parentPath.isEmpty() && clean == Manifest.META_DIR) throw IOException("that name is reserved")
        if (nameTaken(parentPath, clean)) throw IOException("'$clean' already exists")
        val id = vault.createDirectory(parentId, clean)
        val path = Names.join(parentPath, clean)
        dirIds[path] = id
        val folder = Folder(path)
        commit { m -> m.copy(folders = (m.folders + folder).sortedBy { it.path }) }
        folder
    }

    /** Ensures `Notes/` (or any folder path) exists, creating each missing level. */
    fun ensureFolder(path: String): String = synchronized(lock) {
        if (path.isEmpty()) return ""
        if (!dirIds.containsKey(path)) {
            val parent = Names.parent(path)
            ensureFolder(parent)
            createFolder(parent, Names.last(path))
        }
        path
    }

    fun renameFolder(folder: Folder, newName: String): Folder = moveFolder(folder, folder.parent, newName)

    fun moveFolder(folder: Folder, toParent: String, newName: String = folder.name): Folder = synchronized(lock) {
        if (folder.path == toParent || Names.isWithin(toParent, folder.path) && toParent != folder.parent) throw IOException("cannot move a folder into itself")
        val clean = Names.sanitize(newName, "Folder")
        val newPath = Names.join(toParent, clean)
        if (newPath == folder.path) return folder
        val targetDir = dirIdOf(toParent)
        if (nameTaken(toParent, clean)) throw IOException("'$clean' already exists")
        vault.move(entryOf(folder.path), targetDir, clean)
        val affected = dirIds.keys.filter { Names.isWithin(it, folder.path) }
        for (old in affected) {
            val id = dirIds.remove(old)!!
            dirIds[Names.rebase(old, folder.path, newPath)] = id
        }
        val stamp = Iso8601.format(clock())
        val renamed = Folder(newPath, folder.tags)
        commit { m ->
            m.copy(
                folders = m.folders.map { f -> if (Names.isWithin(f.path, folder.path)) f.copy(path = Names.rebase(f.path, folder.path, newPath)) else f }.sortedBy { it.path },
                items = m.items.map { i -> if (Names.isWithin(i.path, folder.path)) i.copy(path = Names.rebase(i.path, folder.path, newPath), modifiedAt = stamp) else i },
            )
        }
        renamed
    }

    fun deleteFolder(folder: Folder) = synchronized(lock) {
        val m = _manifest.value
        for (item in m.items.filter { Names.isWithin(it.path, folder.path) }) item.thumb?.let { deleteThumb(it) }
        vault.delete(entryOf(folder.path))
        dirIds.keys.filter { Names.isWithin(it, folder.path) }.forEach { dirIds.remove(it) }
        commit { cur ->
            cur.copy(
                folders = cur.folders.filter { !Names.isWithin(it.path, folder.path) },
                items = cur.items.filter { !Names.isWithin(it.path, folder.path) },
            )
        }
    }

    fun updateFolderTags(folder: Folder, tags: List<String>): Folder = synchronized(lock) {
        val updated = folder.copy(tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        commit { m -> m.copy(folders = m.folders.map { if (it.path == folder.path) updated else it }) }
        updated
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun nameTaken(folderPath: String, name: String): Boolean {
        val m = _manifest.value
        return m.items.any { it.folder == folderPath && it.name.equals(name, ignoreCase = true) } ||
            m.folders.any { it.parent == folderPath && it.name.equals(name, ignoreCase = true) } ||
            runCatching { vault.exists(dirIdOf(folderPath), name) }.getOrDefault(false)
    }

    private fun uniqueName(folderPath: String, wanted: String): String {
        val m = _manifest.value
        val taken = HashSet<String>()
        m.items.filter { it.folder == folderPath }.forEach { taken += it.name; taken += it.name.lowercase() }
        m.folders.filter { it.parent == folderPath }.forEach { taken += it.name; taken += it.name.lowercase() }
        var candidate = Names.unique(wanted, taken)
        // The tree may hold a file the manifest does not know yet (desktop edits since unlock).
        var n = 1
        while (runCatching { vault.exists(dirIdOf(folderPath), candidate) }.getOrDefault(false)) {
            taken += candidate
            candidate = Names.unique(wanted, taken)
            if (++n > 1000) throw IOException("cannot find a free name for '$wanted'")
        }
        return candidate
    }

    private fun makeThumbnail(item: Item): String? {
        val maker = thumbnails ?: return null
        val bytes = runCatching {
            maker.make(item.mime, item.name, item.size, { open(item) }, { openRandomAccess(item) })
        }.getOrNull() ?: return null
        ensureMetaDirs()
        val name = "${item.id}.jpg"
        vault.writeFile(thumbsDirId!!, name).use { it.write(ByteBuffer.wrap(bytes)) }
        return "${Manifest.META_DIR}/${Manifest.THUMBS_DIR}/$name"
    }

    private fun deleteThumb(thumbPath: String) {
        val thumbs = thumbsDirId ?: return
        runCatching { vault.list(thumbs).firstOrNull { it.name == Names.last(thumbPath) }?.let { vault.delete(it) } }
    }

    private fun copy(input: InputStream, out: OutputStream, progress: ((Long) -> Unit)?, hash: MessageDigest? = null): Long {
        val buf = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            hash?.update(buf, 0, n)
            total += n
            progress?.invoke(total)
        }
        out.flush()
        return total
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override fun close() {
        synchronized(lock) { vault.close() }
    }
}
