package info.piepgras.cryptvault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.backup.BackupProgress
import info.piepgras.cryptvault.backup.BackupTarget
import info.piepgras.cryptvault.backup.RestoreService
import info.piepgras.cryptvault.backup.Retention
import info.piepgras.cryptvault.backup.SnapshotInfo
import info.piepgras.cryptvault.security.sensitive
import info.piepgras.cryptvault.vault.VaultRecord
import info.piepgras.cryptvault.vault.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The restore wizard's state: target, folder, password, snapshot, progress, done (docs/VAULT_LAYOUT.md 6.3). */
class RestoreViewModel(private val container: CryptVaultApp.Container) : ViewModel() {
    enum class Step { TARGET, FOLDER, PASSWORD, SNAPSHOT, RUNNING, DONE }

    data class State(
        val step: Step = Step.TARGET,
        val targets: List<BackupTarget> = emptyList(),
        val target: BackupTarget? = null,
        val folders: List<String>? = null,
        val folder: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val wrongPassword: Boolean = false,
        val session: RestoreService.Session? = null,
        val progress: BackupProgress? = null,
        val restored: VaultRecord? = null,
    )

    val state = MutableStateFlow(State(targets = container.backupTargets.all()))
    private val restore = container.restore
    @Volatile private var cancelled = false

    fun refreshTargets() = state.update { it.copy(targets = container.backupTargets.all()) }

