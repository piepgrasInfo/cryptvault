package info.piepgras.cryptvault.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.vault.VaultLocation
import info.piepgras.cryptvault.vault.VaultRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class VaultListViewModel(private val container: CryptVaultApp.Container) : ViewModel() {
    val records = container.repository.records
    val open = container.repository.open
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun lock(id: String) = container.lockManager.lock(id)

    fun addExisting(name: String, treeUri: Uri, onDone: (VaultRecord?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { container.repository.addExisting(name, treeUri) }
            result.exceptionOrNull()?.let { messages.tryEmit(it.message ?: it.javaClass.simpleName) }
            onDone(result.getOrNull())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    pendingImportCount: Int,
    onCreate: () -> Unit,
    onUnlock: (String) -> Unit,
    onOpen: (String) -> Unit,
    onVaultSettings: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = CryptVaultApp.container(context)
    val vm: VaultListViewModel = viewModel { VaultListViewModel(container) }
    val records by vm.records.collectAsState()
    val open by vm.open.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var addExistingUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val pickExisting = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) addExistingUri = uri
    }

    addExistingUri?.let { uri ->
        TextInputDialog(
            title = stringResource(R.string.vaults_add_existing_title),
            label = stringResource(R.string.vault_name_label),
            onConfirm = { name ->
                addExistingUri = null
                vm.addExisting(name, uri) { }
            },
            onDismiss = { addExistingUri = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings_title)) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vaults_add_existing)) },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                            onClick = { menu = false; pickExisting.launch(null) },
                        )
                        if (open.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vaults_lock_all)) },
                                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                onClick = { menu = false; container.lockManager.lockAll() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.vaults_create)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pendingImportCount > 0) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        pluralStringResource(R.plurals.vaults_pending_import, pendingImportCount, pendingImportCount),
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (records.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.vaults_empty_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.vaults_empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(records, key = { it.id }) { record ->
                        val unlocked = open.containsKey(record.id)
                        val available = remember(record) { container.repository.locationAvailable(record) }
                        VaultRow(
                            record = record,
                            unlocked = unlocked,
                            available = available,
                            onClick = { if (unlocked) onOpen(record.id) else onUnlock(record.id) },
                            onLock = { vm.lock(record.id) },
                            onSettings = { onVaultSettings(record.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRow(record: VaultRecord, unlocked: Boolean, available: Boolean, onClick: () -> Unit, onLock: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(enabled = available, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (!available) Icons.Filled.Warning else if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                null,
                tint = if (!available) MaterialTheme.colorScheme.error else if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(record.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = when {
                    !available -> stringResource(R.string.vault_location_missing)
                    record.location is VaultLocation.Saf -> stringResource(R.string.vault_location_folder, CryptVaultApp.container(context).repository.locationLabel(record))
                    unlocked -> stringResource(R.string.vault_state_unlocked)
                    else -> stringResource(R.string.vault_state_locked)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unlocked) {
                IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, stringResource(R.string.action_lock)) }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.vault_settings_title)) }
        }
    }
}
