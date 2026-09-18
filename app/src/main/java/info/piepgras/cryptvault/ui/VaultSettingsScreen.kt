package info.piepgras.cryptvault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.security.sensitive
import info.piepgras.cryptvault.unlock.PasswordPolicy
import info.piepgras.cryptvault.vault.VaultLocation
import info.piepgras.cryptvault.vault.VaultRecord
import info.piepgras.cryptvault.vault.WrongPasswordException
import kotlinx.coroutines.launch

private sealed class SettingsDialog {
    object Rename : SettingsDialog()
    object AutoLock : SettingsDialog()
    object ChangePassword : SettingsDialog()
    object Delete : SettingsDialog()
    /** Password check before a sensitive action; [purpose] says which. */
    data class Password(val purpose: String) : SettingsDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsScreen(vaultId: String, onBack: () -> Unit, onDeleted: () -> Unit, onShowRecoveryKey: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val repository = container.repository
    val records by repository.records.collectAsState()
    val record = records.firstOrNull { it.id == vaultId }
    if (record == null) {
        onBack()
        return
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val activity = context as? androidx.fragment.app.FragmentActivity
    val biometricPossible = remember { container.biometricWrap.canUse() }
    val promptTitle = stringResource(R.string.settings_biometric_prompt_title)
    val promptNegative = stringResource(R.string.action_cancel)

    /** After the password check: turn the biometric shortcut on (prompt, then wrap) or show the key. */
    fun afterPassword(purpose: String, chars: CharArray) {
        scope.launch {
            when (purpose) {
                "biometric" -> {
                    val raw = runCatching { repository.rawKey(vaultId, chars) }
                    chars.fill('\u0000')
                    raw.onFailure { snackbar.showSnackbar(if (it is WrongPasswordException) resources.getString(R.string.unlock_wrong_password) else it.message ?: "") }
                    val key = raw.getOrNull() ?: return@launch
                    if (activity == null) { key.fill(0); return@launch }
                    info.piepgras.cryptvault.unlock.BiometricUnlock.prompt(activity, promptTitle, record.name, promptNegative) { outcome ->
                        scope.launch {
                            try {
                                if (outcome is info.piepgras.cryptvault.unlock.BiometricUnlock.Outcome.Success) {
                                    runCatching { container.biometricWrap.enable(vaultId, key) }
                                        .onSuccess {
                                            repository.setBiometric(vaultId, true)
                                            snackbar.showSnackbar(resources.getString(R.string.msg_biometrics_enabled))
                                        }
                                        .onFailure { snackbar.showSnackbar(it.message ?: it.javaClass.simpleName) }
                                } else if (outcome is info.piepgras.cryptvault.unlock.BiometricUnlock.Outcome.Failed) {
                                    snackbar.showSnackbar(outcome.message)
                                }
                            } finally {
                                key.fill(0)
                            }
                        }
                    }
                }
                "recovery" -> {
                    val words = runCatching { repository.recoveryKey(vaultId, chars) }
                    chars.fill('\u0000')
                    words.onSuccess { container.recoveryKeyToShow.value = vaultId to it; onShowRecoveryKey() }
                        .onFailure { snackbar.showSnackbar(if (it is WrongPasswordException) resources.getString(R.string.unlock_wrong_password) else it.message ?: "") }
                }
            }
        }
    }

    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            scope.launch {
                runCatching { repository.exportVaultFolder(vaultId, uri) }
                    .onSuccess { snackbar.showSnackbar(resources.getString(R.string.msg_vault_exported)) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "") }
                exporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_name)) },
                supportingContent = { Text(record.name) },
                modifier = Modifier.clickable { dialog = SettingsDialog.Rename },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_location)) },
                supportingContent = {
                    Text(
                        when (record.location) {
                            is VaultLocation.Private -> stringResource(R.string.create_vault_private)
                            is VaultLocation.Saf -> stringResource(R.string.vault_location_folder, repository.locationLabel(record))
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_auto_lock)) },
                supportingContent = { Text(autoLockLabel(record.autoLockSeconds)) },
                modifier = Modifier.clickable { dialog = SettingsDialog.AutoLock },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_biometric)) },
                supportingContent = { Text(stringResource(if (biometricPossible) R.string.vault_settings_biometric_hint else R.string.vault_settings_biometric_unavailable)) },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = record.biometric && container.biometricWrap.isEnabled(vaultId),
                        enabled = biometricPossible,
                        onCheckedChange = { on ->
                            if (on) dialog = SettingsDialog.Password("biometric")
                            else { container.biometricWrap.disable(vaultId); repository.setBiometric(vaultId, false) }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_recovery_key)) },
                supportingContent = { Text(stringResource(R.string.vault_settings_recovery_key_hint)) },
                modifier = Modifier.clickable { dialog = SettingsDialog.Password("recovery") },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_change_password)) },
                modifier = Modifier.clickable { dialog = SettingsDialog.ChangePassword },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_export_folder)) },
                supportingContent = { Text(stringResource(if (exporting) R.string.progress_exporting else R.string.vault_settings_export_folder_hint)) },
                modifier = Modifier.clickable(enabled = !exporting) { exportTree.launch(null) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.vault_settings_delete), color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(stringResource(R.string.vault_settings_delete_hint)) },
                modifier = Modifier.clickable { dialog = SettingsDialog.Delete },
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        is SettingsDialog.Password -> TextInputDialog(
            title = stringResource(R.string.password_current_label), label = stringResource(R.string.password_label), password = true,
            onConfirm = { pw -> dialog = null; afterPassword(d.purpose, pw.toCharArray()) }, onDismiss = { dialog = null },
        )
        SettingsDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.vault_settings_name), initial = record.name, label = stringResource(R.string.vault_name_label),
            onConfirm = { dialog = null; repository.rename(vaultId, it) }, onDismiss = { dialog = null },
        )
        SettingsDialog.AutoLock -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.vault_settings_auto_lock)) },
            text = {
                Column {
                    for (s in VaultRecord.AUTO_LOCK_OPTIONS) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clickable { repository.setAutoLock(vaultId, s); dialog = null }.padding(vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = record.autoLockSeconds == s, onClick = null)
                            Text(autoLockLabel(s), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        SettingsDialog.ChangePassword -> ChangePasswordDialog(
            onConfirm = { old, new ->
                scope.launch {
                    val o = old.toCharArray()
                    val n = new.toCharArray()
                    val result = runCatching { repository.changePassword(vaultId, o, n) }
                    o.fill('\u0000'); n.fill('\u0000')
                    dialog = null
                    result.onSuccess { snackbar.showSnackbar(resources.getString(R.string.msg_password_changed)) }
                        .onFailure {
                            snackbar.showSnackbar(if (it is WrongPasswordException) resources.getString(R.string.unlock_wrong_password) else it.message ?: "")
                        }
                }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Delete -> DeleteVaultDialog(record,
            onConfirm = { deleteFiles ->
                dialog = null
                scope.launch {
                    container.biometricWrap.disable(vaultId)
                    runCatching { repository.delete(vaultId, deleteFiles) }
                        .onSuccess { onDeleted() }
                        .onFailure { snackbar.showSnackbar(it.message ?: "") }
                }
            },
            onDismiss = { dialog = null })
    }
}

@Composable
fun autoLockLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.auto_lock_immediately)
    seconds < 60 -> pluralStringResource(R.plurals.auto_lock_seconds, seconds, seconds)
    seconds < 3600 -> pluralStringResource(R.plurals.auto_lock_minutes, seconds / 60, seconds / 60)
    else -> pluralStringResource(R.plurals.auto_lock_hours, seconds / 3600, seconds / 3600)
}

@Composable
private fun ChangePasswordDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ok = old.isNotEmpty() && PasswordPolicy.check(new).ok && new == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_settings_change_password)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = old, onValueChange = { old = it }, label = { Text(stringResource(R.string.password_current_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().sensitive())
                OutlinedTextField(value = new, onValueChange = { new = it }, label = { Text(stringResource(R.string.password_new_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sensitive(),
                    supportingText = { Text(passwordAdvice(PasswordPolicy.check(new), new)) })
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text(stringResource(R.string.password_confirm_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sensitive(), isError = confirm.isNotEmpty() && confirm != new)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(old, new) }, enabled = ok) { Text(stringResource(R.string.action_change)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteVaultDialog(record: VaultRecord, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by remember { mutableStateOf(record.location is VaultLocation.Private) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_settings_delete)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dialog_delete_vault_body, record.name))
                if (record.location is VaultLocation.Saf) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { deleteFiles = false }.padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = !deleteFiles, onClick = null)
                        Text(stringResource(R.string.dialog_delete_vault_forget), Modifier.padding(start = 8.dp))
                    }
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable { deleteFiles = true }.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = deleteFiles, onClick = null)
                        Text(stringResource(R.string.dialog_delete_vault_files), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteFiles) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
