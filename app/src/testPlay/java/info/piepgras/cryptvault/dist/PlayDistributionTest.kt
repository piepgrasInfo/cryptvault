package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google Play build: the unsuffixed applicationId (the one Play Console knows) and the
 * only build allowed to grow a Google-dependent backup target.
 */
class PlayDistributionTest {

    @Test
    fun `the play build declares itself and may link Play services`() {
        assertEquals("play", Distribution.id)
        assertTrue(Distribution.playServicesAllowed)
    }

    @Test
    fun `the play build keeps the applicationId the Play Console knows`() {
        assertEquals("info.piepgras.cryptvault", BuildConfig.APPLICATION_ID)
        assertFalse(BuildConfig.APPLICATION_ID.endsWith(".foss"))
    }
}
