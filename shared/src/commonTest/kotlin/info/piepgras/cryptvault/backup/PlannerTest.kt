package info.piepgras.cryptvault.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {
    private val sha = (0..9).map { "%02x".format(it).repeat(32) }
    private fun f(path: String, i: Int, size: Long = 100L * (i + 1)) = LocalFile(path, size, sha[i])
    private fun meta(vararg pairs: Pair<String, Int>) = pairs.associate { (n, i) -> n to LocalFile(n, 400, sha[i]) }
    private fun snap(seq: Long, files: List<LocalFile>, meta: Map<String, LocalFile>) =
        snapshotFor(plan(LocalState(files, meta), null).copy(seq = seq), LocalState(files, meta), "v", "Personal", "inst", "2026-09-18T00:00:00Z")

    @Test
    fun `first backup uploads everything and the meta files`() {
        val local = LocalState(listOf(f("d/AA/x.c9r", 0), f("d/AA/y.c9r", 1)), meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9))
        val p = plan(local, null)
        assertEquals(1, p.seq)
        assertEquals(
            listOf(Step.Upload("d/AA/x.c9r", 100, sha[0]), Step.Upload("d/AA/y.c9r", 200, sha[1]),
                Step.UploadMeta("vault.cryptomator", 400, sha[8]), Step.UploadMeta("masterkey.cryptomator", 400, sha[9])),
            p.steps,
        )
        assertEquals(1100, p.uploadBytes)
        assertEquals(0, p.unchanged)
    }

    @Test
    fun `unchanged files produce no steps and stay in the snapshot`() {
        val files = listOf(f("d/AA/x.c9r", 0))
        val m = meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9)
        val last = snap(3, files, m)
        val p = plan(LocalState(files, m), last)
        assertTrue(p.isEmpty)
        assertEquals(4, p.seq)
        assertEquals(1, p.unchanged)
        assertEquals(listOf(SnapshotFile("d/AA/x.c9r", 100, sha[0])), p.files)
    }

    @Test
    fun `modified, new and deleted files`() {
        val m = meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9)
        val last = snap(1, listOf(f("d/AA/x.c9r", 0), f("d/AA/gone.c9r", 1)), m)
        val p = plan(LocalState(listOf(f("d/AA/x.c9r", 2), f("d/AA/new.c9r", 3)), m), last)
        assertEquals(
            listOf(
                Step.Retire("d/AA/x.c9r", sha[0], 100), Step.Retire("d/AA/gone.c9r", sha[1], 200),
                Step.Upload("d/AA/x.c9r", 300, sha[2]), Step.Upload("d/AA/new.c9r", 400, sha[3]),
            ),
            p.steps,
        )
        assertEquals(setOf("d/AA/x.c9r", "d/AA/new.c9r"), p.files.map { it.path }.toSet())
    }

    @Test
    fun `a rename is a retire plus a revive, not an upload`() {
        val m = meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9)
        val last = snap(1, listOf(f("d/AA/old.c9r", 0)), m)
        val p = plan(LocalState(listOf(f("d/BB/new.c9r", 0)), m), last)
        assertEquals(listOf(Step.Retire("d/AA/old.c9r", sha[0], 100), Step.Revive("d/BB/new.c9r", 100, sha[0])), p.steps)
        assertEquals(0, p.uploadBytes)
    }

    @Test
    fun `two copies of retired bytes revive once and upload once`() {
        val m = meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9)
        val last = snap(1, listOf(f("d/AA/a.c9r", 0)), m)
        val p = plan(LocalState(listOf(f("d/AA/b.c9r", 0), f("d/AA/c.c9r", 0)), m), last)
        assertEquals(listOf(Step.Retire("d/AA/a.c9r", sha[0], 100), Step.Revive("d/AA/b.c9r", 100, sha[0]), Step.Upload("d/AA/c.c9r", 100, sha[0])), p.steps)
    }

    @Test
    fun `meta files are re-uploaded only when their hash changes`() {
        val files = listOf(f("d/AA/x.c9r", 0))
        val last = snap(1, files, meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 9))
        val p = plan(LocalState(files, meta("vault.cryptomator" to 8, "masterkey.cryptomator" to 7)), last)
        assertEquals(listOf(Step.UploadMeta("masterkey.cryptomator", 400, sha[7])), p.steps)
    }

    @Test
    fun `writer check`() {
        assertIs<WriterCheck.FirstBackup>(checkWriter(null, 0))
        assertEquals(WriterCheck.Ours(4), checkWriter(Latest(4, sha[0]), 4))
        assertEquals(WriterCheck.Foreign(5, 4), checkWriter(Latest(5, sha[0]), 4))
        assertEquals(WriterCheck.Foreign(2, 0), checkWriter(Latest(2, sha[0]), 0))
    }

    @Test
    fun `latest round trip and validation`() {
        val l = Latest(12, sha[3])
        assertEquals("12\n${sha[3]}\n", l.format())
        assertEquals(l, Latest.parse(l.format()))
        assertEquals(l, Latest.parse("12\r\n${sha[3]}"))
        for (bad in listOf("", "12", "x\n${sha[3]}\n", "12\nnothex\n")) {
            var threw = false
            try { Latest.parse(bad) } catch (_: IllegalArgumentException) { threw = true }
            assertTrue(threw, "should reject: $bad")
        }
    }

    @Test
    fun `snapshot json round trip keeps the schema example shape`() {
        val s = Snapshot(seq = 12, vaultId = "8c5b2f0e-1", vaultName = "Personal", createdAt = "2026-09-17T09:15:02Z",
            installation = "b7e1", manifestGeneration = 42,
            meta = mapOf("vault.cryptomator" to SnapshotMeta(512, sha[1])),
            files = listOf(SnapshotFile("d/AB/x.c9r", 1834307, sha[2]), SnapshotFile("d/AB/old.c9r", 90211, sha[3], Stored.VERSIONS)))
        val text = s.encode()
        assertTrue("\"stored\":\"versions\"" in text && "\"schema\":1" in text)
        assertEquals(s, Snapshot.decode(text))
        assertEquals(1834307L + 90211 + 512, s.totalSize)
    }

    @Test
    fun `remote names`() {
        assertEquals("personal-8c5b2f0e", RemoteLayout.vaultFolderName("Personal", "8c5b2f0e-9c4d-4a56-9d1a-2f9a1c2b7e33"))
        assertEquals("vault-8c5b2f0e", RemoteLayout.vaultFolderName("§§§", "8c5b2f0e-9c4d"))
        assertEquals("cryptvault/snapshots/00000012.json.enc", RemoteLayout.snapshotPath(12))
        assertEquals(12L, RemoteLayout.snapshotSeq("00000012.json.enc"))
        assertEquals(null, RemoteLayout.snapshotSeq("latest"))
        assertEquals(sha[4], RemoteLayout.versionSha("${sha[4]}.c9r"))
        assertEquals(null, RemoteLayout.versionSha("dir.c9r"))
    }
}
