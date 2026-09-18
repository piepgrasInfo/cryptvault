package info.piepgras.cryptvault

import android.app.Application
import android.content.Context
import info.piepgras.cryptvault.items.AndroidThumbnails
import info.piepgras.cryptvault.openwith.OpenWith
import info.piepgras.cryptvault.unlock.LockManager
import info.piepgras.cryptvault.vault.VaultRegistry
import info.piepgras.cryptvault.vault.VaultRepository
import java.io.File

/**
 * Process-wide setup. Deliberately thin: anything that can wait until a screen
 * needs it should wait, because work here is on the critical path of every cold start.
 */
class CryptVaultApp : Application() {

    /** Manual construction, one instance per process (CLAUDE.md: no DI framework). */
    class Container(val app: Application) {
        val resolver: android.content.ContentResolver = app.contentResolver
        val registry = VaultRegistry(File(app.filesDir, "vaults.json"))
        val thumbnails = AndroidThumbnails(app)
        val repository = VaultRepository(app.contentResolver, registry, File(app.filesDir, "vaults"), thumbnails)
        val openWith = OpenWith(app)
        val lockManager = LockManager(app, repository, openWith)
    }

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        // Crash reporting stays off until the user has opted in; see CrashReporting.
        if (CrashReporting.isEnabled(this)) CrashReporting.init(this)
        container = Container(this)
        container.lockManager.start()
    }

    companion object {
        fun container(context: Context): Container = (context.applicationContext as CryptVaultApp).container
    }
}
