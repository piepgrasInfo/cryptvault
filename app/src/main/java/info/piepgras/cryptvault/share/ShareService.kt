package info.piepgras.cryptvault.share

import android.content.Context
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.ItemKind
import info.piepgras.cryptvault.items.OpenVault
import info.piepgras.cryptvault.openwith.OpenWith
import info.piepgras.cryptvault.ui.Format
import java.io.File

/** What the share sheet gets: the container file, its MIME type, a subject and a body. */
data class ShareBundle(val file: File, val mime: String, val subject: String, val body: String, val size: Long)

/**
 * Turns selected items into one encrypted container in `cache/share/` and the text that goes
 * with it (BUILD_BRIEF.md §7.1–7.2): what is attached, which format, who opens it, and that the
 * passphrase comes separately — nothing else, and never the passphrase itself.
 */
class ShareService(private val context: Context, private val openWith: OpenWith) {

    /** The entries of a share: each file item, plus its note as `<title> - note.txt`; a note item as its Markdown file. */
    fun entriesOf(vault: OpenVault, items: List<Item>): List<ShareEntry> {
        val out = ArrayList<ShareEntry>()
        for (item in items) {
            out += ShareEntry(item.name, item.size) { vault.open(item) }
            if (item.kind == ItemKind.FILE && item.note.isNotBlank()) {
                val bytes = item.note.toByteArray(Charsets.UTF_8)
                out += ShareEntry(SharePolicy.noteFileName(item.name), bytes.size.toLong()) { bytes.inputStream() }
            }
        }
        return out
    }

    /** The container's size before it is written: the sum of the entries, a good enough estimate. */
    fun estimateSize(vault: OpenVault, items: List<Item>): Long = entriesOf(vault, items).sumOf { it.size }

    fun titleFor(items: List<Item>): String =
        if (items.size == 1) items.single().let { it.title.ifBlank { it.name } } else context.resources.getQuantityString(R.plurals.share_default_title_n, items.size, items.size)

    /** Writes the container and builds the mail text. Blocking; call on IO. */
    fun build(vault: OpenVault, items: List<Item>, kind: ContainerKind, passphrase: CharArray): ShareBundle {
        val entries = entriesOf(vault, items)
        val title = titleFor(items)
        val name = SharePolicy.containerName(kind, items.map { it.name }, title)
        val dir = openWith.newShareDir()
        val file = File(dir, name)
        ContainerWriter.write(kind, entries, passphrase, file, title, dir)
        val size = file.length()
        val subject = context.getString(R.string.share_subject, title)
        val body = context.getString(
            R.string.share_body,
            name,
            context.getString(kindName(kind)),
            Format.size(size),
            context.getString(openers(kind)),
        )
        return ShareBundle(file, kind.mime, subject, body, size)
    }

    companion object {
        fun kindName(kind: ContainerKind): Int = when (kind) {
            ContainerKind.ZIP -> R.string.share_kind_zip
            ContainerKind.AGE -> R.string.share_kind_age
            ContainerKind.PGP -> R.string.share_kind_pgp
        }

        fun openers(kind: ContainerKind): Int = when (kind) {
            ContainerKind.ZIP -> R.string.share_openers_zip
            ContainerKind.AGE -> R.string.share_openers_age
            ContainerKind.PGP -> R.string.share_openers_pgp
        }
    }
}
