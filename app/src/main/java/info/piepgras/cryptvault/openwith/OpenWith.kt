package info.piepgras.cryptvault.openwith

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.OpenVault
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** One decrypted file handed to another app, and what it looked like when it left. */
data class HandOff(
    val file: File,
    val vaultId: String,
    val itemId: String,
    val size: Long,
    val lastModified: Long,
    val writable: Boolean,
    val at: Long,
)

/**
 * The temporary hand-off of BUILD_BRIEF.md §5.1: decrypt into `cache/open/<random>/<name>`,
 * hand a FileProvider URI to the chosen app with explicit grants, notice on return whether the
 * file changed (and write it back), and wipe on lock, timeout and process start.
 *
 * `cache/` is never backed up; `<random>` keeps names from colliding and from being guessed.
 */
class OpenWith(private val context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        const val DIR = "open"
        const val SHARE_DIR = "share"
        /** Hand-offs older than this are wiped even while the vault stays unlocked (§13). */
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }

    private val authority = "${context.packageName}.fileprovider"
    private val root: File get() = File(context.cacheDir, DIR)
    private val shareRoot: File get() = File(context.cacheDir, SHARE_DIR)
    private val handOffs = ConcurrentHashMap<String, HandOff>()
    private val random = SecureRandom()

    private fun randomDir(base: File): File {
        val bytes = ByteArray(12).also { random.nextBytes(it) }
        val name = bytes.joinToString("") { "%02x".format(it) }
        return File(base, name).also { it.mkdirs() }
    }

    /** Decrypts [item] into a fresh temp file. Slow; call on IO. */
    fun prepare(vault: OpenVault, item: Item, writable: Boolean, progress: ((Long) -> Unit)? = null): File {
        val file = File(randomDir(root), item.name)
        file.outputStream().use { out -> vault.writeTo(item, out, progress) }
        handOffs[file.path] = HandOff(file, vault.vaultId, item.id, file.length(), file.lastModified(), writable, clock())
        return file
    }

    /** A fresh directory under `cache/share/` for a container about to be handed to the share sheet. */
    fun newShareDir(): File = randomDir(shareRoot)

    /** Decrypts into the share directory, for the share sheet; not tracked for save-back. */
    fun prepareForShare(vault: OpenVault, item: Item): File {
        val file = File(randomDir(shareRoot), item.name)
        file.outputStream().use { out -> vault.writeTo(item, out) }
        return file
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    /** ACTION_VIEW (or ACTION_EDIT when [writable]) with explicit, ClipData-backed grants. */
    fun viewIntent(file: File, mime: String, writable: Boolean): Intent {
        val uri = uriFor(file)
        val action = if (writable) Intent.ACTION_EDIT else Intent.ACTION_VIEW
        return Intent(action).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
    }

    /** ACTION_SEND / ACTION_SEND_MULTIPLE for the share sheet; the caller wraps it in a chooser. */
    fun shareIntent(files: List<File>, mime: String, subject: String? = null, text: String? = null): Intent {
        val uris = files.map { uriFor(it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime.ifBlank { "*/*" }
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** Hand-offs whose file changed since it left: candidates for save-back. */
    fun changed(): List<HandOff> = handOffs.values.filter { h ->
        h.writable && h.file.exists() && (h.file.length() != h.size || h.file.lastModified() != h.lastModified)
    }

    /** Writes a changed hand-off back into the vault and re-baselines it. */
    fun saveBack(vault: OpenVault, handOff: HandOff): Item? {
        val item = vault.item(handOff.itemId) ?: return null
        val updated = handOff.file.inputStream().use { vault.replaceContent(item, it, handOff.file.length()) }
        handOffs[handOff.file.path] = handOff.copy(size = handOff.file.length(), lastModified = handOff.file.lastModified())
        return updated
    }

    fun handOffsFor(vaultId: String): List<HandOff> = handOffs.values.filter { it.vaultId == vaultId }

    /** Wipes everything for one vault (on lock). */
    fun wipe(vaultId: String) {
        handOffs.values.filter { it.vaultId == vaultId }.forEach { h ->
            h.file.parentFile?.deleteRecursively()
            handOffs.remove(h.file.path)
        }
    }

    /** Wipes everything (process start, lock-all). */
    fun wipeAll() {
        handOffs.clear()
        root.deleteRecursively()
        shareRoot.deleteRecursively()
    }

    /** Wipes hand-offs older than [MAX_AGE_MS] and share files older than an hour. */
    fun wipeStale() {
        val now = clock()
        handOffs.values.filter { now - it.at > MAX_AGE_MS }.forEach { h ->
            h.file.parentFile?.deleteRecursively()
            handOffs.remove(h.file.path)
        }
        shareRoot.listFiles()?.filter { now - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.deleteRecursively() }
    }
}
