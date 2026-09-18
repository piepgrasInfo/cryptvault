package info.piepgras.cryptvault.recovery

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vectors were produced on 2026-09-18 by compiling Cryptomator's original WordEncoder.java
 * (commit e1d83c99) against Guava 33.5.0 and running RecoveryKeyFactory's createRecoveryKey
 * logic on the three keys below. They are what a desktop Cryptomator shows for these keys.
 */
class RecoveryKeyTest {

    private val zeroKey = ByteArray(64)
    private val zeroWords = "ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad ad " +
        "ad ad ad ad ad ad ad ad ad ad ad ad ad at assume"

    private val countingKey = ByteArray(64) { it.toByte() }
    private val countingWords = "ad back bin enter gym gentle own intense van resident sin oh boot dumb debt stake " +
        "flag tenure hers worship life similarly nail open pray thick shoe visual tend counter warn scenario " +
        "cave cash jury grass shed league allow obvious build transfer dream normally"

    private val mixedKey = ByteArray(64) { (0xA5 xor (it * 37)).toByte() }
    private val mixedWords = "pleased rob hospital boil return carpet community labour gate point refuge haunt " +
        "depth opposed tonight testify intimate costly funny contest worldwide operator rival hostility timber " +
        "peaceful hit weakness highway bail post spam white row gathering beef decade demand confirm indeed pin " +
        "label slavery sadly"

    @Test
    fun `dictionary is the 4096-word Cryptomator list`() {
        assertEquals(4096, RecoveryKey.dictionary.size)
        assertEquals("ad", RecoveryKey.dictionary.first())
        assertEquals("residence", RecoveryKey.dictionary.last())
        assertEquals(4096, RecoveryKey.dictionary.toSet().size)
    }

    @Test
    fun `encodes the desktop vectors`() {
        assertEquals(zeroWords, RecoveryKey.encode(zeroKey))
        assertEquals(countingWords, RecoveryKey.encode(countingKey))
        assertEquals(mixedWords, RecoveryKey.encode(mixedKey))
        assertEquals(RecoveryKey.WORD_COUNT, mixedWords.split(" ").size)
    }

    @Test
    fun `decodes the desktop vectors`() {
        assertContentEquals(zeroKey, RecoveryKey.decode(zeroWords))
        assertContentEquals(countingKey, RecoveryKey.decode(countingWords))
        assertContentEquals(mixedKey, RecoveryKey.decode(mixedWords))
    }

    @Test
    fun `crc32 matches the reference value for 64 zero bytes`() {
        // Guava's Hashing.crc32() reported 1972200246 for the zero key; the low two bytes are 0x36 0x63.
        assertEquals(1972200246, Crc32.of(zeroKey))
    }

    @Test
    fun `tolerates line breaks and extra spaces`() {
        val messy = mixedWords.replace(" ", "\n  ")
        assertContentEquals(mixedKey, RecoveryKey.decode("  $messy \n"))
    }

    @Test
    fun `rejects a swapped word, a wrong word, a missing word and a corrupt checksum`() {
        val words = mixedWords.split(" ")
        val swapped = words.toMutableList().also { val t = it[3]; it[3] = it[4]; it[4] = t }
        assertNull(RecoveryKey.decode(swapped.joinToString(" ")))
        assertNull(RecoveryKey.decode(mixedWords.replace("hospital", "hospitals")))
        assertNull(RecoveryKey.decode(words.drop(2).joinToString(" ")))
        val corruptChecksum = words.toMutableList().also { it[43] = "ad" }
        assertNull(RecoveryKey.decode(corruptChecksum.joinToString(" ")))
        assertFalse(RecoveryKey.isValid(""))
        assertTrue(RecoveryKey.isValid(mixedWords))
    }

    @Test
    fun `round trips random keys`() {
        val rnd = kotlin.random.Random(20260918)
        repeat(200) {
            val key = rnd.nextBytes(64)
            val words = RecoveryKey.encode(key)
            assertEquals(44, words.split(" ").size)
            assertContentEquals(key, RecoveryKey.decode(words))
        }
    }

    @Test
    fun `reports unknown words by position`() {
        assertEquals(listOf(1, 3), RecoveryKey.unknownWords("ad xyzzy ah plugh"))
        assertEquals(emptyList(), RecoveryKey.unknownWords(mixedWords))
    }

    @Test
    fun `refuses a key of the wrong length`() {
        val e = runCatching { RecoveryKey.encode(ByteArray(32)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
