package info.piepgras.cryptvault.ui

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.ImportSources
import info.piepgras.cryptvault.items.Names
import info.piepgras.cryptvault.security.sensitive
import info.piepgras.cryptvault.share.ContainerKind
import info.piepgras.cryptvault.share.ContainerReader
import info.piepgras.cryptvault.share.ContainerTooLargeException
import info.piepgras.cryptvault.share.SharePolicy
import info.piepgras.cryptvault.share.WrongPassphraseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * A received container (BUILD_BRIEF.md §7.4): copy the attachment into `cache/import/`, decide
 * its kind by content, ask for the passphrase, show what is inside, import into an unlocked
 * vault (a `- note.txt` becomes its file's note), wipe the cache.
 */
class ReceiveViewModel(private val container: CryptVaultApp.Container, private val uri: Uri) : ViewModel() {
    enum class Step { DETECT, PASSPHRASE, DECRYPTED, IMPORTING, DONE }

    data class State(
        val step: Step = Step.DETECT,
        val name: String = "",
        val kind: ContainerKind? = null,
        val files: List<File> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
        val wrongPass: Boolean = false,
        val chosenVault: String? = null,
        val imported: Int = 0,
        val importedInto: String = "",
        val importedFolder: String = "",
    )

    val state = MutableStateFlow(State())
    val vaults = container.repository.records
    val open = container.repository.open
    private val work = File(File(container.app.cacheDir, "import"), SecureRandom().nextLong().toULong().toString(36))
    private var containerFile: File? = null

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    work.mkdirs()
                    val source = ImportSources.describe(container.resolver, uri)
                    val f = File(work, Names.sanitize(source.name, "container"))
                    ImportSources.open(container.resolver, uri).use { input -> f.outputStream().use { input.copyTo(it) } }
                    f to ContainerReader.detect(f)
                }
            }
            result.onSuccess { (f, kind) ->
                containerFile = f
                state.update { it.copy(name = f.name, kind = kind, step = if (kind == null) Step.DETECT else Step.PASSPHRASE, error = if (kind == null) NOT_A_CONTAINER else null) }
            }.onFailure { e -> state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun submitPassphrase(pass: CharArray) {
        val f = containerFile ?: return
        val kind = state.value.kind ?: return
        state.update { it.copy(busy = true, wrongPass = false, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ContainerReader.open(kind, f, pass, File(work, "out")) } }
            pass.fill(' ')
            result.onSuccess { files -> state.update { it.copy(files = files, step = Step.DECRYPTED, busy = false, chosenVault = open.value.keys.firstOrNull()) } }
                .onFailure { e ->
                    val msg = when (e) { is WrongPassphraseException -> null; is ContainerTooLargeException -> container.app.getString(R.string.receive_too_big); else -> e.message ?: e.javaClass.simpleName }
                    state.update { it.copy(busy = false, wrongPass = e is WrongPassphraseException, error = msg) }
                }
        }
    }

    fun chooseVault(id: String) = state.update { it.copy(chosenVault = id) }

    fun import() {
        val s = state.value
        val vaultId = s.chosenVault ?: return
        val vault = open.value[vaultId] ?: return
        state.update { it.copy(step = Step.IMPORTING, busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // several files land in a folder named after the container; a single one in the root
                    val folder = if (s.files.count { !it.name.endsWith(SharePolicy.NOTE_SUFFIX) } > 1) vault.ensureFolder(Names.sanitize(s.name.substringBeforeLast('.'), "Received")) else ""
                    val notes = s.files.filter { it.name.endsWith(SharePolicy.NOTE_SUFFIX) }.associateBy { it.name.removeSuffix(SharePolicy.NOTE_SUFFIX) }
                    var count = 0
                    for (file in s.files) {
                        if (file.name.endsWith(SharePolicy.NOTE_SUFFIX) && notes.keys.any { k -> s.files.any { f -> !f.name.endsWith(SharePolicy.NOTE_SUFFIX) && f.name.substringBeforeLast('.') == k } }) continue
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                        val item = file.inputStream().use { input -> vault.importFile(folder, file.name, mime, file.length(), input) }
                        notes[file.name.substringBeforeLast('.')]?.let { n -> vault.updateMeta(item.id, note = n.readText()) }
                        count++
                    }
                    count to folder
                }
            }
            result.onSuccess { (count, folder) ->
                val name = vaults.value.firstOrNull { it.id == vaultId }?.name ?: ""
                state.update { it.copy(step = Step.DONE, busy = false, imported = count, importedInto = name, importedFolder = folder) }
                wipe()
            }.onFailure { e -> state.update { it.copy(step = Step.DECRYPTED, busy = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun wipe() { work.deleteRecursively() }

    override fun onCleared() { wipe() }

    companion object { const val NOT_A_CONTAINER = "not-a-container" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(uri: String, onBack: () -> Unit, onUnlock: (String) -> Unit, onOpenFolder: (String, String) -> Unit) {
    val context = LocalContext.current
    val container = CryptVaultApp.container(context)
    val vm: ReceiveViewModel = viewModel(key = uri) { ReceiveViewModel(container, uri.toUri()) }
    val state by vm.state.collectAsState()
    val vaults by vm.vaults.collectAsState()
    val open by vm.open.collectAsState()
    var pass by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
                navigationIcon = { IconButton(onClick = { vm.wipe(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (state.name.isNotEmpty() && state.kind != null) {
                Text(stringResource(R.string.receive_kind, stringResource(info.piepgras.cryptvault.share.ShareService.kindName(state.kind!!)), state.name), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            when (state.step) {
                ReceiveViewModel.Step.DETECT -> {
                    if (state.error == ReceiveViewModel.NOT_A_CONTAINER) Text(stringResource(R.string.receive_not_container), color = MaterialTheme.colorScheme.error)
                    else if (state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    else { Text(stringResource(R.string.receive_detecting)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                }
                ReceiveViewModel.Step.PASSPHRASE -> {
                    Text(stringResource(R.string.receive_pass_prompt))
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.share_pass_label)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = state.wrongPass, enabled = !state.busy,
                        supportingText = { if (state.wrongPass) Text(stringResource(R.string.receive_wrong_pass)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sensitive(),
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { val c = pass.toCharArray(); pass = ""; vm.submitPassphrase(c) }, enabled = pass.isNotEmpty() && !state.busy, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.receive_open)) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                ReceiveViewModel.Step.DECRYPTED, ReceiveViewModel.Step.IMPORTING -> {
                    Text(stringResource(R.string.receive_files), style = MaterialTheme.typography.titleSmall)
                    for (f in state.files) ListItem(headlineContent = { Text(f.name) }, supportingContent = { Text(Format.size(f.length())) })
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.receive_choose_vault), style = MaterialTheme.typography.titleSmall)
                    if (open.isEmpty()) Text(stringResource(R.string.receive_no_vault), color = MaterialTheme.colorScheme.error)
                    for (v in vaults) {
                        val unlocked = v.id in open
                        ListItem(
                            headlineContent = { Text(v.name) },
                            supportingContent = { if (!unlocked) Text(stringResource(R.string.receive_vault_locked)) },
                            leadingContent = { RadioButton(selected = state.chosenVault == v.id, onClick = { if (unlocked) vm.chooseVault(v.id) else onUnlock(v.id) }, enabled = unlocked) },
                            modifier = Modifier.clickable { if (unlocked) vm.chooseVault(v.id) else onUnlock(v.id) },
                        )
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { vm.import() }, enabled = state.chosenVault != null && !state.busy, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.receive_import)) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                ReceiveViewModel.Step.DONE -> {
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.receive_done_n, state.imported, state.imported, state.importedInto), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { onOpenFolder(state.chosenVault ?: "", state.importedFolder) }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.receive_open_vault)) }
                }
            }
        }
    }
}
