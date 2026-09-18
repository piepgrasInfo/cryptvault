package info.piepgras.cryptvault.unlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import info.piepgras.cryptvault.openwith.OpenWith
import info.piepgras.cryptvault.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locks vaults when the app has been in the background for their timeout, and all of them on
 * screen-off (BUILD_BRIEF.md §4.2). Locking destroys the key in memory and wipes that vault's
 * hand-off files. Phase 2 adds the biometric wrap and the DocumentsProvider revocation hooks.
 */
class LockManager(
    private val context: Context,
    private val repository: VaultRepository,
    private val openWith: OpenWith,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = HashMap<String, Job>()
    @Volatile var inForeground = false
        private set

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lockAll()
        }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        openWith.wipeAll() // a process start means every previous hand-off is dead
    }

    override fun onStart(owner: LifecycleOwner) {
        inForeground = true
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    override fun onStop(owner: LifecycleOwner) {
        inForeground = false
        for (id in repository.open.value.keys) scheduleLock(id)
    }

    private fun scheduleLock(id: String) {
        val seconds = repository.record(id)?.autoLockSeconds ?: 60
        pending[id]?.cancel()
        pending[id] = scope.launch {
            if (seconds > 0) delay(seconds * 1000L)
            lock(id)
        }
    }

    fun lock(id: String) {
        pending.remove(id)?.cancel()
        repository.lock(id)
        openWith.wipe(id)
    }

    fun lockAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        repository.lockAll()
        openWith.wipeAll()
    }
}
