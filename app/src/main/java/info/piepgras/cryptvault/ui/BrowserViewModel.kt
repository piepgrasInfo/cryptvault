package info.piepgras.cryptvault.ui

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Folder
import info.piepgras.cryptvault.items.ImportSource
import info.piepgras.cryptvault.items.ImportSources
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.Names
import info.piepgras.cryptvault.items.OpenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/** A running import, export or hand-off, for the progress dialog. */
data class Progress(val labelRes: Int, val current: Int, val total: Int, val bytes: Long, val ofBytes: Long)

/** One-off things the screen has to do: show a message, launch an intent, ask a question. */
sealed class BrowserEvent {
    data class Message(val text: String) : BrowserEvent()
    /** A string resource, or a plural when [quantity] is given (the quantity is also the first argument). */
    data class MessageRes(val res: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) : BrowserEvent() {
        fun format(resources: android.content.res.Resources): String =
            if (quantity != null) resources.getQuantityString(res, quantity, *(listOf<Any>(quantity) + args).toTypedArray())
            else resources.getString(res, *args.toTypedArray())
    }
    data class Launch(val intent: Intent) : BrowserEvent()
    data class LaunchSender(val sender: IntentSender) : BrowserEvent()
    data class AskDeleteOriginals(val sources: List<ImportSource>) : BrowserEvent()
}

/**
 * State and actions for one vault's browser. One instance per vault (keyed by id); the folder
 * being shown is the screen's, since Back walks folders.
 */
class BrowserViewModel(private val container: CryptVaultApp.Container, val vaultId: String) : ViewModel() {

    private val repository = container.repository
    private val openWith = container.openWith
    private val resolver = container.resolver
    private val context get() = container.app

