package info.piepgras.cryptvault

import android.app.Application
import android.content.Context
import info.piepgras.cryptvault.items.AndroidThumbnails
import info.piepgras.cryptvault.openwith.OpenWith
import info.piepgras.cryptvault.security.SensitiveClipboard
import info.piepgras.cryptvault.unlock.BiometricWrap
import info.piepgras.cryptvault.unlock.LockManager
import info.piepgras.cryptvault.vault.VaultRegistry
import info.piepgras.cryptvault.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.launch

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
        val share = info.piepgras.cryptvault.share.ShareService(app, openWith)
        val lockManager = LockManager(app, repository, openWith)
        val clipboard = SensitiveClipboard(app)
        val biometricWrap = BiometricWrap(app)
        val backupTargets = info.piepgras.cryptvault.backup.BackupTargetStore(app)
        val snapshotKeys = info.piepgras.cryptvault.backup.SnapshotKeyStore(app)
        val backup = info.piepgras.cryptvault.backup.BackupService(app, repository, registry, backupTargets, snapshotKeys)
        val restore = info.piepgras.cryptvault.backup.RestoreService(app, registry, backup, File(app.filesDir, "vaults"))
        /** (vaultId, 44 words) waiting to be shown once by the recovery-key screen, then cleared. */
        val recoveryKeyToShow = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    }

    lateinit var container: Container
        private set
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Crash reporting stays off until the user has opted in; see CrashReporting.
        if (CrashReporting.isEnabled(this)) CrashReporting.init(this)
        container = Container(this)
        container.lockManager.start()
        // Every unlock or lock changes the roots the DocumentsProvider offers.
        appScope.launch { container.repository.open.collect { container.lockManager.notifyRoots() } }
        // Every change to an open vault's manifest (re)starts that vault's debounced backup.
        appScope.launch {
            val watchers = HashMap<String, kotlinx.coroutines.Job>()
            container.repository.open.collect { open ->
                (watchers.keys - open.keys).forEach { watchers.remove(it)?.cancel() }
                for ((id, vault) in open) if (id !in watchers) {
                    watchers[id] = launch {
                        var last = vault.manifest.value.generation
                        vault.manifest.collect { m ->
                            if (m.generation != last) { last = m.generation; container.backup.scheduleAfterChange(id) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun container(context: Context): Container = (context.applicationContext as CryptVaultApp).container
    }
}
