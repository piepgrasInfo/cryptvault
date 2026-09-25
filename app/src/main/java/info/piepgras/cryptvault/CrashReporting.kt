package info.piepgras.cryptvault

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import androidx.core.content.edit

/**
 * Opt-in wrapper around the Sentry/Bugsink crash reporter. The manifest disables Sentry's
 * auto-init, so by default no crash reports are sent anywhere; [init] turns it on and
 * [close] turns it back off, driven by the user's choice in Settings.
 *
 * Whatever this ends up sending has to match legal/privacy-policy.md word for word on the
 * facts - what a report contains, where it goes, and how long it is kept.
 */
object CrashReporting {

    // The DSN comes from the gitignored providers.properties (BuildConfig.BUGSINK_DSN), like the
    // provider keys: the repository is public, and the DSN names the reporting server and
    // authorises sending to it. It is not a secret in any strong sense - it ships inside every
    // APK built with it and can be read out of one - so this keeps it out of the indexed source,
    // no more; the guard against abuse is on the server. A build without it (an F-Droid build
    // from the public source) has no crash reporting: [isAvailable] is false, the opt-in question
    // is never asked and the SDK never starts.
    //
    // Kept out of the manifest on purpose: with the DSN in the manifest the SDK could find it and
    // start itself from its own ContentProvider before the user has had any chance to refuse, if
    // io.sentry.auto-init were ever accidentally re-enabled.
    private val DSN: String get() = BuildConfig.BUGSINK_DSN.trim()

    /** False when this build carries no DSN; nothing crash-related is shown or started then. */
    val isAvailable: Boolean get() = DSN.isNotEmpty()

    private const val PREFS = "crash_reporting"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASKED = "asked"

    @Volatile
    private var initialized = false

    /** True once the user has been shown the opt-in question, whatever they answered. */
    fun hasBeenAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Records the user's answer and starts or stops the SDK to match it immediately. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, enabled)
            putBoolean(KEY_ASKED, true)
        }
        if (enabled) init(context) else close()
    }

    fun init(context: Context) {
        if (initialized || !isAvailable) return
        SentryAndroid.init(context) { options ->
            options.dsn = DSN

            // Bugsink ingests neither traces nor profiles, so sending them is waste on the wire
            // and noise on the server.
            options.tracesSampleRate = 0.0
            options.profilesSampleRate = 0.0

            // Release health pings on every foreground/background transition. An offline app has
            // no reason to tell a server it was opened.
            options.isEnableAutoSessionTracking = false
            options.isSendClientReports = false

            // No PII: self-hosting changes who holds the data, not whether it is personal data.
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.setDiagnosticLevel(SentryLevel.ERROR)

            // Clear anything that could identify the installation before the event goes out.
            options.setBeforeSend { event, _ ->
                event.user = null
                event.serverName = null
                event
            }
        }
        initialized = true
    }

    fun close() {
        if (!initialized) return
        Sentry.close()
        initialized = false
    }
}
