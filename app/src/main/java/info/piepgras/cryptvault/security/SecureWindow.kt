package info.piepgras.cryptvault.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.window.SecureFlagPolicy
import info.piepgras.cryptvault.BuildConfig
import java.io.File

/**
 * Marks a composable as carrying secrets: hidden from screen sharing on Android 15+
 * (`sensitiveContent`) and flagged for accessibility services on Android 16+ (`isSensitiveData`,
 * so only real accessibility tools may read it). Used on password fields, recovery words and
 * note text (BUILD_BRIEF.md §4.4, docs/THREAT_MODEL.md T4).
 */
fun Modifier.sensitive(): Modifier = this.sensitiveContent().semantics { isSensitiveData = true }

/**
 * Screen protection (BUILD_BRIEF.md §4.4): FLAG_SECURE on the Activity window blocks screenshots,
 * screen recording and screen sharing and blanks the recents thumbnail; overlay windows are
 * hidden on API 31+ so nothing can be drawn over the unlock screen.
 *
 * Debug builds only: a file `cache/allow-screenshots` (`adb shell run-as … touch`) turns the flag
 * off so automated UI runs can capture the screen. Release builds ignore the file.
 */
object SecureWindow {

    fun screenshotsAllowed(activity: Activity): Boolean =
        BuildConfig.DEBUG && File(activity.cacheDir, "allow-screenshots").exists()

    fun apply(activity: Activity) {
        val window = activity.window
        if (screenshotsAllowed(activity)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Declared in the manifest (HIDE_OVERLAY_WINDOWS, a normal permission); guarded anyway so a
        // platform that refuses it costs the overlay protection, not the app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { window.setHideOverlayWindows(true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
    }

    /** For Compose dialogs and popups, which are separate windows. */
    fun dialogPolicy(activity: Activity?): SecureFlagPolicy =
        if (activity != null && screenshotsAllowed(activity)) SecureFlagPolicy.SecureOff else SecureFlagPolicy.SecureOn
}
