package info.piepgras.cryptvault.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Folder
import info.piepgras.cryptvault.items.Iso8601
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.ItemQuery
import info.piepgras.cryptvault.items.Names
import info.piepgras.cryptvault.items.OpenVault
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The unlocked vaults as storage roots in the system file picker and the Files app
 * (BUILD_BRIEF.md §5.2). Locked vaults contribute no root. Reads are served through proxy
 * descriptors that decrypt chunk by chunk, so seeking works and no plaintext touches disk;
 * writes are whole-file: the other app writes into a pipe that streams straight into the vault
 * and the item is committed when the pipe closes. Random-access write modes are refused.
 *
 * The provider shows no UI of its own; every prompt happens in the app.
 */
class VaultDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".documents"
        private const val TAG = "CryptVault"

        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX
        fun rootsUri(context: Context): Uri = DocumentsContract.buildRootsUri(authority(context))

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES,
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
        private const val FOLDER_FLAGS = Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
        private const val FILE_FLAGS = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
    }

    private val container get() = CryptVaultApp.container(context!!)

    private fun vault(vaultId: String): OpenVault =
        container.repository.openVault(vaultId) ?: throw FileNotFoundException("the vault is locked")

    private fun notifyChildren(parent: DocumentId) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context!!), parent.toString()), null)
    }

    override fun onCreate(): Boolean = true

    // ---- roots and documents ----------------------------------------------------------------

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val ctx = context ?: return cursor
        for ((id, _) in container.repository.open.value) {
            val record = container.repository.record(id) ?: continue
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, id)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_TITLE, record.name)
                add(Root.COLUMN_SUMMARY, ctx.getString(R.string.app_name))
                add(Root.COLUMN_DOCUMENT_ID, DocumentId.root(id).toString())
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        val id = DocumentId.parse(documentId)
        val v = vault(id.vaultId)
        when {
            id.isRoot -> addFolderRow(cursor, id, container.repository.record(id.vaultId)?.name ?: "", root = true)
            else -> {
                val m = v.manifest.value
                m.itemAt(id.path)?.let { addItemRow(cursor, id.vaultId, it); return cursor }
                m.folders.firstOrNull { it.path == id.path }?.let { addFolderRow(cursor, id, it.name); return cursor }
                throw FileNotFoundException(id.path)
            }
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        val parent = DocumentId.parse(parentDocumentId)
        val v = vault(parent.vaultId)
        val listing = v.listing(parent.path)
        for (f in listing.folders) addFolderRow(cursor, DocumentId(parent.vaultId, f.path), f.name)
        for (item in ItemQuery.sort(listing.items, info.piepgras.cryptvault.items.SortKey.NAME)) addItemRow(cursor, parent.vaultId, item)
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context!!), parentDocumentId))
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        val v = vault(rootId)
        for (item in ItemQuery.filter(v.manifest.value.items, query)) addItemRow(cursor, rootId, item)
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        DocumentId.parse(parentDocumentId).contains(DocumentId.parse(documentId))

    override fun getDocumentType(documentId: String): String {
        val id = DocumentId.parse(documentId)
        if (id.isRoot) return Document.MIME_TYPE_DIR
        return vault(id.vaultId).manifest.value.itemAt(id.path)?.mime ?: Document.MIME_TYPE_DIR
    }

    private fun addFolderRow(cursor: MatrixCursor, id: DocumentId, name: String, root: Boolean = false) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id.toString())
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, if (root) Document.FLAG_DIR_SUPPORTS_CREATE else FOLDER_FLAGS)
            add(Document.COLUMN_SIZE, null)
        }
    }

    private fun addItemRow(cursor: MatrixCursor, vaultId: String, item: Item) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DocumentId(vaultId, item.path).toString())
            add(Document.COLUMN_MIME_TYPE, item.mime)
            add(Document.COLUMN_DISPLAY_NAME, item.name)
            add(Document.COLUMN_LAST_MODIFIED, Iso8601.parse(item.modifiedAt))
            add(Document.COLUMN_FLAGS, FILE_FLAGS or (if (item.thumb != null) Document.FLAG_SUPPORTS_THUMBNAIL else 0))
            add(Document.COLUMN_SIZE, item.size)
        }
    }

    // ---- bytes ------------------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val id = DocumentId.parse(documentId)
        val v = vault(id.vaultId)
        val item = v.manifest.value.itemAt(id.path) ?: throw FileNotFoundException(id.path)
        return when (mode) {
            "r" -> ProxyDescriptors.readOnly(context!!, v.openRandomAccess(item), id.vaultId)
            "w", "wt", "rwt" -> writePipe(v, id, item)
            else -> throw UnsupportedOperationException("mode $mode: the vault takes whole-file writes only")
        }
    }

    /**
     * A pipe whose read end streams into the vault; the item is replaced when the writer closes.
     * If the vault locks meanwhile the read end is closed and the writer gets EPIPE.
     */
    private fun writePipe(v: OpenVault, id: DocumentId, item: Item): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val closeable = AutoCloseable { readEnd.close() }
        ProxyDescriptors.trackWriter(id.vaultId, closeable)
        thread(name = "cryptvault-write-${item.id}") {
            try {
                FileInputStream(readEnd.fileDescriptor).use { input -> v.replaceContent(item, input, -1) }
                notifyChildren(DocumentId(id.vaultId, id.parentPath))
            } catch (e: Exception) {
                Log.w(TAG, "write through the provider failed", e)
                runCatching { readEnd.closeWithError(e.message ?: "write failed") }
            } finally {
                ProxyDescriptors.untrackWriter(id.vaultId, closeable)
                runCatching { readEnd.close() }
            }
        }
        return pipe[1]
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val id = DocumentId.parse(documentId)
        val v = vault(id.vaultId)
        val item = v.manifest.value.itemAt(id.path) ?: throw FileNotFoundException(id.path)
        val bytes = v.thumbnail(item) ?: throw FileNotFoundException("no thumbnail")
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "cryptvault-thumb") {
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(bytes) }
            } catch (e: IOException) {
                // the reader went away
            } finally {
                runCatching { pipe[1].close() }
            }
        }
        return AssetFileDescriptor(pipe[0], 0, bytes.size.toLong())
    }

    // ---- structure --------------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = DocumentId.parse(parentDocumentId)
        val v = vault(parent.vaultId)
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            v.createFolder(parent.path, displayName).path
        } else {
            v.importFile(parent.path, displayName, mimeType, 0, ByteArray(0).inputStream()).path
        }
        notifyChildren(parent)
        return DocumentId(parent.vaultId, created).toString()
    }

    override fun deleteDocument(documentId: String) {
        val id = DocumentId.parse(documentId)
        if (id.isRoot) throw UnsupportedOperationException("the vault root cannot be deleted here")
        val v = vault(id.vaultId)
        val m = v.manifest.value
        m.itemAt(id.path)?.let { v.delete(listOf(it)) }
            ?: m.folders.firstOrNull { it.path == id.path }?.let { v.deleteFolder(it) }
            ?: throw FileNotFoundException(id.path)
        notifyChildren(DocumentId(id.vaultId, id.parentPath))
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String {
        val id = DocumentId.parse(documentId)
        if (id.isRoot) throw UnsupportedOperationException("rename the vault in the app")
        val v = vault(id.vaultId)
        val m = v.manifest.value
        val newPath = m.itemAt(id.path)?.let { v.rename(it, displayName).path }
            ?: m.folders.firstOrNull { it.path == id.path }?.let { v.renameFolder(it, displayName).path }
            ?: throw FileNotFoundException(id.path)
        notifyChildren(DocumentId(id.vaultId, id.parentPath))
        return DocumentId(id.vaultId, newPath).toString()
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val src = DocumentId.parse(sourceDocumentId)
        val target = DocumentId.parse(targetParentDocumentId)
        if (src.vaultId != target.vaultId) throw UnsupportedOperationException("move between vaults is not supported")
        val v = vault(src.vaultId)
        val m = v.manifest.value
        val newPath = m.itemAt(src.path)?.let { v.move(listOf(it), target.path).firstOrNull()?.path ?: it.path }
            ?: m.folders.firstOrNull { it.path == src.path }?.let { v.moveFolder(it, target.path).path }
            ?: throw FileNotFoundException(src.path)
        notifyChildren(DocumentId(src.vaultId, src.parentPath))
        notifyChildren(target)
        return DocumentId(src.vaultId, newPath).toString()
    }

    @Suppress("unused")
    private fun folderOf(v: OpenVault, path: String): Folder? = v.manifest.value.folders.firstOrNull { it.path == path }

    @Suppress("unused")
    private fun nameOf(path: String) = Names.last(path)
}
