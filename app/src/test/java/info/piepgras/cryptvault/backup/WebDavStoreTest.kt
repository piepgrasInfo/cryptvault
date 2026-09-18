package info.piepgras.cryptvault.backup

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavStoreTest {
    private val server = MockWebServer()
    private lateinit var store: WebDavStore

    @Before fun start() { server.start(); store = WebDavStore(server.url("/remote.php/dav/files/me/CryptVault/personal-1/").toString(), "me", "app-pw") }
    @After fun stop() { server.shutdown() }

    private val multistatus = """<?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response><d:href>/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/latest</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>67</d:getcontentlength><d:getetag>&quot;abc123&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/snapshots/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/name%20with%20space.c9r</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength><d:getetag>&quot;x&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""

    @Test
    fun `list parses a multistatus, drops the directory itself and decodes names`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        val entries = store.list("cryptvault")
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/", req.path)
        assertEquals("1", req.getHeader("Depth"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals(listOf(
            RemoteEntry("latest", false, 67, "\"abc123\""),
            RemoteEntry("snapshots", true, 0, null),
            RemoteEntry("name with space.c9r", false, 5, "\"x\""),
        ), entries)
    }

    @Test
    fun `stat maps 404 to null and a conditional put maps 412 to a conflict`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(store.stat("cryptvault/latest"))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(412))
        assertThrows(RemoteConflictException::class.java) {
            store.upload("cryptvault/latest", 4, "\"abc123\"") { "1\nx\n".byteInputStream() }
        }
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("\"abc123\"", put.getHeader("If-Match"))
        assertEquals("4", put.getHeader("Content-Length"))

        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"new\""))
        val e = store.upload("cryptvault/latest", 4, "*") { "1\nx\n".byteInputStream() }
        val put2 = server.takeRequest()
        assertEquals("*", put2.getHeader("If-None-Match"))
        assertEquals("1\nx\n", put2.body.readUtf8())
        assertEquals(RemoteEntry("latest", false, 4, "\"new\""), e)
    }

    @Test
    fun `download sends a range, move sends destination and overwrite, auth failures are typed`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("tail"))
        assertEquals("tail", store.download("d/AB/x.c9r", 100).use { it.readBytes().toString(Charsets.UTF_8) })
        assertEquals("bytes=100-", server.takeRequest().getHeader("Range"))

        server.enqueue(MockResponse().setResponseCode(201))
        store.move("d/AB/x.c9r", "cryptvault/versions/aa.c9r")
        val mv = server.takeRequest()
        assertEquals("MOVE", mv.method)
        assertEquals(server.url("/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/versions/aa.c9r").toString(), mv.getHeader("Destination"))
        assertEquals("T", mv.getHeader("Overwrite"))

        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(RemoteAuthException::class.java) { store.list("") }
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(404))
        store.delete("gone")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `mkdirs creates each level and tolerates existing ones, probe reports plainly`() {
        server.enqueue(MockResponse().setResponseCode(409)) // the vault folder is missing …
        server.enqueue(MockResponse().setResponseCode(201)) // … so its parent is created first
        server.enqueue(MockResponse().setResponseCode(201)) // then the folder
        server.enqueue(MockResponse().setResponseCode(405)) // cryptvault/ exists already
        server.enqueue(MockResponse().setResponseCode(201))
        store.mkdirs("cryptvault/versions")
        assertEquals("/remote.php/dav/files/me/CryptVault/personal-1/", server.takeRequest().path)
        assertEquals("/remote.php/dav/files/me/CryptVault/", server.takeRequest().path)
        assertEquals("/remote.php/dav/files/me/CryptVault/personal-1/", server.takeRequest().path)
        assertEquals("/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/", server.takeRequest().path)
        assertEquals("/remote.php/dav/files/me/CryptVault/personal-1/cryptvault/versions/", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(store.probe()!!.contains("404"))
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        assertNull(store.probe())
    }
}
