// Ported from Cryptomator (https://github.com/cryptomator/cryptomator), file
// src/main/java/org/cryptomator/ui/recoverykey/WordEncoder.java at commit
// e1d83c996e501649b2253741e4a80ce54646b317, GPL-3.0. The bit layout is the format: three bytes
// become two 12-bit dictionary indices, most significant bits first.
package info.piepgras.cryptvault.recovery

/**
 * Spells bytes as words from [RECOVERY_WORDS] and back. Every three bytes become two words, so
 * the input must be padded to a multiple of three.
 */
internal object WordEncoder {

    private const val WORD_COUNT = 4096
    private const val DELIMITER = ' '

    private val indices: Map<String, Int> = RECOVERY_WORDS.withIndex().associate { (i, w) -> w to i }

    init {
        require(RECOVERY_WORDS.size == WORD_COUNT) { "dictionary has ${RECOVERY_WORDS.size} words, expected $WORD_COUNT" }
        require(indices.size == WORD_COUNT) { "dictionary contains duplicates" }
    }

    /** All words, in index order. */
    val words: List<String> get() = RECOVERY_WORDS

    /** True when [word] is in the dictionary; used for per-word validation while typing. */
    fun isWord(word: String): Boolean = indices.containsKey(word)

    fun encodePadded(input: ByteArray): String {
        require(input.size % 3 == 0) { "input needs to be padded to a multiple of three" }
        val sb = StringBuilder()
        var i = 0
        while (i < input.size) {
            val b1 = input[i].toInt()
            val b2 = input[i + 1].toInt()
            val b3 = input[i + 2].toInt()
            val firstWordIndex = (0xFF0 and (b1 shl 4)) + (0x00F and (b2 shr 4))
            val secondWordIndex = (0xF00 and (b2 shl 8)) + (0x0FF and b3)
            sb.append(RECOVERY_WORDS[firstWordIndex]).append(DELIMITER)
            sb.append(RECOVERY_WORDS[secondWordIndex]).append(DELIMITER)
            i += 3
        }
        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /**
     * Decodes a word sequence produced by [encodePadded]. Whitespace runs of any kind separate
     * words, so a key pasted with line breaks decodes too.
     *
     * @throws IllegalArgumentException if the word count is odd or a word is not in the dictionary
     */
    fun decode(encoded: String): ByteArray {
        val split = encoded.split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(split.size % 2 == 0) { "needs to be a multiple of two words" }
        val result = ByteArray(split.size / 2 * 3)
        var i = 0
        while (i < split.size) {
            val w1 = split[i]
            val w2 = split[i + 1]
            val firstWordIndex = indices[w1] ?: throw IllegalArgumentException("$w1 not in dictionary")
            val secondWordIndex = indices[w2] ?: throw IllegalArgumentException("$w2 not in dictionary")
            result[i / 2 * 3] = (0xFF and (firstWordIndex shr 4)).toByte()
            result[i / 2 * 3 + 1] = ((0xF0 and (firstWordIndex shl 4)) + (0x0F and (secondWordIndex shr 8))).toByte()
            result[i / 2 * 3 + 2] = (0xFF and secondWordIndex).toByte()
            i += 2
        }
        return result
    }
}
