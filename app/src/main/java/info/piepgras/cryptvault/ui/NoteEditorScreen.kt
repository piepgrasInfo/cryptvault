package info.piepgras.cryptvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Manifest
import info.piepgras.cryptvault.security.sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Markdown note: a title and a body; saved on the check mark and on Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(vaultId: String, folder: String, itemId: String?, onLocked: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val open by container.repository.open.collectAsState()
    val vault = open[vaultId]
    LaunchedEffect(vault) { if (vault == null) onLocked() }
    if (vault == null) return
    val item = itemId?.let { vault.item(it) }

    var title by rememberSaveable { mutableStateOf(item?.title ?: "") }
    var body by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(item == null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item?.id) {
        if (item != null && !loaded) {
            body = withContext(Dispatchers.IO) { runCatching { vault.readNote(item) }.getOrDefault("") }
            loaded = true
        }
    }

    fun save(then: () -> Unit) {
        if (saving) return
        if (item == null && title.isBlank() && body.isBlank()) { then(); return }
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (item == null) {
                        val target = folder.ifEmpty { vault.ensureFolder(Manifest.DEFAULT_NOTES_FOLDER) }
                        vault.createNote(target, title.ifBlank { resources.getString(R.string.note_default_title) }, body)
                    } else {
                        var current = vault.updateNote(item, body)
                        if (title != current.title) current = vault.updateMeta(current.id, title = title)
                        current
                    }
                }
            }
            saving = false
            result.onSuccess { then() }.onFailure { snackbar.showSnackbar(it.message ?: it.javaClass.simpleName) }
        }
    }

    BackHandler { save(onBack) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (item == null) stringResource(R.string.note_new_title) else stringResource(R.string.note_edit_title)) },
                navigationIcon = { IconButton(onClick = { save(onBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { container.clipboard.copy(body); scope.launch { snackbar.showSnackbar(resources.getString(R.string.msg_copied)) } }, enabled = body.isNotEmpty()) {
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = { save(onBack) }, enabled = !saving) { Icon(Icons.Filled.Check, stringResource(R.string.action_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(16.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.note_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text(stringResource(R.string.note_body_label)) },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).sensitive(),
                enabled = loaded,
            )
        }
    }
}
