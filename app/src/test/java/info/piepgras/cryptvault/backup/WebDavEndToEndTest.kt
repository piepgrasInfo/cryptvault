package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.PathVaultStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The backup executor against a real WebDAV implementation: `tools/webdav_test_server.py`
 * started as a subprocess (skipped where python3 is missing). This is the automated half of the
 * Phase 4 WebDAV acceptance; the emulator half runs against the same script at 10.0.2.2.
 */
class WebDavEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private var server: Process? = null
    private var port = 0
    private val password = "correct horse battery staple".toCharArray()

    @Before
    fun startServer() {
        val script = File(System.getProperty("user.dir")).let { dir ->
            generateSequence(dir) { it.parentFile }.map { File(it, "tools/webdav_test_server.py") }.firstOrNull { it.exists() }
        }
        assumeTrue("tools/webdav_test_server.py not found", script != null)
        val python = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false) }
        assumeTrue("python not available", python != null)
        port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder(python!!, script!!.path, "--root", tmp.newFolder("dav").path, "--port", port.toString(), "--bind", "127.0.0.1", "--user", "u", "--password", "p")
            .redirectErrorStream(true).redirectOutput(File(tmp.root, "server.log")).start()
        // wait until it answers
        val store = WebDavStore("http://127.0.0.1:$port/dav/", "u", "p")
        var ok = false
        repeat(50) { if (!ok) { ok = store.probe() == null; if (!ok) Thread.sleep(100) } }
        assumeTrue("server did not come up: " + File(tmp.root, "server.log").readText(), ok)
    }

    @After
    fun stopServer() { server?.destroy() }

    @Test
    fun `backup, change, interrupted run, second run and restore over real WebDAV`() {
        val vaultDir = tmp.newFolder("vault")
        val storage = PathVaultStorage(vaultDir.toPath())
        val vault = CryptomatorVault.create(storage, password.clone())
        val key = SnapshotCrypto.deriveKey(vault.rawKey())
        fun put(name: String, content: ByteArray) = vault.writeFile("", name).use { it.write(ByteBuffer.wrap(content)) }
        put("small.txt", "hello".toByteArray())
        put("big.bin", ByteArray(300_000) { (it % 251).toByte() }) // several chunks, streamed up and down
        val index = BackupIndex(tmp.newFolder("index"))
        val store = WebDavStore("http://127.0.0.1:$port/dav/personal-1/", "u", "p")
        store.mkdirs("")
        val runner = BackupRunner(storage, store, index, key, "v", "Personal", "inst")

        val first = runner.run() as BackupOutcome.Done
        assertEquals(1, first.seq)
        assertEquals(hashes(vaultDir), remoteHashes(store))

        put("small.txt", "hello again".toByteArray())
        vault.delete(vault.list("").first { it.name == "big.bin" })
        put("third.txt", "3".toByteArray())
        val second = runner.run() as BackupOutcome.Done
        assertEquals(2, second.seq)
        assertEquals(hashes(vaultDir), remoteHashes(store))
        assertEquals(2, store.list(RemoteLayout.VERSIONS).size)
        assertEquals(BackupOutcome.NothingToDo(2), runner.run())

        // a second installation is refused by the conditional write on `latest`
        val other = BackupIndex(tmp.newFolder("index2"))
        assertEquals(BackupOutcome.Foreign(2, 0), BackupRunner(storage, store, other, key, "v", "Personal", "inst2").run())

        // restore snapshot 1 (big.bin is in versions/ now) and snapshot 2
        val r1 = tmp.newFolder("r1")
        val restore = RestoreRunner(store, PathVaultStorage(r1.toPath()))
        val raw = restore.fetchMetaAndUnlock(password.clone())
        val snaps = restore.listSnapshots(SnapshotCrypto.deriveKey(raw))
        assertEquals(listOf(2L, 1L), snaps.map { it.seq })
        restore.restore(snaps[1].snapshot, snaps[0].snapshot)
        CryptomatorVault.open(PathVaultStorage(r1.toPath()), password.clone()).use { v ->
            val names = v.list("").map { it.name }.toSet()
            assertEquals(setOf("small.txt", "big.bin"), names)
            val big = v.list("").first { it.name == "big.bin" }
            assertEquals(300_000L, big.size)
        }
        val r2 = tmp.newFolder("r2")
        val restore2 = RestoreRunner(store, PathVaultStorage(r2.toPath()))
        restore2.fetchMetaAndUnlock(password.clone())
        restore2.restore(snaps[0].snapshot, snaps[0].snapshot)
        assertEquals(hashes(vaultDir), hashes(r2))
        assertTrue(File(tmp.root, "server.log").readText().contains("PUT"))
    }

    private fun hashes(dir: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        Files.walk(dir.toPath()).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = dir.toPath().relativize(p).toString().replace(File.separatorChar, '/')
                if (rel.startsWith("d/") || rel in RemoteLayout.META_FILES) out[rel] = SnapshotCrypto.sha256Hex(Files.readAllBytes(p))
            }
        }
        return out
    }

    private fun remoteHashes(store: RemoteStore, dir: String = "", out: java.util.TreeMap<String, String> = java.util.TreeMap()): Map<String, String> {
        for (e in store.list(dir)) {
            val p = RemotePaths.join(dir, e.name)
            if (e.isDirectory) { if (p != RemoteLayout.SIDECAR) remoteHashes(store, p, out) }
            else if (p.startsWith("d/") || p in RemoteLayout.META_FILES) out[p] = SnapshotCrypto.sha256Hex(store.download(p).use { it.readBytes() })
        }
        return out
    }
}
