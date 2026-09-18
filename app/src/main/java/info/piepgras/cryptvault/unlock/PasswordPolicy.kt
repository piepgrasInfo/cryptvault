package info.piepgras.cryptvault.unlock

/**
 * The password gate for vault creation and change (BUILD_BRIEF.md §4.1). Phase 1 enforces the
 * length floor; Phase 2 adds the zxcvbn score and passphrase suggestions on top.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    data class Result(val ok: Boolean)

    fun check(password: CharSequence): Result = Result(ok = password.length >= MIN_LENGTH)
}
