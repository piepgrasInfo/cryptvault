package info.piepgras.cryptvault.unlock

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassphraseTest {
    @Test
    fun `the list is the EFF long list`() {
        assertEquals(7776, Passphrase.WORD_COUNT)
        assertTrue(Passphrase.isFromList("abacus abdomen abdominal abide abiding ability"))
        assertTrue(Passphrase.entropyBits(6) > 77.0 && Passphrase.entropyBits(6) < 77.6)
    }

    @Test
    fun `generates six words from the list, differently each time`() {
        val rnd = Random(1)
        val a = Passphrase.generate(rnd)
        val b = Passphrase.generate(rnd)
        assertEquals(6, a.split(" ").size)
        assertTrue(Passphrase.isFromList(a))
        assertTrue(a != b)
        assertEquals(8, Passphrase.generate(rnd, 8).split(" ").size)
    }

    @Test
    fun `recognises what is and is not a list passphrase`() {
        assertFalse(Passphrase.isFromList("abacus abdomen"))
        assertFalse(Passphrase.isFromList("abacus abdomen abdominal abide abiding notaword"))
        assertTrue(Passphrase.isFromList("  Abacus   abdomen abdominal abide abiding ability \n"))
    }
}
