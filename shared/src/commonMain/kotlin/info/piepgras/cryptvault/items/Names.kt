package info.piepgras.cryptvault.items

/** Rules for names and paths in the cleartext tree. Pure, so they are tested on the JVM. */
object Names {

    const val MAX_NAME_LENGTH = 200
    const val NOTE_EXTENSION = ".md"

    /**
     * Turns whatever a picker, a share intent or a keyboard produced into a name the vault tree
     * accepts: no path separators, no control characters, no leading or trailing dots or
     * spaces, not `.` or `..`, at most [MAX_NAME_LENGTH] characters, never empty.
     */
    fun sanitize(raw: String?, fallback: String = "untitled"): String {
        var s = (raw ?: "").replace('/', '_').replace('\\', '_')
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .trim()
            .trimEnd('.')
            .trimStart('.')
        if (s.isEmpty() || s == "." || s == "..") s = fallback
        if (s.length > MAX_NAME_LENGTH) {
            val ext = extension(s)
            val keep = if (ext.isNotEmpty() && ext.length < 16) ext else ""
            s = s.take(MAX_NAME_LENGTH - keep.length).trimEnd('.', ' ') + keep
        }
        return s
    }

    /** `name (2).ext`, `name (3).ext`, … until [taken] does not contain it. */
    fun unique(wanted: String, taken: Set<String>): String {
        if (wanted !in taken) return wanted
        val ext = extension(wanted)
        val stem = wanted.removeSuffix(ext)
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** The extension including the dot (`.tar.gz` → `.gz`), lower-cased, or "". */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot)
        return if (ext.length <= 12 && ext.drop(1).all { it.isLetterOrDigit() }) ext.lowercase() else ""
    }

    fun isNoteName(name: String): Boolean = name.lowercase().endsWith(NOTE_EXTENSION)

    /** Joins a folder path and a name; the root folder is "". */
    fun join(folder: String, name: String): String = when {
        folder.isEmpty() -> name
        name.isEmpty() -> folder
        else -> "$folder/$name"
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun last(path: String): String = path.substringAfterLast('/')

    /** True when [path] is [folder] itself or lies below it. */
    fun isWithin(path: String, folder: String): Boolean =
        folder.isEmpty() || path == folder || path.startsWith("$folder/")

    /** Re-roots [path] from [oldFolder] to [newFolder]. */
    fun rebase(path: String, oldFolder: String, newFolder: String): String {
        require(isWithin(path, oldFolder)) { "$path is not within $oldFolder" }
        val rest = if (oldFolder.isEmpty()) path else path.removePrefix(oldFolder).removePrefix("/")
        return join(newFolder, rest)
    }

    /** A rough MIME type from the extension, for platforms without a MIME map. */
    fun mimeFromName(name: String): String = when (extension(name)) {
        ".jpg", ".jpeg" -> "image/jpeg"
        ".png" -> "image/png"
        ".gif" -> "image/gif"
        ".webp" -> "image/webp"
        ".heic", ".heif" -> "image/heic"
        ".bmp" -> "image/bmp"
        ".svg" -> "image/svg+xml"
        ".mp4", ".m4v" -> "video/mp4"
        ".mkv" -> "video/x-matroska"
        ".webm" -> "video/webm"
        ".mov" -> "video/quicktime"
        ".3gp" -> "video/3gpp"
        ".mp3" -> "audio/mpeg"
        ".m4a" -> "audio/mp4"
        ".ogg", ".oga" -> "audio/ogg"
        ".opus" -> "audio/opus"
        ".flac" -> "audio/flac"
        ".wav" -> "audio/wav"
        ".pdf" -> "application/pdf"
        ".txt" -> "text/plain"
        ".md" -> "text/markdown"
        ".csv" -> "text/csv"
        ".json" -> "application/json"
        ".xml" -> "application/xml"
        ".html", ".htm" -> "text/html"
        ".zip" -> "application/zip"
        ".7z" -> "application/x-7z-compressed"
        ".gpg", ".pgp" -> "application/pgp-encrypted"
        ".age" -> "application/vnd.age"
        ".doc" -> "application/msword"
        ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ".xls" -> "application/vnd.ms-excel"
        ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ".ppt" -> "application/vnd.ms-powerpoint"
        ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ".odt" -> "application/vnd.oasis.opendocument.text"
        ".ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        ".epub" -> "application/epub+zip"
        ".apk" -> "application/vnd.android.package-archive"
        ".kdbx" -> "application/x-keepass"
        ".pem", ".crt", ".cer" -> "application/x-pem-file"
        ".p12", ".pfx" -> "application/x-pkcs12"
        else -> "application/octet-stream"
    }

    /** A vault display name → the remote folder slug (docs/VAULT_LAYOUT.md §6). */
    fun slug(displayName: String, id: String): String {
        val base = displayName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
            .ifEmpty { "vault" }
        return "$base-${id.replace("-", "").take(8)}"
    }
}
