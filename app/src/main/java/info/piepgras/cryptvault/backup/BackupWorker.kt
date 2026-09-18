package info.piepgras.cryptvault.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import info.piepgras.cryptvault.CryptVaultApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs one backup of one vault. Automatic runs (after changes) stay in the background and give
 * up with a retry when the network or the server is not there; manual runs ("Back up now")
 * hold a `dataSync` foreground service with a progress notification for as long as they last
 * (BUILD_BRIEF.md §6.4). Three failures in a row raise one alert notification.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val vaultId = inputData.getString(KEY_VAULT) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val takeOver = inputData.getBoolean(KEY_TAKE_OVER, false)
        val container = CryptVaultApp.container(applicationContext)
        val service = container.backup
        val record = container.registry.get(vaultId)
        if (record?.backup == null) return Result.success()
        var progressJob: Job? = null
        if (manual) {
            runCatching { setForeground(foregroundInfo(service, record, null)) }
            progressJob = CoroutineScope(coroutineContext).launch {
                service.running.map { it[vaultId]?.progress }.collect { p ->
                    if (p != null) runCatching { setForeground(foregroundInfo(service, record, p)) }
                }
            }
        }
        return try {
            when (service.runNow(vaultId, manual, takeOver, isCancelled = { isStopped })) {
                is BackupOutcome.Done, is BackupOutcome.NothingToDo, is BackupOutcome.Foreign -> Result.success()
            }
        } catch (e: BackupCancelledException) {
            Result.failure()
        } catch (e: RemoteAuthException) {
            Result.failure() // credentials: retrying does not help; the alert was raised
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            progressJob?.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val container = CryptVaultApp.container(applicationContext)
        val record = inputData.getString(KEY_VAULT)?.let { container.registry.get(it) }
        return foregroundInfo(container.backup, record, null)
    }

    private fun foregroundInfo(service: BackupService, record: info.piepgras.cryptvault.vault.VaultRecord?, progress: BackupProgress?): ForegroundInfo {
        val notification = service.progressNotification(record, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(BackupService.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(BackupService.PROGRESS_ID, notification)
        }
    }

    companion object {
        const val KEY_VAULT = "vault"
        const val KEY_MANUAL = "manual"
        const val KEY_TAKE_OVER = "takeOver"
    }
}
