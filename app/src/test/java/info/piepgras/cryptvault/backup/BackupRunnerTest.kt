package info.piepgras.cryptvault.backup

import info.piepgras.cryptvault.vault.CryptomatorVault
import info.piepgras.cryptvault.vault.PathVaultStorage
import info.piepgras.cryptvault.vault.VaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files

class BackupRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()
    private lateinit var vaultDir: File
    private lateinit var storage: PathVaultStorage
    private lateinit var vault: CryptomatorVault
    private lateinit var key: ByteArray
    private val remote = FakeRemoteStore()
    private lateinit var index: BackupIndex
    private var clock = 0

    @Before
    fun setUp() {
        vaultDir = tmp.newFolder("vault")
        storage = PathVaultStorage(vaultDir.toPath())
        vault = CryptomatorVault.create(storage, password.clone())
        key = SnapshotCrypto.deriveKey(vault.rawKey())
        index = BackupIndex(tmp.newFolder("index"))
    }

    private fun runner(idx: BackupIndex = index, installation: String = "inst-A", keep: Int = 5) = BackupRunner(
        storage, remote, idx, key, "vault-1", "Personal", installation, keep,
        now = { "2026-09-18T10:%02d:00Z".format(clock++) },
    )

    private fun put(name: String, content: String, dirId: String = "") {
        vault.writeFile(dirId, name).use { it.write(ByteBuffer.wrap(content.toByteArray())) }
    }

    private fun entry(name: String, dirId: String = ""): VaultEntry = vault.list(dirId).first { it.name == name }
    private fun read(v: CryptomatorVault, name: String, dirId: String = ""): String =
        Channels.newInputStream(v.readFile(v.list(dirId).first { it.name == name })).use { it.readBytes().toString(Charsets.UTF_8) }

    /** path → sha of every local ciphertext file the backup mirrors. */
    private fun localHashes(dir: File = vaultDir): Map<String, String> {
        val out = sortedMapOf<String, String>()
        Files.walk(dir.toPath()).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = dir.toPath().relativize(p).toString().replace(File.separatorChar, '/')
                if (rel.startsWith("d/") || rel in RemoteLayout.META_FILES) out[rel] = SnapshotCrypto.sha256Hex(Files.readAllBytes(p))
            }
        }
        return out
    }

    private fun mirrorHashes(): Map<String, String> =
        remote.files.filterKeys { !it.startsWith(RemoteLayout.SIDECAR + "/") }.mapValues { SnapshotCrypto.sha256Hex(it.value) }.toSortedMap()

    private fun remoteSnapshot(seq: Long): Snapshot =
        Snapshot.decode(SnapshotCrypto.decrypt(key, remote.files[RemoteLayout.snapshotPath(seq)]!!).toString(Charsets.UTF_8))

    private fun versions(): Set<String> = remote.list(RemoteLayout.VERSIONS).mapNotNull { RemoteLayout.versionSha(it.name) }.toSet()

    @Test
    fun `first backup mirrors the ciphertext and commits a snapshot`() {
        put("a.txt", "alpha"); put("b.txt", "bravo")
        val docs = vault.createDirectory("", "Docs"); put("c.txt", "charlie", docs)

        val outcome = runner().run() as BackupOutcome.Done
        assertEquals(1, outcome.seq)
        assertEquals(remote.files.filterKeys { !it.startsWith("cryptvault/") }.values.sumOf { it.size.toLong() }, outcome.uploadedBytes)
        assertNull(outcome.gcError)
        assertEquals(localHashes(), mirrorHashes())
        val latest = Latest.parse(remote.text(RemoteLayout.LATEST)!!)
        assertEquals(1, latest.seq)
        assertEquals(SnapshotCrypto.sha256Hex(remote.files[RemoteLayout.snapshotPath(1)]!!), latest.snapshotSha256)
        val snap = remoteSnapshot(1)
        assertEquals(localHashes().filterKeys { it.startsWith("d/") }, snap.files.associate { it.path to it.sha256 }.toSortedMap())
        assertEquals(setOf("vault.cryptomator", "masterkey.cryptomator"), snap.meta.keys)
        assertEquals("inst-A", snap.installation)
        assertEquals(1, index.state.lastOwnSeq)
        assertNull(index.journal())
        assertNotNull(index.snapshot(1))
        assertTrue(index.hashes.size >= 5)
    }

    @Test
    fun `incremental run retires, uploads, revives and skips the unchanged`() {
        put("a.txt", "alpha"); put("b.txt", "bravo"); put("keep.txt", "same")
        runner().run()
        val before = localHashes()
        val oldA = before.entries.first { it.key.startsWith("d/") && it.value == SnapshotCrypto.sha256Hex(ciphertextOf("a.txt")) }.value
        val oldB = SnapshotCrypto.sha256Hex(ciphertextOf("b.txt"))

        put("a.txt", "ALPHA v2")                       // modified
        vault.delete(entry("b.txt"))                   // deleted
        put("c.txt", "charlie")                        // new
        vault.move(entry("keep.txt"), "", "kept.txt")  // renamed: new ciphertext path, same bytes
        remote.log.clear()

        val outcome = runner().run() as BackupOutcome.Done
        assertEquals(2, outcome.seq)
        assertEquals(localHashes(), mirrorHashes())
        assertEquals(setOf(oldA, oldB), versions())
        assertTrue("rename should be a move from versions/, got: ${remote.log}", remote.log.any { it.startsWith("move cryptvault/versions/") })
        assertEquals(2, remote.log.count { it.startsWith("upload d/") })  // a.txt (new bytes) and c.txt; the rename cost nothing
        assertEquals(2, remoteSnapshot(2).seq)
        assertEquals(BackupOutcome.NothingToDo(2), runner().run())
    }

    private fun ciphertextOf(name: String): ByteArray = Files.readAllBytes(vaultDir.toPath().resolve(entry(name).contentPath!!))

    @Test
    fun `an interrupted run leaves the last commit restorable and the next run completes`() {
        put("a.txt", "alpha"); put("b.txt", "bravo")
        runner().run()
        val snapshot1Files = localHashes()

        put("a.txt", "alpha changed"); put("c.txt", "new"); vault.delete(entry("b.txt"))
        remote.failAfter = remote.mutations + 2 // dies in the middle of the steps
        assertThrows(IOException::class.java) { runner().run() }
        remote.failAfter = Int.MAX_VALUE

        // The commit point is untouched and snapshot 1 is still fully restorable.
        assertEquals(1, Latest.parse(remote.text(RemoteLayout.LATEST)!!).seq)
        val restoreDir = tmp.newFolder("restore1")
        val r = RestoreRunner(remote, PathVaultStorage(restoreDir.toPath()))
        r.fetchMetaAndUnlock(password.clone())
        val s1 = remoteSnapshot(1)
        r.restore(s1, s1)
        assertEquals(snapshot1Files, localHashes(restoreDir))
        assertNotNull(index.journal())

        val outcome = runner().run() as BackupOutcome.Done
        assertEquals(2, outcome.seq)
        assertEquals(localHashes(), mirrorHashes())
        assertNull(index.journal())
    }

    @Test
    fun `a second installation is refused until it takes over`() {
        put("a.txt", "alpha")
        runner().run()
        val other = BackupIndex(tmp.newFolder("index-B"))
        assertEquals(BackupOutcome.Foreign(1, 0), runner(other, "inst-B").run())
        assertEquals(1, Latest.parse(remote.text(RemoteLayout.LATEST)!!).seq)

        put("b.txt", "bravo")
        val taken = runner(other, "inst-B").run(takeOver = true) as BackupOutcome.Done
        assertEquals(2, taken.seq)
        assertEquals("inst-B", remoteSnapshot(2).installation)
        assertEquals(localHashes(), mirrorHashes())
        // and now the first installation is the foreign one
        assertEquals(BackupOutcome.Foreign(2, 1), runner().run())
    }

    @Test
    fun `the sixth snapshot removes the first and exactly its unreferenced version`() {
        put("x.txt", "v1")
        val shas = ArrayList<String>()
        runner().run(); shas += SnapshotCrypto.sha256Hex(ciphertextOf("x.txt"))
        for (i in 2..6) {
            put("x.txt", "v$i")
            runner().run(); shas += SnapshotCrypto.sha256Hex(ciphertextOf("x.txt"))
        }
        val present = remote.list(RemoteLayout.SNAPSHOTS).mapNotNull { RemoteLayout.snapshotSeq(it.name) }.sorted()
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), present)
        assertEquals(setOf(shas[1], shas[2], shas[3], shas[4]), versions()) // v1's bytes are gone, v2..v5 stay, v6 is the mirror
        assertNull(index.snapshot(1)); assertNotNull(index.snapshot(2))
    }

    @Test
    fun `restore reproduces the vault and older snapshots read their old content`() {
        put("x.txt", "first"); put("y.txt", "y")
        runner().run()
        put("x.txt", "second")
        runner().run()
        val current = localHashes()

        val dir = tmp.newFolder("restore")
        val r = RestoreRunner(remote, PathVaultStorage(dir.toPath()))
        val raw = r.fetchMetaAndUnlock(password.clone())
        val list = r.listSnapshots(SnapshotCrypto.deriveKey(raw))
        assertEquals(listOf(2L, 1L), list.map { it.seq })
        r.restore(list[0].snapshot, list[0].snapshot)
        assertEquals(current, localHashes(dir))

        val old = tmp.newFolder("restore-old")
        val r2 = RestoreRunner(remote, PathVaultStorage(old.toPath()))
        r2.fetchMetaAndUnlock(password.clone())
        r2.restore(list[1].snapshot, list[0].snapshot)
        CryptomatorVault.open(PathVaultStorage(old.toPath()), password.clone()).use { v ->
            assertEquals("first", read(v, "x.txt"))
            assertEquals("y", read(v, "y.txt"))
        }
        assertThrows(Exception::class.java) { RestoreRunner(remote, PathVaultStorage(tmp.newFolder("bad").toPath())).fetchMetaAndUnlock("wrong".toCharArray()) }
    }

    @Test
    fun `snapshot crypto round trip and key derivation`() {
        val plain = "{\"seq\":1}".toByteArray()
        val blob = SnapshotCrypto.encrypt(key, plain)
        assertEquals("CVS1", blob.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(String(plain), String(SnapshotCrypto.decrypt(key, blob)))
        assertThrows(IOException::class.java) { SnapshotCrypto.decrypt(SnapshotCrypto.deriveKey(ByteArray(64) { 7 }), blob) }
        assertThrows(IOException::class.java) { SnapshotCrypto.decrypt(key, "not a snapshot at all".toByteArray()) }
        assertEquals(SnapshotCrypto.deriveKey(vault.rawKey()).toHex(), key.toHex())
        assertEquals(32, key.size)
    }

    @Test
    fun `hash cache invalidates on size or mtime change`() {
        val c = HashCache(File(tmp.root, "h.json"))
        c.put("d/a", 10, 100, "sha-a")
        assertEquals("sha-a", c.lookup("d/a", 10, 100))
        assertNull(c.lookup("d/a", 11, 100)); assertNull(c.lookup("d/a", 10, 101))
        c.save()
        val again = HashCache(File(tmp.root, "h.json"))
        assertEquals("sha-a", again.lookup("d/a", 10, 100))
        again.retainOnly(emptySet()); again.save()
        assertNull(HashCache(File(tmp.root, "h.json")).lookup("d/a", 10, 100))
    }
}
