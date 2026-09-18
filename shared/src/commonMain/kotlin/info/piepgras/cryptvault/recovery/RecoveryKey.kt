// The checksum layout is ported from Cryptomator (https://github.com/cryptomator/cryptomator),
// file src/main/java/org/cryptomator/ui/recoverykey/RecoveryKeyFactory.java at commit
// e1d83c996e501649b2253741e4a80ce54646b317, GPL-3.0: the 64 raw masterkey bytes are followed
// by the first two bytes of Guava's CRC-32 HashCode, which is little-endian, i.e. the two
// *least* significant bytes of the CRC-32 value. The comment in the original says "most
// significant"; the bytes on disk say otherwise, and the bytes are the format (see the vectors
// in RecoveryKeyTest, produced by running the original code).
package info.piepgras.cryptvault.recovery

/**
 * A vault's recovery key: its 64-byte masterkey as 44 words. Interoperable with Cryptomator
 * desktop, so a key written down from either app resets the password in the other.
 */
object RecoveryKey {

    const val RAW_KEY_LENGTH = 64
    const val WORD_COUNT = 44

    /** The dictionary, for word-by-word validation in the UI. */
    val dictionary: List<String> get() = WordEncoder.words

    fun isDictionaryWord(word: String): Boolean = WordEncoder.isWord(word)

    /** Encodes a 64-byte masterkey as 44 words. The caller zeroes [rawKey] afterwards. */
    fun encode(rawKey: ByteArray): String {
        require(rawKey.size == RAW_KEY_LENGTH) { "key should be $RAW_KEY_LENGTH bytes" }
        val padded = rawKey.copyOf(RAW_KEY_LENGTH + 2)
        try {
            val crc = Crc32.of(rawKey)
            padded[RAW_KEY_LENGTH] = (crc and 0xFF).toByte()
            padded[RAW_KEY_LENGTH + 1] = ((crc ushr 8) and 0xFF).toByte()
            return WordEncoder.encodePadded(padded)
        } finally {
            padded.fill(0)
        }
    }

    /**
     * Decodes 44 words back to the 64-byte masterkey, or returns null when the words are not a
     * recovery key (wrong count, unknown word, checksum mismatch). Never throws on user input.
     */
    fun decode(words: String): ByteArray? {
        val padded = try {
            WordEncoder.decode(words)
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            if (padded.size != RAW_KEY_LENGTH + 2) return null
            val rawKey = padded.copyOf(RAW_KEY_LENGTH)
            val crc = Crc32.of(rawKey)
            val ok = padded[RAW_KEY_LENGTH] == (crc and 0xFF).toByte() &&
                padded[RAW_KEY_LENGTH + 1] == ((crc ushr 8) and 0xFF).toByte()
            if (!ok) {
                rawKey.fill(0)
                return null
            }
            return rawKey
        } finally {
            padded.fill(0)
        }
    }

    fun isValid(words: String): Boolean {
        val key = decode(words) ?: return false
        key.fill(0)
        return true
    }

    /**
     * Which of the typed words are not in the dictionary, by position — for the entry screen to
     * mark them while the user types. An empty list does not mean the key is valid.
     */
    fun unknownWords(typed: String): List<Int> =
        typed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .withIndex().filter { (_, w) -> !WordEncoder.isWord(w) }.map { it.index }
}

/** CRC-32 (ISO-HDLC, the one java.util.zip.CRC32 and Guava compute), in common code. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in bytes) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc xor 0xFFFFFFFF.toInt()
    }
}
