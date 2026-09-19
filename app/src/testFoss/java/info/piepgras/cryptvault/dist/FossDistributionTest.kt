package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Play-services-free build must be: its own applicationId, so it installs beside the
 * Play build instead of colliding with it, and not one backup target that needs Google Play
 * services on the device.
 */
class FossDistributionTest {

    @Test
    fun `the foss build declares itself and forbids Play services`() {
        assertEquals("foss", Distribution.id)
        assertFalse(Distribution.playServicesAllowed)
    }

    @Test
    fun `the foss build carries the suffixed applicationId`() {
        assertTrue(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID.endsWith(".foss"))
    }

    @Test
    fun `the foss build offers no Google target`() {
        assertTrue(Distribution.backupTargets.none { it in Distribution.googleBackupTargets })
    }
}
