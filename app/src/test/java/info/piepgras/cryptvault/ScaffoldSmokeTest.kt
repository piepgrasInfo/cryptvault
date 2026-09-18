package info.piepgras.cryptvault

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exists so `./gradlew :app:testDebugUnitTest` is green from the first commit:
 * Gradle fails the task outright when a test source set contains no tests, and a
 * red build on day one teaches everyone to ignore the build.
 *
 * Delete it as soon as there is a real test.
 */
class ScaffoldSmokeTest {

    @Test
    fun `unit tests run`() {
        assertTrue(true)
    }
}