    fun chooseTarget(target: BackupTarget, preselectFolder: String? = null) {
        state.update { it.copy(target = target, step = Step.FOLDER, folders = null, error = null, busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { container.backup.listVaultFolders(target) } }
            result.onSuccess { folders ->
                if (preselectFolder != null && preselectFolder in folders) chooseFolder(preselectFolder)
                else state.update { it.copy(folders = folders, busy = false) }
            }.onFailure { e -> state.update { it.copy(folders = emptyList(), busy = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun chooseFolder(folder: String) = state.update { it.copy(folder = folder, step = Step.PASSWORD, error = null, wrongPassword = false, busy = false) }

    fun submitPassword(password: CharArray) {
        val s = state.value
        val target = s.target ?: return
        val folder = s.folder ?: return
        state.update { it.copy(busy = true, error = null, wrongPassword = false) }
        viewModelScope.launch {
            val result = runCatching { restore.begin(target, folder, password) }
            password.fill(' ')
            result.onSuccess { session -> state.update { it.copy(session = session, step = Step.SNAPSHOT, busy = false) } }
                .onFailure { e ->
                    state.update { it.copy(busy = false, wrongPassword = e is WrongPasswordException, error = if (e is WrongPasswordException) null else e.message ?: e.javaClass.simpleName) }
                }
        }
    }

    fun chooseSnapshot(info: SnapshotInfo) {
        val session = state.value.session ?: return
        state.update { it.copy(step = Step.RUNNING, progress = null, error = null) }
        cancelled = false
        viewModelScope.launch {
            val result = runCatching {
                restore.restore(session, info, isCancelled = { cancelled }) { p -> state.update { it.copy(progress = p) } }
            }
            result.onSuccess { record -> state.update { it.copy(step = Step.DONE, restored = record) } }
                .onFailure { e -> state.update { it.copy(step = Step.SNAPSHOT, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun cancelRun() { cancelled = true }

    /** Done: optionally make this device the writer of that remote folder. */
    fun finish(adopt: Boolean) {
        val s = state.value
        val record = s.restored ?: return
        val session = s.session ?: return
        if (adopt) restore.adoptAsWriter(record, session, Retention.DEFAULT_KEEP, true)
        session.snapshotKey.fill(0)
    }

    /** Leaving before the end: throw the half-restored directory away. */
    fun abandon() {
        cancelled = true
        val s = state.value
        if (s.step != Step.DONE) s.session?.let { restore.abandon(it) }
    }

    fun back(): Boolean {
        val s = state.value
        return when (s.step) {
            Step.FOLDER -> { state.update { it.copy(step = Step.TARGET, target = null, folders = null, error = null) }; true }
            Step.PASSWORD -> { state.update { it.copy(step = Step.FOLDER, folder = null, error = null) }; true }
            Step.SNAPSHOT -> { s.session?.let { restore.abandon(it) }; state.update { it.copy(step = Step.PASSWORD, session = null, error = null) }; true }
            else -> false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(preselectTargetId: String?, preselectFolder: String?, onBack: () -> Unit, onDone: (vaultId: String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val vm: RestoreViewModel = viewModel { RestoreViewModel(container) }
    val state by vm.state.collectAsState()
    var showWebDav by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var adopt by remember { mutableStateOf(false) }

    LaunchedEffect(preselectTargetId) {
        if (preselectTargetId != null && state.step == RestoreViewModel.Step.TARGET) {
            state.targets.firstOrNull { it.id == preselectTargetId }?.let { vm.chooseTarget(it, preselectFolder) }
        }
    }

    fun leave() {
        if (state.step == RestoreViewModel.Step.DONE) return
        if (!vm.back()) { vm.abandon(); onBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    if (state.step != RestoreViewModel.Step.DONE && state.step != RestoreViewModel.Step.RUNNING) {
                        IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.restore_help), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
            when (state.step) {
                RestoreViewModel.Step.TARGET -> {
                    Text(stringResource(R.string.restore_step_target), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    if (state.targets.isEmpty()) Text(stringResource(R.string.backup_targets_none), modifier = Modifier.padding(16.dp, 4.dp))
                    for (t in state.targets) {
                        ListItem(headlineContent = { Text(t.label) }, supportingContent = { Text(targetDescription(t)) }, modifier = Modifier.clickable { vm.chooseTarget(t) })
                    }
                    Row(Modifier.padding(16.dp, 8.dp)) {
                        OutlinedButton(onClick = { showWebDav = true }) { Text(stringResource(R.string.backup_target_add_webdav)) }
                    }
                }
                RestoreViewModel.Step.FOLDER -> {
                    Text(stringResource(R.string.restore_step_folder), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
                    val folders = state.folders
                    if (folders != null && folders.isEmpty() && state.error == null) Text(stringResource(R.string.restore_folders_none), modifier = Modifier.padding(16.dp, 4.dp))
                    folders?.forEach { f -> ListItem(headlineContent = { Text(f) }, modifier = Modifier.clickable { vm.chooseFolder(f) }) }
                }
                RestoreViewModel.Step.PASSWORD -> {
                    Text(stringResource(R.string.restore_step_password), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    Text(state.folder ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp, 0.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.password_label)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = state.wrongPassword, enabled = !state.busy,
                        supportingText = { if (state.wrongPassword) Text(stringResource(R.string.unlock_wrong_password)) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp).sensitive(),
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
                    Button(
                        onClick = { val chars = password.toCharArray(); password = ""; vm.submitPassword(chars) },
                        enabled = password.isNotEmpty() && !state.busy,
                        modifier = Modifier.padding(16.dp, 8.dp),
                    ) { Text(stringResource(R.string.action_unlock)) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 0.dp))
                }
                RestoreViewModel.Step.SNAPSHOT -> {
                    Text(stringResource(R.string.restore_step_snapshot), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp, 4.dp)) }
                    state.session?.snapshots?.forEach { s ->
                        ListItem(
                            headlineContent = { Text(Format.date(context, s.createdAt)) },
                            supportingContent = { Text(resources.getQuantityString(R.plurals.restore_snapshot_line, s.fileCount, s.fileCount, Format.size(s.totalSize), s.seq)) },
                            modifier = Modifier.clickable { vm.chooseSnapshot(s) },
                        )
                    }
                }
                RestoreViewModel.Step.RUNNING -> {
                    Text(stringResource(R.string.restore_running), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    val p = state.progress
                    if (p != null && p.bytesTotal > 0) {
                        LinearProgressIndicator(progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
                        Text(stringResource(R.string.backup_notice_progress, Format.size(p.bytesDone), Format.size(p.bytesTotal)), modifier = Modifier.padding(16.dp, 0.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 8.dp))
                    }
                    OutlinedButton(onClick = { vm.cancelRun() }, modifier = Modifier.padding(16.dp, 8.dp)) { Text(stringResource(R.string.action_cancel)) }
                }
                RestoreViewModel.Step.DONE -> {
                    Text(stringResource(R.string.restore_done, state.restored?.name ?: ""), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
                    Row(Modifier.fillMaxWidth().clickable { adopt = !adopt }.padding(16.dp, 8.dp)) {
                        Checkbox(checked = adopt, onCheckedChange = { adopt = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_adopt))
                    }
                    Button(onClick = { vm.finish(adopt); onDone(state.restored?.id ?: "") }, modifier = Modifier.padding(16.dp, 8.dp)) { Text(stringResource(R.string.restore_finish)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showWebDav) {
        WebDavTargetDialog(
            onSave = { t -> showWebDav = false; container.backupTargets.put(t); vm.refreshTargets() },
            onDismiss = { showWebDav = false },
        )
    }
}