    val vault: OpenVault? get() = repository.openVault(vaultId)
    val open = repository.open
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val progress = MutableStateFlow<Progress?>(null)
    val events = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 8)
    private val thumbs = LruCache<String, ImageBitmap>(200)
    private var captureFile: File? = null
    private val random = SecureRandom()

    fun record() = repository.record(vaultId)

    // ---- thumbnails -------------------------------------------------------------------------

    suspend fun thumbnail(item: Item): ImageBitmap? {
        val key = item.thumb ?: return null
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = vault?.thumbnail(item) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { thumbs.put(key, it) }
        }
    }

    // ---- selection --------------------------------------------------------------------------

    fun toggle(id: String) {
        selection.value = if (id in selection.value) selection.value - id else selection.value + id
    }

    fun selectAll(ids: List<String>) {
        selection.value = ids.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    private fun selectedItems(): List<Item> {
        val v = vault ?: return emptyList()
        return selection.value.mapNotNull { v.item(it) }
    }

    // ---- importing --------------------------------------------------------------------------

    fun importUris(uris: List<Uri>, folder: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val v = vault ?: return@launch
            val sources = withContext(Dispatchers.IO) { uris.map { runCatching { ImportSources.describe(resolver, it) }.getOrNull() }.filterNotNull() }
            var imported = 0
            val failures = ArrayList<String>()
            for ((i, src) in sources.withIndex()) {
                progress.value = Progress(R.string.progress_importing, i + 1, sources.size, 0, src.size)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ImportSources.open(resolver, src.uri).use { input ->
                            v.importFile(folder, src.name, src.mime, src.size, input) { done ->
                                progress.value = Progress(R.string.progress_importing, i + 1, sources.size, done, src.size)
                            }
                        }
                    }
                }
                if (result.isSuccess) imported++ else {
                    failures += src.name
                    android.util.Log.w("CryptVault", "import of ${src.mime} (${src.size} bytes) failed", result.exceptionOrNull())
                }
            }
            progress.value = null
            if (failures.isEmpty()) events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_imported, quantity = imported))
            else events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_import_failed, listOf(failures.joinToString(", ")), quantity = imported))
            val deletable = withContext(Dispatchers.IO) {
                sources.filter { it.isMedia || ImportSources.canDeleteDirectly(context, it.uri) }
            }
            if (imported > 0 && deletable.isNotEmpty()) events.tryEmit(BrowserEvent.AskDeleteOriginals(deletable))
        }
    }

    /** "Move into vault": deletes the originals that were imported. */
    fun deleteOriginals(sources: List<ImportSource>) {
        viewModelScope.launch(Dispatchers.IO) {
            val media = sources.filter { it.isMedia }
            val documents = sources.filter { !it.isMedia }
            var deleted = 0
            for (d in documents) if (ImportSources.deleteDirectly(resolver, d.uri)) deleted++
            if (deleted > 0) events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_originals_deleted, quantity = deleted))
            if (media.isNotEmpty()) {
                val request = ImportSources.mediaDeleteRequest(resolver, media.map { it.uri })
                if (request != null) events.tryEmit(BrowserEvent.LaunchSender(request.intentSender))
                else events.tryEmit(BrowserEvent.MessageRes(R.string.msg_originals_not_deletable))
            }
        }
    }

    /** A FileProvider URI for the camera to write into; imported by [onCaptured]. */
    fun captureUri(): Uri {
        val dir = File(context.cacheDir, "capture/${random.nextLong().toULong().toString(16)}").also { it.mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        captureFile = file
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun onCaptured(ok: Boolean, folder: String) {
        val file = captureFile ?: return
        captureFile = null
        viewModelScope.launch {
            val v = vault
            if (ok && v != null && file.exists() && file.length() > 0) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { file.inputStream().use { v.importFile(folder, file.name, "image/jpeg", file.length(), it) } }
                }
                result.onSuccess { events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_imported, quantity = 1)) }
                    .onFailure { events.tryEmit(BrowserEvent.Message(it.message ?: it.javaClass.simpleName)) }
            }
            withContext(Dispatchers.IO) { file.parentFile?.deleteRecursively() }
        }
    }

    // ---- folders and items ------------------------------------------------------------------

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                events.tryEmit(BrowserEvent.Message(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun createFolder(parent: String, name: String) = io { vault?.createFolder(parent, name) }
    fun renameFolder(folder: Folder, name: String) = io { vault?.renameFolder(folder, name) }
    fun deleteFolder(folder: Folder) = io { vault?.deleteFolder(folder) }
    fun renameItem(item: Item, name: String) = io { vault?.rename(item, name) }

    fun moveSelected(toFolder: String) = io {
        val items = selectedItems()
        vault?.move(items, toFolder)
        selection.value = emptySet()
        events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_moved, quantity = items.size))
    }

    fun moveFolder(folder: Folder, toParent: String) = io { vault?.moveFolder(folder, toParent) }

    fun deleteSelected() = io {
        val items = selectedItems()
        vault?.delete(items)
        selection.value = emptySet()
        events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_deleted, quantity = items.size))
    }

    fun deleteItem(item: Item) = io { vault?.delete(listOf(item)) }

    /** All folders, for the move dialog. */
    fun folders(): List<Folder> = vault?.manifest?.value?.folders ?: emptyList()

    // ---- out of the vault -------------------------------------------------------------------

    fun openItem(item: Item, edit: Boolean) {
        viewModelScope.launch {
            val v = vault ?: return@launch
            progress.value = Progress(R.string.progress_preparing, 1, 1, 0, item.size)
            val file = withContext(Dispatchers.IO) {
                runCatching { openWith.prepare(v, item, edit) { done -> progress.value = Progress(R.string.progress_preparing, 1, 1, done, item.size) } }
            }
            progress.value = null
            file.onSuccess {
                // No resolveActivity() gate: package visibility makes it return null for apps we
                // may still start. The screen reports ActivityNotFoundException from the launch.
                events.tryEmit(BrowserEvent.Launch(Intent.createChooser(openWith.viewIntent(it, item.mime, edit), null)))
            }.onFailure { events.tryEmit(BrowserEvent.Message(it.message ?: it.javaClass.simpleName)) }
        }
    }

    fun selectedForShare(): List<Item> = selectedItems()

    fun estimateShare(items: List<Item>): Long = vault?.let { v -> container.share.estimateSize(v, items) } ?: 0L

    /** Writes an encrypted container and hands it to the share sheet with subject and body (BUILD_BRIEF.md §7). */
    fun shareEncrypted(items: List<Item>, kind: info.piepgras.cryptvault.share.ContainerKind, passphrase: CharArray) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val v = vault ?: return@launch
            progress.value = Progress(R.string.progress_preparing, 0, items.size, 0, 0)
            val bundle = withContext(Dispatchers.IO) { runCatching { container.share.build(v, items, kind, passphrase) } }
            passphrase.fill(' ')
            progress.value = null
            bundle.onSuccess { b ->
                events.tryEmit(BrowserEvent.Launch(Intent.createChooser(openWith.shareIntent(listOf(b.file), b.mime, b.subject, b.body), null)))
                selection.value = emptySet()
            }.onFailure { events.tryEmit(BrowserEvent.Message(it.message ?: it.javaClass.simpleName)) }
        }
    }

    fun shareSelected() = share(selectedItems())

    fun share(items: List<Item>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val v = vault ?: return@launch
            progress.value = Progress(R.string.progress_preparing, 0, items.size, 0, 0)
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    items.mapIndexed { i, item ->
                        progress.value = Progress(R.string.progress_preparing, i + 1, items.size, 0, 0)
                        openWith.prepareForShare(v, item)
                    }
                }
            }
            progress.value = null
            files.onSuccess {
                val mime = items.map { it.mime }.distinct().singleOrNull() ?: "*/*"
                events.tryEmit(BrowserEvent.Launch(Intent.createChooser(openWith.shareIntent(it, mime), null)))
                selection.value = emptySet()
            }.onFailure { events.tryEmit(BrowserEvent.Message(it.message ?: it.javaClass.simpleName)) }
        }
    }

    fun exportItem(item: Item, target: Uri) = io {
        val v = vault ?: return@io
        resolver.openOutputStream(target, "wt")?.use { out -> v.writeTo(item, out) }
            ?: throw java.io.IOException("cannot open target")
        events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_exported, quantity = 1))
    }

    fun exportSelected(tree: Uri) = io {
        val v = vault ?: return@io
        val items = selectedItems()
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        var n = 0
        for ((i, item) in items.withIndex()) {
            progress.value = Progress(R.string.progress_exporting, i + 1, items.size, 0, item.size)
            val doc = android.provider.DocumentsContract.createDocument(resolver, root, item.mime, item.name) ?: continue
            resolver.openOutputStream(doc, "wt")?.use { out -> v.writeTo(item, out) }
            n++
        }
        progress.value = null
        selection.value = emptySet()
        events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_exported, quantity = n))
    }

    /** On resume: files handed to editors that changed go back into the vault. */
    fun checkSaveBacks() = io {
        val v = vault ?: return@io
        val changed = openWith.changed().filter { it.vaultId == vaultId }
        var saved = 0
        for (h in changed) {
            runCatching { openWith.saveBack(v, h) }.onSuccess { if (it != null) saved++ }
        }
        openWith.wipeStale()
        if (saved > 0) events.tryEmit(BrowserEvent.MessageRes(R.plurals.msg_saved_back, quantity = saved))
    }

    fun lock() = container.lockManager.lock(vaultId)

    fun suggestedNoteFolder(current: String): String = current.ifEmpty { Names.join("", "") }
}
