package info.piepgras.cryptvault.unlock

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffTest {
    @Test
    fun `first failure is free, then 2 s doubling, capped at 5 min`() {
        assertEquals(0L, Backoff.delayAfter(1))
        assertEquals(2_000L, Backoff.delayAfter(2))
        assertEquals(4_000L, Backoff.delayAfter(3))
        assertEquals(8_000L, Backoff.delayAfter(4))
        assertEquals(256_000L, Backoff.delayAfter(9))
        assertEquals(300_000L, Backoff.delayAfter(10))
        assertEquals(300_000L, Backoff.delayAfter(50))
        assertEquals(300_000L, Backoff.delayAfter(1000))
    }

    @Test
    fun `state accumulates and resets`() {
        var s = Backoff.State()
        s = Backoff.onFailure(s, 1_000L)
        assertEquals(1, s.failures)
        assertEquals(0L, Backoff.remaining(s, 1_000L))
        s = Backoff.onFailure(s, 1_000L)
        assertEquals(2_000L, Backoff.remaining(s, 1_000L))
        assertEquals(500L, Backoff.remaining(s, 2_500L))
        assertEquals(0L, Backoff.remaining(s, 3_000L))
        s = Backoff.onFailure(s, 3_000L)
        assertEquals(4_000L, Backoff.remaining(s, 3_000L))
        assertEquals(Backoff.State(), Backoff.onSuccess())
    }
}
