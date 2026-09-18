package info.piepgras.cryptvault.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.ItemKind
import info.piepgras.cryptvault.items.OpenVault
import info.piepgras.cryptvault.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the detail screen can show inline without leaving the vault. */
private sealed class Preview {
    data class Picture(val bitmap: ImageBitmap) : Preview()
    data class Text(val text: String, val truncated: Boolean) : Preview()
    object None : Preview()
}

private suspend fun loadPreview(vault: OpenVault, item: Item, vm: BrowserViewModel): Preview = withContext(Dispatchers.IO) {
    runCatching {
        when {
            item.mime.startsWith("image/") && item.mime != "image/svg+xml" && item.size < 64L * 1024 * 1024 -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                vault.open(item).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                vault.open(item).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()?.let { Preview.Picture(it) } ?: Preview.None
            }
            item.mime.startsWith("text/") || item.mime == "application/json" || item.mime == "application/xml" -> {
                val limit = 200 * 1024
                val buf = ByteArray(limit + 1)
                var n = 0
                vault.open(item).use { input ->
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                Preview.Text(String(buf, 0, minOf(n, limit), Charsets.UTF_8), n > limit)
            }
            else -> vm.thumbnail(item)?.let { Preview.Picture(it) } ?: Preview.None
        }
    }.getOrDefault(Preview.None)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(vaultId: String, itemId: String, onLocked: () -> Unit, onBack: () -> Unit, onEditNote: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val vm: BrowserViewModel = viewModel(key = "browser-$vaultId") { BrowserViewModel(container, vaultId) }
    val open by vm.open.collectAsState()
    val vault = open[vaultId]
    LaunchedEffect(vault) { if (vault == null) onLocked() }
    if (vault == null) return
    val manifest by vault.manifest.collectAsState()
    val item = manifest.item(itemId)
    LaunchedEffect(item) { if (item == null) onBack() }
    if (item == null) return

    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var title by remember(item.id) { mutableStateOf(item.title) }
    var tags by remember(item.id) { mutableStateOf(item.tags.joinToString(", ")) }
    var note by remember(item.id) { mutableStateOf(item.note) }
    val dirty = title != item.title || tags.split(',').map { it.trim() }.filter { it.isNotEmpty() } != item.tags || note != item.note
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    var notice by remember { mutableStateOf<(() -> Unit)?>(null) }
    val preview by produceState<Preview?>(null, item.id, item.modifiedAt) { value = loadPreview(vault, item, vm) }

    val launch = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> if (uri != null) vm.exportItem(item, uri) }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is BrowserEvent.Message -> snackbar.showSnackbar(e.text)
                is BrowserEvent.MessageRes -> snackbar.showSnackbar(e.format(resources))
                is BrowserEvent.Launch -> runCatching { launch.launch(e.intent) }
                else -> Unit
            }
        }
    }

    fun withNotice(action: () -> Unit) {
        if (AppPrefs.openWithNoticeShown(context)) action() else notice = action
    }

    fun save() {
        val t = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { vault.updateMeta(item.id, title = title, tags = t, note = note) } }
                .onFailure { snackbar.showSnackbar(it.message ?: "") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { more = false; dialog = BrowserDialog.RenameItem(item) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_move)) }, onClick = { more = false; vm.selectAll(listOf(item.id)); dialog = BrowserDialog.MoveItems })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { more = false; exportOne.launch(item.name) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { more = false; dialog = BrowserDialog.DeleteItem(item) })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp), contentAlignment = Alignment.Center) {
                when (val p = preview) {
                    is Preview.Picture -> Image(p.bitmap, null, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
                    is Preview.Text -> Text(
                        p.text + if (p.truncated) "\n…" else "",
                        Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                    Preview.None -> Icon(iconFor(item), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> Unit
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind == ItemKind.NOTE) {
                    Button(onClick = onEditNote, Modifier.weight(1f)) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.action_edit)) }
                } else {
                    Button(onClick = { withNotice { vm.openItem(item, false) } }, Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.action_open)) }
                    OutlinedButton(onClick = { withNotice { vm.openItem(item, true) } }) { Icon(Icons.Filled.Edit, null) }
                }
                OutlinedButton(onClick = { vm.share(listOf(item)) }) { Icon(Icons.Filled.Share, null) }
                OutlinedButton(onClick = { exportOne.launch(item.name) }) { Icon(Icons.Filled.SaveAlt, null) }
            }
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.item_title_label)) }, placeholder = { Text(item.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text(stringResource(R.string.item_tags_label)) }, placeholder = { Text(stringResource(R.string.item_tags_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.item_note_label)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (dirty) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { save() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
                }
                Spacer(Modifier.height(24.dp))
                InfoLine(stringResource(R.string.item_info_name), item.name)
                InfoLine(stringResource(R.string.item_info_folder), item.folder.ifEmpty { stringResource(R.string.folder_root) })
                InfoLine(stringResource(R.string.item_info_type), item.mime)
                InfoLine(stringResource(R.string.item_info_size), Format.size(item.size))
                InfoLine(stringResource(R.string.item_info_added), Format.shortDate(context, item.createdAt))
                InfoLine(stringResource(R.string.item_info_modified), Format.shortDate(context, item.modifiedAt))
                item.sha256?.let { InfoLine("SHA-256", it.take(16) + "…") }
            }
        }
    }

    notice?.let { action ->
        ConfirmDialog(
            title = stringResource(R.string.open_with_notice_title),
            text = stringResource(R.string.open_with_notice_body),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = { AppPrefs.setOpenWithNoticeShown(context); notice = null; action() },
            onDismiss = { notice = null },
        )
    }
    when (val d = dialog) {
        is BrowserDialog.RenameItem -> TextInputDialog(
            title = stringResource(R.string.dialog_rename), initial = d.item.name, label = stringResource(R.string.file_name_label),
            onConfirm = { dialog = null; vm.renameItem(d.item, it) }, onDismiss = { dialog = null },
        )
        BrowserDialog.MoveItems -> FolderPickerDialog(vm.folders(), excluding = null, current = item.folder,
            onPick = { dialog = null; vm.moveSelected(it) }, onDismiss = { dialog = null; vm.clearSelection() })
        is BrowserDialog.DeleteItem -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title), text = stringResource(R.string.dialog_delete_item, d.item.displayTitle),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { dialog = null; vm.deleteItem(d.item); onBack() }, onDismiss = { dialog = null },
        )
        else -> Unit
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
