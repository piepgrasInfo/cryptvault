package info.piepgras.cryptvault.unlock

/**
 * The delay after repeated wrong passwords (BUILD_BRIEF.md §4.1): starts at 2 s, doubles, caps at
 * 5 minutes, per vault, and survives process restarts because the caller stores [State]. No wipe,
 * ever. Pure, so the schedule is tested on the JVM.
 */
object Backoff {

    const val FIRST_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    /** Failures so far and when the current wait ends (epoch millis, 0 = none). */
    data class State(val failures: Int = 0, val waitUntil: Long = 0L)

    /** The delay imposed after the n-th consecutive failure (n ≥ 1). The first failure costs nothing. */
    fun delayAfter(failures: Int): Long = when {
        failures <= 1 -> 0L
        else -> minOf(MAX_DELAY_MS, FIRST_DELAY_MS shl minOf(failures - 2, 20))
    }

    fun onFailure(state: State, now: Long): State {
        val failures = state.failures + 1
        return State(failures, now + delayAfter(failures))
    }

    fun onSuccess(): State = State()

    /** Milliseconds still to wait, or 0 when an attempt is allowed. */
    fun remaining(state: State, now: Long): Long = maxOf(0L, state.waitUntil - now)
}
