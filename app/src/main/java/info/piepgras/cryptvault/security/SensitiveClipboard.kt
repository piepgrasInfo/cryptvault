package info.piepgras.cryptvault.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clipboard hygiene (BUILD_BRIEF.md §4.4): copies made by the app are flagged sensitive (no
 * preview in the system clipboard UI on API 33+) and cleared after [CLEAR_AFTER_MS] if the clip
 * is still ours.
 */
class SensitiveClipboard(private val context: Context) {

    companion object {
        const val CLEAR_AFTER_MS = 60_000L
        private const val LABEL = "CryptVault"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pending: Job? = null

    fun copy(text: CharSequence) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
        pending?.cancel()
        pending = scope.launch {
            delay(CLEAR_AFTER_MS)
            clearIfOurs()
        }
    }

    /** Clears the clipboard when the current clip is one we placed. */
    fun clearIfOurs() {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val current = runCatching { manager.primaryClipDescription }.getOrNull() ?: return
        if (current.label == LABEL) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip()
            else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
