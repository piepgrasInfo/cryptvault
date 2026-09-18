package info.piepgras.cryptvault.prefs

import android.content.Context
import androidx.core.content.edit
import info.piepgras.cryptvault.unlock.Backoff

/** The failed-attempt backoff state per vault, persisted so it survives a process restart. */
object UnlockPrefs {
    private const val FILE = "unlock"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun backoff(context: Context, vaultId: String): Backoff.State {
        val p = prefs(context)
        return Backoff.State(p.getInt("$vaultId.failures", 0), p.getLong("$vaultId.waitUntil", 0L))
    }

    fun setBackoff(context: Context, vaultId: String, state: Backoff.State) = prefs(context).edit {
        if (state.failures == 0) {
            remove("$vaultId.failures"); remove("$vaultId.waitUntil")
        } else {
            putInt("$vaultId.failures", state.failures); putLong("$vaultId.waitUntil", state.waitUntil)
        }
    }
}
