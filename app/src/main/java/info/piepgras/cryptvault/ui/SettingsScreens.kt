package info.piepgras.cryptvault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.BuildConfig
import info.piepgras.cryptvault.CrashReporting
import info.piepgras.cryptvault.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashReporting.isEnabled(context)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_crash_reporting)) },
                supportingContent = { Text(stringResource(R.string.settings_crash_reporting_hint)) },
                trailingContent = { Switch(checked = crash, onCheckedChange = { crash = it; CrashReporting.setEnabled(context, it) }) },
            )
            HorizontalDivider()
            ListItem(headlineContent = { Text(stringResource(R.string.permissions_title)) }, modifier = Modifier.clickable(onClick = onPermissions))
            ListItem(headlineContent = { Text(stringResource(R.string.about_title)) }, supportingContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)) }, modifier = Modifier.clickable(onClick = onAbout))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_body), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_license_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_license_body), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_components_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_components_body), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_developer, "Martin Kruse", "apps@piepgras.info"), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.permissions_intro), style = MaterialTheme.typography.bodyMedium)
            PermissionEntry(stringResource(R.string.perm_internet_title), stringResource(R.string.perm_internet_body))
            PermissionEntry(stringResource(R.string.perm_notifications_title), stringResource(R.string.perm_notifications_body))
            PermissionEntry(stringResource(R.string.perm_overlay_title), stringResource(R.string.perm_overlay_body))
            PermissionEntry(stringResource(R.string.perm_biometric_title), stringResource(R.string.perm_biometric_body))
            PermissionEntry(stringResource(R.string.perm_foreground_title), stringResource(R.string.perm_foreground_body))
            PermissionEntry(stringResource(R.string.perm_local_network_title), stringResource(R.string.perm_local_network_body))
            PermissionEntry(stringResource(R.string.perm_documents_title), stringResource(R.string.perm_documents_body))
            Text(stringResource(R.string.permissions_not_requested_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permissions_not_requested_body), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PermissionEntry(title: String, body: String) {
    Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
    Text(body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
}
