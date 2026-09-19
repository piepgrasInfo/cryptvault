package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.BuildConfig
import info.piepgras.cryptvault.backup.BackupTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules both distributions obey. The per-flavor halves are in `src/testPlay` and
 * `src/testFoss`; this one runs for either (BUILD_BRIEF.md §13).
 */
class DistributionTest {

    @Test
    fun `a build that may not link Play services offers no Google target`() {
        if (!Distribution.playServicesAllowed) {
            for (kind in Distribution.googleBackupTargets) {
                assertTrue("$kind must not be offered by the ${Distribution.id} build", kind !in Distribution.backupTargets)
            }
        }
    }

    @Test
    fun `every build offers the two targets that need no provider registration`() {
        assertTrue(Distribution.offers(BackupTarget.Kind.WEBDAV))
        assertTrue(Distribution.offers(BackupTarget.Kind.FOLDER))
    }

    @Test
    fun `the target list has no duplicates`() {
        assertEquals(Distribution.backupTargets.size, Distribution.backupTargets.toSet().size)
    }

    @Test
    fun `the distribution id is one of the two flavours and matches the build config`() {
        assertTrue(Distribution.id in setOf("play", "foss"))
        assertEquals(BuildConfig.DISTRIBUTION, Distribution.id)
        assertEquals(BuildConfig.PLAY_SERVICES_ALLOWED, Distribution.playServicesAllowed)
    }
}
