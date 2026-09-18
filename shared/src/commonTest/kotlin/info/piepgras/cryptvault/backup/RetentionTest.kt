package info.piepgras.cryptvault.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetentionTest {
    private val sha = (0..9).map { "%02x".format(it).repeat(32) }
    private fun snap(seq: Long, vararg hashes: Int) = Snapshot(seq = seq, vaultId = "v", vaultName = "V", createdAt = "t", installation = "i",
        files = hashes.map { SnapshotFile("d/$seq/$it.c9r", 10, sha[it]) })

    @Test
    fun `the sixth snapshot removes the first and exactly the unreferenced versions`() {
        val snaps = listOf(snap(1, 0, 1), snap(2, 0, 2), snap(3, 0, 3), snap(4, 0, 4), snap(5, 0, 5), snap(6, 0, 6))
        // versions/ holds the retired bytes of every superseded file so far: 1..5
        val versions = listOf(sha[1], sha[2], sha[3], sha[4], sha[5])
        val gc = Retention.gc(snaps.map { it.seq }, snaps.drop(1), keep = 5, versionsPresent = versions)
        assertEquals(listOf(1L), gc.deleteSnapshots)
        assertEquals(listOf(sha[1]), gc.deleteVersions) // only snapshot 1 referenced sha[1]
    }

    @Test
    fun `nothing to collect below the limit`() {
        val snaps = listOf(snap(1, 0), snap(2, 1))
        val gc = Retention.gc(listOf(1, 2), snaps, 5, listOf(sha[0]))
        assertTrue(gc.isEmpty)
    }

    @Test
    fun `a version referenced by any retained snapshot survives even if the mirror has the same bytes`() {
        val snaps = listOf(snap(7, 0), snap(8, 1), snap(9, 0))
        val gc = Retention.gc(listOf(7, 8, 9), snaps, 2, listOf(sha[0], sha[1], sha[2]))
        assertEquals(listOf(7L), gc.deleteSnapshots)
        assertEquals(listOf(sha[2]), gc.deleteVersions)
    }

    @Test
    fun `keep is clamped and gc refuses to run without the retained manifests`() {
        assertEquals(listOf(4L, 5L), Retention.retainedSeqs(listOf(5, 3, 4, 1, 2), keep = 2))
        assertEquals(1, Retention.clampKeep(0)); assertEquals(20, Retention.clampKeep(99))
        var threw = false
        try { Retention.gc(listOf(1, 2, 3), listOf(snap(3, 0)), 2, emptyList()) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `restore locates bytes in the mirror by hash, else in versions`() {
        val newest = snap(9, 0, 1)
        val idx = Locate.mirrorIndex(newest)
        assertEquals("d/9/0.c9r", Locate.remotePath(SnapshotFile("d/3/renamed.c9r", 10, sha[0]), idx))
        assertEquals(RemoteLayout.versionPath(sha[5]), Locate.remotePath(SnapshotFile("d/3/x.c9r", 10, sha[5]), idx))
    }
}
