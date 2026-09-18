package info.piepgras.cryptvault

import android.app.Application

/**
 * Process-wide setup. Deliberately thin: anything that can wait until a screen
 * needs it should wait, because work here is on the critical path of every cold
 * start, including the ones where the user only wanted the widget.
 */
class CryptVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Crash reporting stays off until the user has opted in; see CrashReporting.
        if (CrashReporting.isEnabled(this)) CrashReporting.init(this)
    }
}
