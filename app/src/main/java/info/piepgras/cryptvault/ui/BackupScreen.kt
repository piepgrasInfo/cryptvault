package info.piepgras.cryptvault.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.backup.BackupProgress
import info.piepgras.cryptvault.backup.BackupTarget
import info.piepgras.cryptvault.backup.Retention
import info.piepgras.cryptvault.dist.Distribution
import info.piepgras.cryptvault.items.Iso8601
import info.piepgras.cryptvault.vault.BackupConfig
import info.piepgras.cryptvault.vault.VaultRecord
import info.piepgras.cryptvault.vault.WrongPasswordException
import kotlinx.coroutines.launch

private sealed class BackupDialog {
    object WebDav : BackupDialog()
    object Keep : BackupDialog()
    object SwitchOff : BackupDialog()
    data class ForgetTarget(val target: BackupTarget) : BackupDialog()
    /** Password check before enabling backup on a locked vault; [targetId] is what was chosen. */
    data class Password(val targetId: String) : BackupDialog()
}

/**
 * A vault's backup: pick or connect a target, switch backup on, watch runs, adjust retention
 * and the network rule, resolve a foreign writer (BUILD_BRIEF.md paragraphs 6 and 9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vaultId: String, onBack: () -> Unit, onRestoreFrom: (targetId: String, folder: String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val repository = container.repository
    val service = container.backup
    val records by repository.records.collectAsState()
    val record = records.firstOrNull { it.id == vaultId }
    if (record == null) {
        onBack()
        return
    }
    val open by repository.open.collectAsState()
    val running by service.running.collectAsState()
    val run = running[vaultId]
    var targets by remember { mutableStateOf(service.targets.all()) }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var chosenTarget by remember { mutableStateOf(record.backup?.targetId ?: targets.firstOrNull()?.id) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val config = record.backup

    fun refreshTargets() {
        targets = service.targets.all()
        if (chosenTarget == null) chosenTarget = targets.firstOrNull()?.id
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { service.enqueueManual(vaultId) }
    fun backUpNow(takeOver: Boolean = false) {
        val needsAsk = Build.VERSION.SDK_INT >= 33 && !takeOver &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else service.enqueueManual(vaultId, takeOver)
    }

    fun enable(targetId: String, rawKey: ByteArray) {
        scope.launch {
            try {
                service.enable(vaultId, targetId, config?.keep ?: Retention.DEFAULT_KEEP, config?.unmeteredOnly ?: true, rawKey)
                snackbar.showSnackbar(resources.getString(R.string.backup_msg_enabled))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: e.javaClass.simpleName)
            } finally {
                rawKey.fill(0)
            }
        }
    }

    fun enableChosen() {
        val targetId = chosenTarget ?: return
        val openVault = open[vaultId]
        if (openVault != null) enable(targetId, openVault.rawKey()) else dialog = BackupDialog.Password(targetId)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val label = uri.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: resources.getString(R.string.backup_target_folder)
            val target = BackupTarget.Folder(BackupTarget.newId(), label, uri.toString())
            service.targets.put(target)
            refreshTargets()
            chosenTarget = target.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(record.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))

            if (config != null) {
                val target = targets.firstOrNull { it.id == config.targetId }
                StatusCard(config, target, run?.progress)
                if (config.foreignSeq != null) {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { onRestoreFrom(config.targetId, config.folder) }) { Text(stringResource(R.string.backup_foreign_restore)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { backUpNow(takeOver = true) }) { Text(stringResource(R.string.backup_foreign_take_over)) }
                    }
                }
                Row(Modifier.padding(16.dp, 8.dp)) {
                    if (run == null) {
                        Button(onClick = { backUpNow() }) { Text(stringResource(R.string.backup_now)) }
                    } else {
                        OutlinedButton(onClick = { service.cancel(vaultId) }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_keep)) },
                    supportingContent = { Text(resources.getQuantityString(R.plurals.backup_keep_n, config.keep, config.keep)) },
                    modifier = Modifier.clickable { dialog = BackupDialog.Keep },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_unmetered)) },
                    supportingContent = { Text(stringResource(R.string.backup_unmetered_help)) },
                    trailingContent = { Switch(checked = config.unmeteredOnly, onCheckedChange = { service.update(vaultId, unmeteredOnly = it) }) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_switch_off), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.backup_switch_off_help)) },
                    modifier = Modifier.clickable { dialog = BackupDialog.SwitchOff },
                )
            } else {
                Text(stringResource(R.string.backup_off_help), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp, 4.dp))
            }

            HorizontalDivider()
            Text(stringResource(R.string.backup_targets_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
            if (targets.isEmpty()) {
                Text(stringResource(R.string.backup_targets_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp, 4.dp))
            }
            for (t in targets) {
                ListItem(
                    headlineContent = { Text(t.label) },
                    supportingContent = { Text(targetDescription(t)) },
                    leadingContent = {
                        if (config == null) RadioButton(selected = chosenTarget == t.id, onClick = { chosenTarget = t.id })
                        else Icon(if (t is BackupTarget.WebDav) Icons.Filled.Cloud else Icons.Filled.Folder, null)
                    },
                    trailingContent = {
                        if (config?.targetId != t.id) {
                            IconButton(onClick = { dialog = BackupDialog.ForgetTarget(t) }) { Icon(Icons.Filled.Delete, stringResource(R.string.backup_target_forget)) }
                        }
                    },
                    modifier = if (config == null) Modifier.clickable { chosenTarget = t.id } else Modifier,
                )
            }
            // Which targets exist is the build's business, not this screen's: the Play-services-free
            // build has no Google Drive to offer (dist/Distribution.kt).
            Row(Modifier.padding(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in Distribution.backupTargets) when (kind) {
                    BackupTarget.Kind.WEBDAV -> OutlinedButton(onClick = { dialog = BackupDialog.WebDav }) { Text(stringResource(R.string.backup_target_add_webdav)) }
                    BackupTarget.Kind.FOLDER -> OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.backup_target_add_folder)) }
                    // Not built yet; they await their developer registrations (BUILD_BRIEF.md §6.1).
                    BackupTarget.Kind.DROPBOX, BackupTarget.Kind.ONEDRIVE, BackupTarget.Kind.DRIVE -> Unit
                }
            }
            if (config == null && targets.isNotEmpty()) {
                Button(onClick = { enableChosen() }, enabled = chosenTarget != null, modifier = Modifier.padding(16.dp, 8.dp)) { Text(stringResource(R.string.backup_turn_on)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (val d = dialog) {
        null -> Unit
        BackupDialog.WebDav -> WebDavTargetDialog(
            onSave = { t -> dialog = null; service.targets.put(t); refreshTargets(); chosenTarget = t.id },
            onDismiss = { dialog = null },
        )
        BackupDialog.Keep -> AlertDialog(
            onDismissRequest = { dialog = null },
            properties = secureDialogProperties(),
            title = { Text(stringResource(R.string.backup_keep)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (n in listOf(1, 2, 3, 5, 10, 20)) {
                        Row(Modifier.fillMaxWidth().clickable { dialog = null; service.update(vaultId, keep = n) }.padding(vertical = 8.dp)) {
                            RadioButton(selected = config?.keep == n, onClick = null)
                            Text(resources.getQuantityString(R.plurals.backup_keep_n, n, n), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        BackupDialog.SwitchOff -> ConfirmDialog(
            title = stringResource(R.string.backup_switch_off),
            text = stringResource(R.string.backup_switch_off_confirm),
            confirmLabel = stringResource(R.string.backup_switch_off),
            destructive = true,
            onConfirm = { dialog = null; service.disable(vaultId) },
            onDismiss = { dialog = null },
        )
        is BackupDialog.ForgetTarget -> ConfirmDialog(
            title = stringResource(R.string.backup_target_forget),
            text = stringResource(R.string.backup_target_forget_confirm, d.target.label),
            confirmLabel = stringResource(R.string.backup_target_forget),
            destructive = true,
            onConfirm = {
                dialog = null
                service.removeTarget(d.target.id)
                refreshTargets()
                if (chosenTarget == d.target.id) chosenTarget = null
            },
            onDismiss = { dialog = null },
        )
        is BackupDialog.Password -> TextInputDialog(
            title = stringResource(R.string.password_current_label), label = stringResource(R.string.password_label), password = true,
            onConfirm = { pw ->
                dialog = null
                val chars = pw.toCharArray()
                scope.launch {
                    val raw = runCatching { repository.rawKey(vaultId, chars) }
                    chars.fill(' ')
                    raw.onSuccess { enable(d.targetId, it) }
                        .onFailure { snackbar.showSnackbar(if (it is WrongPasswordException) resources.getString(R.string.unlock_wrong_password) else it.message ?: "") }
                }
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun StatusCard(config: BackupConfig, target: BackupTarget?, progress: BackupProgress?) {
    val context = LocalContext.current
    Card(Modifier.padding(16.dp, 8.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(target?.label ?: stringResource(R.string.backup_target_missing), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.backup_folder, config.folder), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            when {
                progress != null -> {
                    if (progress.bytesTotal > 0) {
                        LinearProgressIndicator(progress = { (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.backup_notice_progress, Format.size(progress.bytesDone), Format.size(progress.bytesTotal)), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val (text, color) = backupStatusLine(context, config)
                    Text(text, color = color)
                }
            }
        }
    }
}

/** One line of backup status with its colour: fresh, stale (7 days, tertiary), old or failed (30 days, error). */
@Composable
fun backupStatusLine(context: Context, config: BackupConfig): Pair<String, Color> {
    val scheme = MaterialTheme.colorScheme
    return when {
        config.foreignSeq != null -> stringResource(R.string.backup_status_foreign) to scheme.error
        config.lastError != null && config.lastSnapshotAt == null -> stringResource(R.string.backup_status_failed, config.lastError) to scheme.error
        config.lastSnapshotAt != null -> {
            val age = System.currentTimeMillis() - (Iso8601.parse(config.lastSnapshotAt) ?: 0L)
            val days = age / (24L * 3600 * 1000)
            val text = if (config.lastError != null) stringResource(R.string.backup_status_failed, config.lastError)
            else stringResource(R.string.backup_status_ok, Format.date(context, config.lastSnapshotAt), config.lastSeq)
            text to when {
                config.lastError != null || days >= 30 -> scheme.error
                days >= 7 -> scheme.tertiary
                else -> scheme.onSurfaceVariant
            }
        }
        else -> stringResource(R.string.backup_status_never) to scheme.onSurfaceVariant
    }
}
