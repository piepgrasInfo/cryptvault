package info.piepgras.cryptvault.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A vault folder on a WebDAV server (Nextcloud, ownCloud, Synology, Hetzner, …), BUILD_BRIEF.md
 * §6.1: PROPFIND, PUT with `If-Match`, GET with `Range`, MKCOL, MOVE, DELETE over plain OkHttp
 * with HTTP Basic auth. ETags are the concurrency token. Uploads are single streams (Nextcloud
 * chunking is not used; a backup's largest single upload is one ciphertext file).
 *
 * [base] is the vault folder's URL, e.g. `https://cloud.example.org/remote.php/dav/files/me/CryptVault/personal-8c5b2f0e/`.
 */
class WebDavStore(
    base: String,
    private val user: String,
    private val password: String,
    client: OkHttpClient = defaultClient(),
) : RemoteStore {

    private val base: HttpUrl = base.trimEnd('/').plus("/").toHttpUrlOrNull() ?: throw IOException("not a valid URL: $base")
    private val client = client.newBuilder().authenticator { _, response ->
        if (response.request.header("Authorization") != null) null // already tried
        else response.request.newBuilder().header("Authorization", Credentials.basic(user, password)).build()
    }.build()

    override val caps = RemoteCaps(conditionalWrite = true, serverSideMove = true, rangeGet = true)

    private fun url(path: String): HttpUrl {
        val b = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun dirUrl(path: String): HttpUrl = url(path).newBuilder().addPathSegment("").build()

    private fun request(url: HttpUrl, method: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url).method(method, body)
            .header("Authorization", Credentials.basic(user, password))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun check(r: Response, path: String, vararg ok: Int): Response {
        if (r.code in ok) return r
        r.close()
        when (r.code) {
            401, 403 -> throw RemoteAuthException("the server refused the credentials (${r.code}) for $path")
            412 -> throw RemoteConflictException(path)
            507 -> throw IOException("the server is out of space (507)")
            else -> throw IOException("$path: HTTP ${r.code} ${r.message}".trim())
        }
    }

    override fun list(dir: String): List<RemoteEntry> {
        val req = request(dirUrl(dir), "PROPFIND", PROPFIND_BODY.toRequestBodyXml(), mapOf("Depth" to "1"))
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return emptyList()
            check(r, dir, 207)
            val self = dirUrl(dir).encodedPath.trimEnd('/')
            return parseMultistatus(r.body?.string() ?: "").filter { it.href.trimEnd('/') != self }
                .map { RemoteEntry(it.name, it.isDir, it.size, it.etag) }
        }
    }

    override fun stat(path: String): RemoteEntry? {
        val req = request(url(path), "PROPFIND", PROPFIND_BODY.toRequestBodyXml(), mapOf("Depth" to "0"))
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            check(r, path, 207)
            val p = parseMultistatus(r.body?.string() ?: "").firstOrNull() ?: return null
            return RemoteEntry(RemotePaths.name(path).ifEmpty { p.name }, p.isDir, p.size, p.etag)
        }
    }

    @Volatile private var baseKnown = false

    /** MKCOL of [url]; creates up to [depth] missing parents first (the vault folder and `CryptVault/`). */
    private fun mkcol(url: HttpUrl, depth: Int) {
        client.newCall(request(url, "MKCOL")).execute().use { r ->
            when (r.code) {
                201, 405 -> return // created, or exists already
                409 -> if (depth > 0) {
                    val segments = url.pathSegments.filter { it.isNotEmpty() }
                    if (segments.size <= 1) check(r, url.encodedPath, 201)
                    val parent = url.newBuilder().encodedPath("/").apply { segments.dropLast(1).forEach { addPathSegment(it) }; addPathSegment("") }.build()
                    r.close()
                    mkcol(parent, depth - 1)
                    mkcol(url, 0)
                } else check(r, url.encodedPath, 201)
                else -> check(r, url.encodedPath, 201, 405)
            }
        }
    }

    override fun mkdirs(dir: String) {
        if (!baseKnown) { mkcol(base, 3); baseKnown = true }
        var current = ""
        for (seg in dir.split('/').filter { it.isNotEmpty() }) {
            current = RemotePaths.join(current, seg)
            mkcol(dirUrl(current), 0)
        }
    }

    override fun upload(path: String, size: Long, ifMatch: String?, source: () -> InputStream): RemoteEntry {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { source().use { input -> input.source().use { sink.writeAll(it) } } }
        }
        val headers = HashMap<String, String>()
        if (ifMatch == "*") headers["If-None-Match"] = "*" else if (ifMatch != null) headers["If-Match"] = ifMatch
        client.newCall(request(url(path), "PUT", body, headers)).execute().use { r ->
            if (r.code == 409) { r.close(); mkdirs(RemotePaths.parent(path)); return upload(path, size, ifMatch, source) }
            check(r, path, 200, 201, 204)
            val etag = r.header("ETag") ?: stat(path)?.etag
            return RemoteEntry(RemotePaths.name(path), false, size, etag)
        }
    }

    override fun download(path: String, offset: Long): InputStream {
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
        val r = client.newCall(request(url(path), "GET", null, headers)).execute()
        if (r.code == 404) { r.close(); throw IOException("not found: $path") }
        check(r, path, 200, 206)
        val body = r.body ?: throw IOException("empty body for $path")
        val stream = body.byteStream()
        if (offset > 0 && r.code == 200) stream.skipFully(offset) // the server ignored the range
        return stream
    }

    override fun delete(path: String, ifMatch: String?) {
        val headers = if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap()
        client.newCall(request(url(path), "DELETE", null, headers)).execute().use { r ->
            if (r.code == 404) return
            check(r, path, 200, 204)
        }
    }

    override fun move(from: String, to: String) {
        val headers = mapOf("Destination" to url(to).toString(), "Overwrite" to "T")
        client.newCall(request(url(from), "MOVE", null, headers)).execute().use { r ->
            if (r.code == 409) { r.close(); mkdirs(RemotePaths.parent(to)); return move(from, to) }
            check(r, from, 201, 204)
        }
    }

    /** OPTIONS + PROPFIND of the base: the "Test connection" of the settings screen. Returns null or a message. */
    fun probe(): String? = try {
        client.newCall(request(base, "PROPFIND", PROPFIND_BODY.toRequestBodyXml(), mapOf("Depth" to "0"))).execute().use { r ->
            when (r.code) {
                207, 200 -> null
                401, 403 -> "The server refused the user name or password."
                404 -> "Nothing at this address (404). Check the URL — for Nextcloud it ends in /remote.php/dav/files/<user>/."
                else -> "HTTP ${r.code} ${r.message}".trim()
            }
        }
    } catch (e: IOException) {
        e.message ?: e.javaClass.simpleName
    }

    private data class Prop(val href: String, val name: String, val isDir: Boolean, val size: Long, val etag: String?)

    private fun parseMultistatus(xml: String): List<Prop> {
        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream())
        } catch (e: Exception) {
            throw IOException("the server sent an unreadable PROPFIND reply", e)
        }
        val out = ArrayList<Prop>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val el = responses.item(i) as Element
            val href = el.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val isDir = el.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = el.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
            val etag = el.getElementsByTagNameNS("DAV:", "getetag").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val decoded = URLDecoder.decode(href.substringAfter("://").substringAfter('/', href).let { "/$it" }.takeIf { href.contains("://") } ?: href, "UTF-8")
            val name = decoded.trimEnd('/').substringAfterLast('/')
            out += Prop(decoded, name, isDir, size, etag)
        }
        return out
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/><D:getetag/></D:prop></D:propfind>"""

        private fun String.toRequestBodyXml(): RequestBody = RequestBody.create(XML, this)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun InputStream.skipFully(n: Long) {
            var left = n
            val buf = ByteArray(65536)
            while (left > 0) {
                val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) throw IOException("short body")
                left -= r
            }
        }
    }
}
