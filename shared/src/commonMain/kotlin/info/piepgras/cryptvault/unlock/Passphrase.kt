package info.piepgras.cryptvault.unlock

import kotlin.random.Random

/**
 * Passphrases from the EFF long list: for the "Suggest a passphrase" button (BUILD_BRIEF.md §4.1)
 * and for mailed containers (§7.2). Six words is the default: ~77 bits, which is what a ZIP's
 * weak PBKDF2 needs to be safe against offline guessing. The caller passes a cryptographically
 * secure [Random] (`SecureRandom().asKotlinRandom()` on the JVM).
 */
object Passphrase {

    const val DEFAULT_WORDS = 6
    const val SEPARATOR = " "
    val WORD_COUNT: Int get() = EFF_WORDS.size

    fun generate(random: Random, words: Int = DEFAULT_WORDS): String =
        List(words) { EFF_WORDS[random.nextInt(EFF_WORDS.size)] }.joinToString(SEPARATOR)

    /** Bits of entropy of a generated passphrase with [words] words. */
    fun entropyBits(words: Int): Double = words * log2(EFF_WORDS.size.toDouble())

    /** True when [phrase] consists of at least [minWords] words from the list. */
    fun isFromList(phrase: String, minWords: Int = DEFAULT_WORDS): Boolean {
        val parts = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.size >= minWords && parts.all { it.lowercase() in EFF_SET }
    }

    private val EFF_SET: Set<String> by lazy { EFF_WORDS.toHashSet() }

    private fun log2(x: Double): Double = kotlin.math.ln(x) / kotlin.math.ln(2.0)
}
