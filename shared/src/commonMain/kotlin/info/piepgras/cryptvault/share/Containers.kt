package info.piepgras.cryptvault.share

import info.piepgras.cryptvault.unlock.Passphrase

/** The three mail containers of BUILD_BRIEF.md §7.2. */
enum class ContainerKind(val extension: String, val mime: String) {
    /** ZIP with AES-256 (AE-2); opens in 7-Zip, WinRAR, PeaZip, Keka, The Unarchiver, iZip. */
    ZIP("zip", "application/zip"),
    /** age with a passphrase (scrypt); opens with age/rage and the mobile age apps. */
    AGE("age", "application/vnd.age"),
    /** OpenPGP symmetric, v4 SKESK + SEIPDv1; opens in GnuPG, Kleopatra, GPG Suite, Thunderbird, Proton. */
    PGP("gpg", "application/pgp-encrypted"),
}

/** Content sniffing for received containers (docs/VAULT_LAYOUT.md §8): by bytes, never by name. */
object ContainerSniff {
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val AGE_MAGIC = "age-encryption.org/v1\n".encodeToByteArray()

    /** [head] is the first bytes of the file (32 are plenty). */
    fun sniff(head: ByteArray): ContainerKind? = when {
        head.size >= 4 && head.copyOfRange(0, 4).contentEquals(ZIP_MAGIC) -> ContainerKind.ZIP
        head.size >= AGE_MAGIC.size && head.copyOfRange(0, AGE_MAGIC.size).contentEquals(AGE_MAGIC) -> ContainerKind.AGE
        head.isNotEmpty() && (head[0] == 0xC3.toByte() || head[0] == 0x8C.toByte()) -> ContainerKind.PGP
        else -> null
    }

    const val HEAD_BYTES = 32
}

/** The rules around a shared container: passphrase policy, size warning, names. */
object SharePolicy {
    /** Above this container size the mail is likely to bounce (BUILD_BRIEF.md §7.2). */
    const val WARN_BYTES = 15L * 1024 * 1024

    /** zxcvbn score a typed passphrase needs (0–4). */
    const val MIN_SCORE = 3

    /** ZIP's PBKDF2-SHA1 × 1000 is weak: six list words, or a typed phrase of score 4. */
    const val ZIP_MIN_SCORE = 4

    sealed interface Verdict {
        data object Ok : Verdict
        data class TooWeak(val needScore: Int) : Verdict
        data object Empty : Verdict
    }

    /**
     * Whether a passphrase may be used for [kind]. [score] is the zxcvbn score of a typed phrase
     * (the caller computes it; generated phrases are list words and pass without it).
     */
    fun check(kind: ContainerKind, phrase: String, score: Int): Verdict {
        if (phrase.isBlank()) return Verdict.Empty
        if (Passphrase.isFromList(phrase)) return Verdict.Ok
        val need = if (kind == ContainerKind.ZIP) ZIP_MIN_SCORE else MIN_SCORE
        return if (score >= need) Verdict.Ok else Verdict.TooWeak(need)
    }

    /** The container's file name: one item keeps its name, several take the title. */
    fun containerName(kind: ContainerKind, itemNames: List<String>, title: String): String {
        val base = if (itemNames.size == 1) itemNames.single() else title.ifBlank { "cryptvault" }
        return when (kind) {
            ContainerKind.ZIP -> stripExtension(base) + ".zip"
            ContainerKind.AGE -> if (itemNames.size == 1) "$base.age" else stripExtension(base) + ".zip.age"
            ContainerKind.PGP -> if (itemNames.size == 1) "$base.gpg" else stripExtension(base) + ".zip.gpg"
        }
    }

    /** Whether a single file of this extension is stored, not deflated, inside a ZIP (already compressed). */
    fun isAlreadyCompressed(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "mkv", "mov", "m4a", "mp3", "aac", "ogg", "opus", "flac", "zip", "7z", "rar", "gz", "xz", "zst", "pdf", "apk", "jar", "age", "gpg")

    /** The name of the note file that travels beside a file item: `<title> - note.txt`. */
    fun noteFileName(itemTitle: String): String = "${stripExtension(itemTitle)} - note.txt"
    const val NOTE_SUFFIX = " - note.txt"

    private fun stripExtension(name: String): String = if (name.count { it == '.' } >= 1 && name.substringAfterLast('.').length in 1..5) name.substringBeforeLast('.') else name
}
