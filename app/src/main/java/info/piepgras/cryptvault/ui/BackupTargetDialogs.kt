package info.piepgras.cryptvault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.backup.BackupService
import info.piepgras.cryptvault.backup.BackupTarget
import info.piepgras.cryptvault.backup.LocalNetwork
import info.piepgras.cryptvault.backup.WebDavStore
import info.piepgras.cryptvault.security.sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Connect a WebDAV server" form: URL, user, app password, with a connection test before saving. */
@Composable
fun WebDavTargetDialog(onSave: (BackupTarget.WebDav) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var testing by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var tested by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val okText = stringResource(R.string.backup_webdav_test_ok)
    val deniedText = stringResource(R.string.backup_webdav_local_denied)
    val valid = BackupService.validWebDavUrl(url) && user.isNotBlank() && password.isNotEmpty()

    fun runTest() {
        testing = true; result = null; tested = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { WebDavStore(url, user, password).probe() }.getOrElse { it.message ?: it.javaClass.simpleName } }
            result = r ?: okText
            tested = r == null
            testing = false
        }
    }

    val localPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runTest() else result = deniedText
    }

    /** A server at home needs the local-network permission first (Android 17+). */
    fun test() {
        if (LocalNetwork.needsPermission(context, url)) localPermission.launch(LocalNetwork.PERMISSION) else runTest()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = secureDialogProperties(),
        title = { Text(stringResource(R.string.backup_webdav_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.backup_webdav_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = label, onValueChange = { label = it; tested = false }, label = { Text(stringResource(R.string.backup_target_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim(); tested = false }, label = { Text(stringResource(R.string.backup_webdav_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    isError = url.length > 8 && !BackupService.validWebDavUrl(url),
                )
                OutlinedTextField(value = user, onValueChange = { user = it; tested = false }, label = { Text(stringResource(R.string.backup_webdav_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it; tested = false }, label = { Text(stringResource(R.string.backup_webdav_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().sensitive(),
                )
                if (BackupService.isCleartext(url)) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.backup_webdav_cleartext), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = if (tested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (tested) {
                TextButton(onClick = {
                    val fallback = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                    onSave(BackupTarget.WebDav(BackupTarget.newId(), label.ifBlank { fallback }, url, user, password))
                }) { Text(stringResource(R.string.action_save)) }
            } else {
                TextButton(onClick = { test() }, enabled = valid && !testing) { Text(stringResource(if (testing) R.string.backup_webdav_testing else R.string.backup_webdav_test)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun targetDescription(t: BackupTarget): String = when (t) {
    is BackupTarget.WebDav -> t.url
    is BackupTarget.Folder -> stringResource(R.string.backup_target_folder)
}
