package info.piepgras.cryptvault.vault

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * [VaultStorage] over a Storage Access Framework tree (a folder the user picked: SD card, local
 * folder, or a cloud app's folder). Talks to [DocumentsContract] directly rather than through
 * `DocumentFile`, which issues one query per child; a listing here is one query.
 *
 * Document ids are cached per relative path and dropped whenever a path is renamed, moved or
 * deleted — providers such as the external-storage one derive ids from paths, so a rename
 * changes the ids of everything below it.
 *
 * Known limits (BUILD_BRIEF.md §2.1, docs/VAULT_LAYOUT.md §7): no atomic rename-over, so
 * [move] onto an existing file deletes the target first; a provider may hand back a pipe
 * instead of a seekable descriptor, in which case random access fails with an IOException.
 */
class SafVaultStorage(private val resolver: ContentResolver, val treeUri: Uri) : VaultStorage {

    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val ids = HashMap<String, String>().apply { put("", rootId) }
    private val lock = Any()

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    private fun childrenUri(docId: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

    private data class Child(val id: String, val name: String, val isDirectory: Boolean, val size: Long)

    private fun children(docId: String): List<Child> {
        val out = ArrayList<Child>()
        val cursor: Cursor = resolver.query(childrenUri(docId), PROJECTION, null, null, null)
            ?: throw IOException("provider returned no listing")
        cursor.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: ""
                out += Child(c.getString(0), c.getString(1) ?: continue, mime == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
        return out
    }

    /** Resolves a relative path to a document id, or null if any segment is missing. */
    private fun resolve(path: String): String? = synchronized(lock) {
        if (path.isEmpty()) return rootId
        require(!path.startsWith("/") && !path.split('/').contains("..")) { "bad relative path: $path" }
        ids[path]?.let { return it }
        var parentPath = ""
        var parentId = rootId
        val segments = path.split('/')
        for ((i, seg) in segments.withIndex()) {
            val childPath = if (parentPath.isEmpty()) seg else "$parentPath/$seg"
            val known = ids[childPath]
            val id = known ?: run {
                val found = children(parentId).firstOrNull { it.name == seg } ?: return null
                for (ch in children(parentId)) ids[if (parentPath.isEmpty()) ch.name else "$parentPath/${ch.name}"] = ch.id
                found.id
            }
            if (i == segments.lastIndex) return id
            parentPath = childPath
            parentId = id
        }
        return null
    }

    private fun forget(pathPrefix: String) = synchronized(lock) {
        ids.keys.filter { it == pathPrefix || it.startsWith("$pathPrefix/") }.forEach { ids.remove(it) }
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    private fun nameOf(path: String): String = path.substringAfterLast('/')

    override fun list(dir: String): List<StorageEntry> {
        val id = resolve(dir) ?: throw IOException("not a directory: $dir")
        val kids = children(id)
        synchronized(lock) { for (k in kids) ids[if (dir.isEmpty()) k.name else "$dir/${k.name}"] = k.id }
        return kids.map { StorageEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.size) }
    }

    override fun stat(path: String): StorageEntry? {
        val id = resolve(path) ?: return null
        val cursor = try {
            resolver.query(docUri(id), PROJECTION, null, null, null)
        } catch (e: Exception) {
            null
        } ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val mime = c.getString(2) ?: ""
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            return StorageEntry(c.getString(1) ?: nameOf(path), isDir, if (isDir || c.isNull(3)) 0 else c.getLong(3))
        }
    }

    override fun readChannel(path: String): SeekableByteChannel {
        val id = resolve(path) ?: throw IOException("no such file: $path")
        val pfd = resolver.openFileDescriptor(docUri(id), "r") ?: throw IOException("cannot open $path")
        return PfdReadChannel(pfd)
    }

    override fun writeChannel(path: String): WritableByteChannel {
        val existing = resolve(path)
        val id = existing ?: run {
            val parent = resolve(parentOf(path)) ?: throw IOException("parent of $path does not exist")
            val name = nameOf(path)
            val created = DocumentsContract.createDocument(resolver, docUri(parent), "application/octet-stream", name)
                ?: throw IOException("provider refused to create $path")
            val newId = DocumentsContract.getDocumentId(created)
            synchronized(lock) { ids[path] = newId }
            newId
        }
        val pfd = resolver.openFileDescriptor(docUri(id), if (existing != null) "wt" else "w")
            ?: throw IOException("cannot open $path for writing")
        return PfdWriteChannel(pfd)
    }

    override fun createDirectory(path: String) {
        if (path.isEmpty() || resolve(path) != null) return
        val parentPath = parentOf(path)
        createDirectory(parentPath)
        val parent = resolve(parentPath) ?: throw IOException("cannot create $path")
        val created = DocumentsContract.createDocument(resolver, docUri(parent), DocumentsContract.Document.MIME_TYPE_DIR, nameOf(path))
            ?: throw IOException("provider refused to create directory $path")
        synchronized(lock) { ids[path] = DocumentsContract.getDocumentId(created) }
    }

    override fun delete(path: String) {
        val id = resolve(path) ?: throw IOException("no such entry: $path")
        if (!DocumentsContract.deleteDocument(resolver, docUri(id))) throw IOException("provider refused to delete $path")
        forget(path)
    }

    override fun deleteRecursively(path: String) {
        val id = resolve(path) ?: return
        // SAF deletes a directory with everything in it.
        if (!DocumentsContract.deleteDocument(resolver, docUri(id))) throw IOException("provider refused to delete $path")
        forget(path)
    }

    override fun move(from: String, to: String) {
        val srcId = resolve(from) ?: throw IOException("no such entry: $from")
        val fromParent = parentOf(from)
        val toParent = parentOf(to)
        resolve(to)?.let { existing ->
            DocumentsContract.deleteDocument(resolver, docUri(existing))
            forget(to)
        }
        var currentId = srcId
        if (fromParent != toParent) {
            val srcParentId = resolve(fromParent) ?: throw IOException("no such directory: $fromParent")
            val dstParentId = resolve(toParent) ?: throw IOException("no such directory: $toParent")
            val moved = try {
                DocumentsContract.moveDocument(resolver, docUri(srcId), docUri(srcParentId), docUri(dstParentId))
            } catch (e: Exception) {
                null
            }
            if (moved == null) {
                copyThenDelete(from, to)
                return
            }
            currentId = DocumentsContract.getDocumentId(moved)
        }
        if (nameOf(from) != nameOf(to)) {
            val renamed = DocumentsContract.renameDocument(resolver, docUri(currentId), nameOf(to))
                ?: throw IOException("provider refused to rename $from")
            currentId = DocumentsContract.getDocumentId(renamed)
        }
        forget(from)
        forget(to)
        synchronized(lock) { ids[to] = currentId }
    }

    /** For providers without move support: files are streamed, directories recursed. */
    private fun copyThenDelete(from: String, to: String) {
        val entry = stat(from) ?: throw IOException("no such entry: $from")
        if (entry.isDirectory) {
            createDirectory(to)
            for (child in list(from)) copyThenDelete("$from/${child.name}", "$to/${child.name}")
        } else {
            readChannel(from).use { input ->
                writeChannel(to).use { out ->
                    val buf = ByteBuffer.allocate(256 * 1024)
                    while (input.read(buf) >= 0) {
                        buf.flip()
                        while (buf.hasRemaining()) out.write(buf)
                        buf.clear()
                    }
                }
            }
        }
        deleteRecursively(from)
    }

    private class PfdReadChannel(private val pfd: ParcelFileDescriptor) : SeekableByteChannel {
        private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel
        override fun read(dst: ByteBuffer): Int = channel.read(dst)
        override fun write(src: ByteBuffer): Int = throw IOException("read-only")
        override fun position(): Long = channel.position()
        override fun position(newPosition: Long): SeekableByteChannel {
            channel.position(newPosition)
            return this
        }
        override fun size(): Long = channel.size()
        override fun truncate(size: Long): SeekableByteChannel = throw IOException("read-only")
        override fun isOpen(): Boolean = channel.isOpen
        override fun close() {
            try {
                channel.close()
            } finally {
                pfd.close()
            }
        }
    }

    private class PfdWriteChannel(private val pfd: ParcelFileDescriptor) : WritableByteChannel {
        private val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel
        override fun write(src: ByteBuffer): Int = channel.write(src)
        override fun isOpen(): Boolean = channel.isOpen
        override fun close() {
            try {
                runCatching { pfd.fileDescriptor.sync() }
                channel.close()
            } finally {
                pfd.close()
            }
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        /**
         * The self-test of BUILD_BRIEF.md §2.1: create, write, rename, delete a small file in
         * the tree. Returns null on success or the failure's message.
         */
        fun selfTest(resolver: ContentResolver, treeUri: Uri): String? = try {
            val s = SafVaultStorage(resolver, treeUri)
            val name = ".cryptvault-selftest-${System.nanoTime()}"
            s.writeChannel(name).use { it.write(ByteBuffer.wrap(ByteArray(1024) { 0x2A })) }
            val entry = s.stat(name) ?: throw IOException("the file was not there after writing it")
            if (entry.size != 1024L) throw IOException("the provider reports ${entry.size} bytes for a 1024-byte file")
            s.readChannel(name).use { ch ->
                val buf = ByteBuffer.allocate(1024)
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
                ch.position(512)
            }
            s.move(name, "$name.renamed")
            if (s.stat(name) != null || s.stat("$name.renamed") == null) throw IOException("rename did not take effect")
            s.delete("$name.renamed")
            if (s.stat("$name.renamed") != null) throw IOException("delete did not take effect")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
