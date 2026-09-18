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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsScreen(vaultId: String, onBack: () -> Unit, onDeleted: () -> Unit) {
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

    when (dialog) {
        null -> Unit
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
                    o.fill(' '); n.fill(' ')
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
                OutlinedTextField(value = old, onValueChange = { old = it }, label = { Text(stringResource(R.string.password_current_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = new, onValueChange = { new = it }, label = { Text(stringResource(R.string.password_new_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    supportingText = { Text(if (new.isEmpty() || PasswordPolicy.check(new).ok) stringResource(R.string.password_hint) else pluralStringResource(R.plurals.password_too_weak, PasswordPolicy.MIN_LENGTH, PasswordPolicy.MIN_LENGTH)) })
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text(stringResource(R.string.password_confirm_label)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), isError = confirm.isNotEmpty() && confirm != new)
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
