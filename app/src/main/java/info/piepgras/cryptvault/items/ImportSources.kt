package info.piepgras.cryptvault.items

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.InputStream

/** What is known about a content URI before it is imported. */
data class ImportSource(val uri: Uri, val name: String, val mime: String?, val size: Long) {
    val isMedia: Boolean get() = uri.authority == MediaStore.AUTHORITY
}

/**
 * Turns the URIs the pickers, the share sheet and the camera hand over into name, type, size
 * and a stream — and, for "move into vault", deletes the original where the source allows it.
 */
object ImportSources {

    fun describe(resolver: ContentResolver, uri: Uri): ImportSource {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        var finalName = Names.sanitize(name ?: uri.lastPathSegment?.substringAfterLast('/'), "file")
        if (Names.extension(finalName).isEmpty() && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { finalName = "$finalName.$it" }
        }
        return ImportSource(uri, finalName, mime, size)
    }

    fun open(resolver: ContentResolver, uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

    /** Whether "move into vault" can delete this source directly (SAF documents with the delete flag). */
    fun canDeleteDirectly(resolver: ContentResolver, uri: Uri): Boolean {
        if (!DocumentsContract.isDocumentUri(null, uri) && uri.authority != "com.android.externalstorage.documents") {
            if (!isDocumentUri(resolver, uri)) return false
        }
        return runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun isDocumentUri(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { resolver.getType(uri) != null && uri.pathSegments.firstOrNull() == "document" }.getOrDefault(false)

    fun deleteDirectly(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    /**
     * For photos and videos from the picker: the system's delete confirmation (API 30+). The
     * caller launches the returned intent sender; null when nothing can be built.
     */
    fun mediaDeleteRequest(resolver: ContentResolver, uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val media = uris.filter { it.authority == MediaStore.AUTHORITY }.mapNotNull { toMediaStoreUri(it) }
        if (media.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(resolver, media) }.getOrNull()
    }

    /** Photo-picker URIs are `content://media/picker/...`; the delete request needs the plain media URI. */
    private fun toMediaStoreUri(uri: Uri): Uri? {
        val segments = uri.pathSegments
        if (segments.size >= 4 && segments[0] == "picker") {
            val id = segments.last().toLongOrNull() ?: return null
            val kind = segments[2]
            val base = if (kind.contains("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            return ContentUris.withAppendedId(base, id)
        }
        return uri
    }
}
