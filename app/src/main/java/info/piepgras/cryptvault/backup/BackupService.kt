package info.piepgras.cryptvault.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Iso8601
import info.piepgras.cryptvault.vault.BackupConfig
import info.piepgras.cryptvault.vault.SafVaultStorage
import info.piepgras.cryptvault.vault.VaultRecord
import info.piepgras.cryptvault.vault.VaultRegistry
import info.piepgras.cryptvault.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a vault's backup is doing right now, for the settings screen and the notification. */
data class BackupRun(val vaultId: String, val manual: Boolean, val progress: BackupProgress? = null)

/**
 * The orchestration of BUILD_BRIEF.md §6: per-vault configuration, the remote store for a
 * target, runs (from the UI or the worker), the debounced job after changes, notifications.
 * Runs work on the ciphertext alone, so a locked vault backs up too.
 */
class BackupService(
    private val context: Context,
    private val repository: VaultRepository,
    private val registry: VaultRegistry,
    val targets: BackupTargetStore,
    val snapshotKeys: SnapshotKeyStore,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val _running = MutableStateFlow<Map<String, BackupRun>>(emptyMap())
    val running: StateFlow<Map<String, BackupRun>> get() = _running

    /** Random per-installation id written into every snapshot (docs/VAULT_LAYOUT.md §6.1). */
    val installationId: String
        get() = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("installation", id) } }

    fun indexFor(vaultId: String) = BackupIndex(File(File(context.filesDir, "index"), vaultId))

    /** A store rooted at the vault folder inside the target's CryptVault area. */
    fun storeFor(target: BackupTarget, folder: String): RemoteStore = when (target) {
        is BackupTarget.WebDav -> WebDavStore(joinUrl(target.url, "$APP_DIR/$folder/"), target.user, target.password)
        is BackupTarget.Folder -> StorageRemoteStore(PrefixedVaultStorage(SafVaultStorage(context.contentResolver, target.treeUri.toUri()), "$APP_DIR/$folder"))
    }

    /** A store rooted at the target's CryptVault area, for listing vault folders (restore). */
    fun appRootStore(target: BackupTarget): RemoteStore = when (target) {
        is BackupTarget.WebDav -> WebDavStore(joinUrl(target.url, "$APP_DIR/"), target.user, target.password)
        is BackupTarget.Folder -> StorageRemoteStore(PrefixedVaultStorage(SafVaultStorage(context.contentResolver, target.treeUri.toUri()), APP_DIR))
    }

    /** Lists the vault folders a target holds: names like `personal-8c5b2f0e`. */
    fun listVaultFolders(target: BackupTarget): List<String> =
        appRootStore(target).list("").filter { it.isDirectory }.map { it.name }.sorted()

    // ---- configuration ----------------------------------------------------------------------

    /**
     * Switches backup on for a vault. Needs the raw masterkey once, to derive and wrap the
     * snapshot key; the caller zeroes [rawKey].
     */
    suspend fun enable(vaultId: String, targetId: String, keep: Int, unmeteredOnly: Boolean, rawKey: ByteArray) = withContext(Dispatchers.IO) {
        val record = registry.get(vaultId) ?: throw IOException("unknown vault")
        targets.get(targetId) ?: throw IOException("unknown target")
        snapshotKeys.store(vaultId, rawKey)
        val folder = record.backup?.takeIf { it.targetId == targetId }?.folder ?: RemoteLayout.vaultFolderName(record.name, record.id)
        if (record.backup?.targetId != targetId) indexFor(vaultId).wipe() // a new target starts from its own first snapshot
        registry.update(record.copy(backup = BackupConfig(targetId, folder, Retention.clampKeep(keep), unmeteredOnly, Iso8601.format(System.currentTimeMillis()))))
        enqueueManual(vaultId)
    }

    fun disable(vaultId: String) {
        cancel(vaultId)
        registry.get(vaultId)?.let { registry.update(it.copy(backup = null)) }
        snapshotKeys.remove(vaultId)
        indexFor(vaultId).wipe()
    }

    fun update(vaultId: String, keep: Int? = null, unmeteredOnly: Boolean? = null) {
        val record = registry.get(vaultId) ?: return
        val b = record.backup ?: return
        registry.update(record.copy(backup = b.copy(keep = keep?.let(Retention::clampKeep) ?: b.keep, unmeteredOnly = unmeteredOnly ?: b.unmeteredOnly)))
    }

    /** Forgets a target and switches off backup for every vault that used it. */
    fun removeTarget(targetId: String) {
        registry.records.value.filter { it.backup?.targetId == targetId }.forEach { disable(it.id) }
        targets.remove(targetId)
    }

    // ---- running ----------------------------------------------------------------------------

    /**
     * One run, on the calling coroutine (the worker or "Back up now"). Updates [running] and the
     * record's status; returns the outcome or throws what the run threw.
     */
    suspend fun runNow(vaultId: String, manual: Boolean, takeOver: Boolean = false, isCancelled: () -> Boolean = { false }): BackupOutcome = withContext(Dispatchers.IO) {
        val record = registry.get(vaultId) ?: throw IOException("unknown vault")
        val config = record.backup ?: throw IOException("backup is not set up for this vault")
        val target = targets.get(config.targetId) ?: throw IOException("the backup target is gone")
        val key = snapshotKeys.load(vaultId) ?: throw IOException("the backup key is missing — set backup up again")
        if (target is BackupTarget.WebDav && LocalNetwork.needsPermission(context, target.url)) {
            throw RemoteAuthException(context.getString(R.string.backup_webdav_local_denied))
        }
        if (_running.value.containsKey(vaultId)) throw IOException("a backup of this vault is already running")
        _running.update { it + (vaultId to BackupRun(vaultId, manual)) }
        try {
            val runner = BackupRunner(
                repository.storageFor(record), storeFor(target, config.folder), indexFor(vaultId), key,
                record.id, record.name, installationId, config.keep,
                manifestGeneration = { repository.open.value[vaultId]?.manifest?.value?.generation ?: 0 },
            )
            val outcome = runner.run(takeOver, isCancelled) { p -> _running.update { it + (vaultId to BackupRun(vaultId, manual, p)) } }
            recordOutcome(vaultId, outcome)
            outcome
        } catch (e: Exception) {
            recordFailure(vaultId, e)
            throw e
        } finally {
            key.fill(0)
            _running.update { it - vaultId }
        }
    }

    private fun recordOutcome(vaultId: String, outcome: BackupOutcome) {
        val record = registry.get(vaultId) ?: return
        val b = record.backup ?: return
        val state = indexFor(vaultId).state
        val next = when (outcome) {
            is BackupOutcome.Done -> b.copy(lastSeq = outcome.seq, lastSnapshotAt = state.lastSnapshotAt, lastRunAt = state.lastRunAt, lastError = outcome.gcError, foreignSeq = null, failures = 0)
            is BackupOutcome.NothingToDo -> b.copy(lastSeq = outcome.seq, lastRunAt = state.lastRunAt, lastError = null, foreignSeq = null, failures = 0)
            is BackupOutcome.Foreign -> b.copy(lastRunAt = state.lastRunAt, foreignSeq = outcome.remoteSeq, failures = 0)
        }
        registry.update(record.copy(backup = next))
        if (outcome is BackupOutcome.Foreign) notifyAlert(vaultId, context.getString(R.string.backup_notice_foreign, record.name))
    }

    private fun recordFailure(vaultId: String, e: Exception) {
        val record = registry.get(vaultId) ?: return
        val b = record.backup ?: return
        val failures = if (e is BackupCancelledException) b.failures else b.failures + 1
        registry.update(record.copy(backup = b.copy(lastRunAt = Iso8601.format(System.currentTimeMillis()), lastError = e.message ?: e.javaClass.simpleName, failures = failures)))
        if (failures >= 3 || e is RemoteAuthException) notifyAlert(vaultId, context.getString(R.string.backup_notice_failed, record.name, e.message ?: ""))
    }

    // ---- scheduling -------------------------------------------------------------------------

    /** A change happened: (re)start the debounce; unique per vault, replaced on every change. */
    fun scheduleAfterChange(vaultId: String) {
        val record = registry.get(vaultId) ?: return
        val config = record.backup ?: return
        val target = targets.get(config.targetId) ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (target.kind == BackupTarget.Kind.FOLDER) NetworkType.NOT_REQUIRED else if (config.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.KEY_VAULT to vaultId, BackupWorker.KEY_MANUAL to false))
            .setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .addTag(TAG_AUTO)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("backup-auto-$vaultId", ExistingWorkPolicy.REPLACE, request)
    }

    /** "Back up now": expedited, in the foreground with a progress notification. */
    fun enqueueManual(vaultId: String, takeOver: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.KEY_VAULT to vaultId, BackupWorker.KEY_MANUAL to true, BackupWorker.KEY_TAKE_OVER to takeOver))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED.takeIf { targets.get(registry.get(vaultId)?.backup?.targetId ?: "")?.kind != BackupTarget.Kind.FOLDER } ?: NetworkType.NOT_REQUIRED).build())
            .addTag(TAG_MANUAL)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("backup-now-$vaultId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(vaultId: String) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("backup-auto-$vaultId")
        wm.cancelUniqueWork("backup-now-$vaultId")
    }

    // ---- notifications ----------------------------------------------------------------------

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.backup_channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.backup_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun progressNotification(record: VaultRecord?, progress: BackupProgress?, restoring: Boolean = false): android.app.Notification {
        ensureChannels()
        val title = context.getString(if (restoring) R.string.restore_notice_running else R.string.backup_notice_running, record?.name ?: "")
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
        if (progress != null && progress.bytesTotal > 0) {
            builder.setProgress(1000, (progress.bytesDone * 1000 / progress.bytesTotal).toInt().coerceIn(0, 1000), false)
            builder.setContentText(context.getString(R.string.backup_notice_progress, info.piepgras.cryptvault.ui.Format.size(progress.bytesDone), info.piepgras.cryptvault.ui.Format.size(progress.bytesTotal)))
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun notifyAlert(vaultId: String, text: String) {
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.backup_channel_alerts))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(ALERT_ID_BASE + vaultId.hashCode(), n) }
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val APP_DIR = "CryptVault"
        const val DEBOUNCE_MINUTES = 3L
        const val CHANNEL_PROGRESS = "backup_progress"
        const val CHANNEL_ALERTS = "backup_alerts"
        const val TAG_AUTO = "backup-auto"
        const val TAG_MANUAL = "backup-manual"
        const val PROGRESS_ID = 4101
        const val RESTORE_ID = 4102
        private const val ALERT_ID_BASE = 4200

        fun joinUrl(base: String, rel: String): String = base.trimEnd('/') + "/" + rel.trimStart('/')

        /** Whether the WebDAV URL is one the debug network config lets through in the clear. */
        fun isCleartext(url: String): Boolean = url.startsWith("http://", ignoreCase = true)

        fun validWebDavUrl(url: String): Boolean = runCatching { url.toUri().let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() } }.getOrDefault(false)
    }
}
