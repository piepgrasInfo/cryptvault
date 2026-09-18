package info.piepgras.cryptvault.unlock

import com.nulabinc.zxcvbn.Zxcvbn
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * The password gate for vault creation and change (BUILD_BRIEF.md §4.1): zxcvbn score 3 or
 * better and at least [MIN_LENGTH] characters. [suggest] offers six EFF words. The zxcvbn
 * estimate is what the meter shows; the length floor catches the short-but-random case zxcvbn
 * scores well.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10
    const val MIN_SCORE = 3

    data class Result(
        val ok: Boolean,
        /** zxcvbn score 0..4. */
        val score: Int,
        /** zxcvbn's one-line suggestion or warning, English, may be empty. */
        val advice: String,
        val tooShort: Boolean,
    )

    private val zxcvbn by lazy { Zxcvbn() }
    private val random by lazy { SecureRandom().asKotlinRandom() }

    fun check(password: CharSequence): Result {
        if (password.isEmpty()) return Result(false, 0, "", true)
        val strength = zxcvbn.measure(password)
        val tooShort = password.length < MIN_LENGTH
        val feedback = strength.feedback
        val advice = feedback.warning?.takeIf { it.isNotBlank() } ?: feedback.suggestions.firstOrNull() ?: ""
        return Result(ok = !tooShort && strength.score >= MIN_SCORE, score = strength.score, advice = advice, tooShort = tooShort)
    }

    /** Six EFF words, ~77 bits. */
    fun suggest(): String = Passphrase.generate(random)
}
