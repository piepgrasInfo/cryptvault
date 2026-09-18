package info.piepgras.cryptvault.backup

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An in-memory [RemoteStore] with failure injection: [failAfter] operations that change the
 * remote (uploads, moves, deletes) throw, which is how the tests interrupt a run at any point.
 */
class FakeRemoteStore(
    override val caps: RemoteCaps = RemoteCaps(conditionalWrite = true, serverSideMove = true, rangeGet = true),
) : RemoteStore {
    val files = sortedMapOf<String, ByteArray>()
    val dirs = sortedSetOf<String>()
    private var etagCounter = 0L
    val etags = HashMap<String, String>()
    var failAfter: Int = Int.MAX_VALUE
    var mutations = 0
        private set
    val log = ArrayList<String>()

    private fun mutate(what: String) {
        log += what
        if (++mutations > failAfter) throw IOException("injected failure at: $what")
    }

    private fun newEtag(path: String): String = "e${++etagCounter}".also { etags[path] = it }

    override fun list(dir: String): List<RemoteEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val names = LinkedHashMap<String, RemoteEntry>()
        for ((p, bytes) in files) if (p.startsWith(prefix)) {
            val rest = p.removePrefix(prefix)
            val name = rest.substringBefore('/')
            if ('/' in rest) names.putIfAbsent(name, RemoteEntry(name, true, 0))
            else names[name] = RemoteEntry(name, false, bytes.size.toLong(), etags[p])
        }
        for (d in dirs) if (d.startsWith(prefix) && d != dir) {
            val name = d.removePrefix(prefix).substringBefore('/')
            names.putIfAbsent(name, RemoteEntry(name, true, 0))
        }
        return names.values.toList()
    }

    override fun stat(path: String): RemoteEntry? {
        files[path]?.let { return RemoteEntry(RemotePaths.name(path), false, it.size.toLong(), etags[path]) }
        if (path in dirs || files.keys.any { it.startsWith("$path/") }) return RemoteEntry(RemotePaths.name(path), true, 0)
        return null
    }

    override fun mkdirs(dir: String) {
        var p = dir
        while (p.isNotEmpty()) { dirs += p; p = RemotePaths.parent(p) }
    }

    override fun upload(path: String, size: Long, ifMatch: String?, source: () -> InputStream): RemoteEntry {
        if (ifMatch != null) {
            val current = etags[path]
            if (ifMatch == "*" && current != null) throw RemoteConflictException(path)
            if (ifMatch != "*" && current != ifMatch) throw RemoteConflictException(path)
        }
        mutate("upload $path")
        val bytes = source().use { it.readBytes() }
        check(bytes.size.toLong() == size) { "size mismatch for $path" }
        mkdirs(RemotePaths.parent(path))
        files[path] = bytes
        return RemoteEntry(RemotePaths.name(path), false, size, newEtag(path))
    }

    override fun download(path: String, offset: Long): InputStream {
        val bytes = files[path] ?: throw IOException("not found: $path")
        return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    override fun delete(path: String, ifMatch: String?) {
        if (ifMatch != null && etags[path] != ifMatch) throw RemoteConflictException(path)
        mutate("delete $path")
        if (files.remove(path) == null) { dirs.remove(path); files.keys.filter { it.startsWith("$path/") }.forEach { files.remove(it) } }
        etags.remove(path)
    }

    override fun move(from: String, to: String) {
        mutate("move $from -> $to")
        val bytes = files.remove(from) ?: throw IOException("not found: $from")
        mkdirs(RemotePaths.parent(to))
        files[to] = bytes
        etags.remove(from); newEtag(to)
    }

    /** A second installation's view: same bytes, separate counters. */
    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)
}
