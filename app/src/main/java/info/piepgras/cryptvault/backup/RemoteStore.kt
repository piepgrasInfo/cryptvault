package info.piepgras.cryptvault.backup

import java.io.IOException
import java.io.InputStream

/** One entry of a remote listing. [etag] is the provider's version token when it has one. */
data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long, val etag: String? = null)

/** What a target can do; the executor adapts, the planner never sees it (BUILD_BRIEF.md §6.2). */
data class RemoteCaps(
    /** `upload(ifMatch)` and `delete(ifMatch)` are honoured server-side. */
    val conditionalWrite: Boolean,
    /** `move` is a rename on the server; otherwise the store copies and deletes. */
    val serverSideMove: Boolean,
    val rangeGet: Boolean,
    /** Uploads above this size go through the store's resumable path; 0 = no limit. */
    val maxSimpleUpload: Long = 0,
)

/** Thrown when a conditional write's precondition fails: someone else wrote here. */
class RemoteConflictException(path: String) : IOException("remote changed underneath us: $path")

/** Thrown when the target rejects our credentials; the run stops and the UI asks to reconnect. */
class RemoteAuthException(message: String) : IOException(message)

/**
 * A vault's folder on one backup target (docs/VAULT_LAYOUT.md §6): paths are relative to that
 * folder, '/'-separated, "" being the folder itself. Every provider quirk lives inside the
 * implementation; the executor sees only these calls. All calls block and throw [IOException].
 * Conditional writes use the [RemoteEntry.etag] a previous call returned; [ifMatch] = "*" on
 * upload means "only if absent" where the provider can express it.
 */
interface RemoteStore {
    val caps: RemoteCaps

    /** Lists one directory level; an empty list for a missing directory. */
    fun list(dir: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    /** Creates the directory and any missing parents; no error if it already exists. */
    fun mkdirs(dir: String)
    /**
     * Streams [size] bytes from [source] to [path], creating parents and overwriting. With
     * [ifMatch] and [RemoteCaps.conditionalWrite], the write only succeeds when the remote
     * version still matches, else [RemoteConflictException].
     */
    fun upload(path: String, size: Long, ifMatch: String? = null, source: () -> InputStream): RemoteEntry
    /** Opens [path] for reading, from [offset] when the store can range-get (else it skips). */
    fun download(path: String, offset: Long = 0): InputStream
    fun delete(path: String, ifMatch: String? = null)
    /** Moves a file, replacing an existing [to]; parents of [to] are created. */
    fun move(from: String, to: String)
}

/** Small helpers every store shares. */
object RemotePaths {
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun name(path: String): String = path.substringAfterLast('/')
    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
}
