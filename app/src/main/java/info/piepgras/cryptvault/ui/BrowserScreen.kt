package info.piepgras.cryptvault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import info.piepgras.cryptvault.CryptVaultApp
import info.piepgras.cryptvault.R
import info.piepgras.cryptvault.items.Folder
import info.piepgras.cryptvault.items.ImportSource
import info.piepgras.cryptvault.items.Item
import info.piepgras.cryptvault.items.ItemKind
import info.piepgras.cryptvault.items.ItemQuery
import info.piepgras.cryptvault.items.Names
import info.piepgras.cryptvault.items.SortKey
import info.piepgras.cryptvault.prefs.AppPrefs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    vaultId: String,
    folder: String,
    pendingImport: List<Uri>,
    onPendingImportConsumed: () -> Unit,
    onLocked: () -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenItem: (String) -> Unit,
    onNewNote: () -> Unit,
    onEditNote: (String) -> Unit,
    onVaultSettings: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = CryptVaultApp.container(context)
    val vm: BrowserViewModel = viewModel(key = "browser-$vaultId") { BrowserViewModel(container, vaultId) }
    val open by vm.open.collectAsState()
    val vault = open[vaultId]
    LaunchedEffect(vault) { if (vault == null) onLocked() }
    if (vault == null) return

    val manifest by vault.manifest.collectAsState()
    val selection by vm.selection.collectAsState()
    val progress by vm.progress.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortKey by rememberSaveable { mutableStateOf(AppPrefs.sortKey(context)) }
    var ascending by rememberSaveable { mutableStateOf(AppPrefs.sortAscending(context)) }
    var grid by rememberSaveable { mutableStateOf(AppPrefs.grid(context)) }
    var fabOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    var notice by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun withNotice(action: () -> Unit) {
        if (AppPrefs.openWithNoticeShown(context)) action() else notice = action
    }

    val listing = remember(manifest, folder, query, sortKey, ascending) {
        val l = vault.listing(folder)
        val items = if (query.isBlank()) l.items else ItemQuery.filter(manifest.items, query)
        l.copy(folders = if (query.isBlank()) l.folders else emptyList(), items = ItemQuery.sort(items, sortKey, ascending))
    }

    // Events → UI
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val launchSender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is BrowserEvent.Message -> snackbar.showSnackbar(e.text)
                is BrowserEvent.MessageRes -> snackbar.showSnackbar(e.format(resources))
                is BrowserEvent.Launch -> runCatching { launch.launch(e.intent) }
                    .onFailure { snackbar.showSnackbar(resources.getString(R.string.msg_no_app)) }
                is BrowserEvent.LaunchSender -> runCatching { launchSender.launch(IntentSenderRequest.Builder(e.sender).build()) }
                is BrowserEvent.AskDeleteOriginals -> dialog = BrowserDialog.DeleteOriginals(e.sources)
            }
        }
    }

    // Save-back check on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.checkSaveBacks() }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    // Launchers
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importUris(uris, folder) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> vm.importUris(uris, folder) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onCaptured(ok, folder) }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.exportSelected(uri) }
    var exportItem by remember { mutableStateOf<Item?>(null) }
    val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = exportItem
        if (uri != null && item != null) vm.exportItem(item, uri)
        exportItem = null
    }
    val hasCamera = remember { android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }

    val title = if (folder.isEmpty()) vm.record()?.name ?: "" else Names.last(folder)

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) } },
                    actions = {
                        IconButton(onClick = { vm.selectAll(listing.items.map { it.id }) }) { Icon(Icons.Filled.SelectAll, stringResource(R.string.action_select_all)) }
                        IconButton(onClick = { vm.shareSelected() }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                        IconButton(onClick = { dialog = BrowserDialog.MoveItems }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move)) }
                        IconButton(onClick = { exportTree.launch(null) }) { Icon(Icons.Filled.SaveAlt, stringResource(R.string.action_export)) }
                        IconButton(onClick = { dialog = BrowserDialog.DeleteItems }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                        var sortMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.action_sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (key in SortKey.entries) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(sortLabel(key))) },
                                    trailingIcon = if (key == sortKey) ({ Icon(Icons.Filled.Check, null) }) else null,
                                    onClick = {
                                        if (key == sortKey) ascending = !ascending else { sortKey = key; ascending = true }
                                        AppPrefs.setSort(context, sortKey, ascending)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { grid = !grid; AppPrefs.setGrid(context, grid) }) {
                            Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, stringResource(R.string.action_toggle_grid))
                        }
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.vault_settings_title)) }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { more = false; onVaultSettings() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_lock)) }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, onClick = { more = false; vm.lock() })
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (fabOpen) {
                    FabEntry(Icons.Filled.CreateNewFolder, stringResource(R.string.fab_new_folder)) { fabOpen = false; dialog = BrowserDialog.NewFolder }
                    FabEntry(Icons.AutoMirrored.Filled.NoteAdd, stringResource(R.string.fab_new_note)) { fabOpen = false; onNewNote() }
                    if (hasCamera) FabEntry(Icons.Filled.PhotoCamera, stringResource(R.string.fab_capture)) { fabOpen = false; capture.launch(vm.captureUri()) }
                    FabEntry(Icons.Filled.PhotoLibrary, stringResource(R.string.fab_import_media)) {
                        fabOpen = false
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    FabEntry(Icons.Filled.UploadFile, stringResource(R.string.fab_import_files)) { fabOpen = false; pickFiles.launch(arrayOf("*/*")) }
                }
                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(if (fabOpen) Icons.Filled.Close else Icons.Filled.Add, stringResource(R.string.fab_add))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pendingImport.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.browser_pending_import, pendingImport.size, pendingImport.size), Modifier.weight(1f))
                        TextButton(onClick = onPendingImportConsumed) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { vm.importUris(pendingImport, folder); onPendingImportConsumed() }) { Text(stringResource(R.string.action_import_here)) }
                    }
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (listing.folders.isEmpty() && listing.items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.browser_empty) else stringResource(R.string.browser_no_matches),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(listing.folders, key = { "f:" + it.path }) { f ->
                        FolderTile(f, onClick = { onOpenFolder(f.path) }, onLongClick = { dialog = BrowserDialog.FolderMenu(f) })
                    }
                    items(listing.items, key = { it.id }) { item ->
                        ItemTile(vm, item, selected = item.id in selection,
                            onClick = { if (selection.isNotEmpty()) vm.toggle(item.id) else if (item.kind == ItemKind.NOTE) onEditNote(item.id) else onOpenItem(item.id) },
                            onLongClick = { vm.toggle(item.id) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(listing.folders, key = { "f:" + it.path }) { f ->
                        FolderRow(f, onClick = { onOpenFolder(f.path) }, onLongClick = { dialog = BrowserDialog.FolderMenu(f) })
                    }
                    items(listing.items, key = { it.id }) { item ->
                        ItemRow(vm, item, selected = item.id in selection, showPath = query.isNotBlank(),
                            onClick = { if (selection.isNotEmpty()) vm.toggle(item.id) else if (item.kind == ItemKind.NOTE) onEditNote(item.id) else onOpenItem(item.id) },
                            onLongClick = { vm.toggle(item.id) },
                            onMenu = { dialog = BrowserDialog.ItemMenu(item) })
                    }
                }
            }
        }
    }

    progress?.let { p ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(p.labelRes)) },
            text = {
                Column {
                    Text(if (p.total > 1) stringResource(R.string.progress_n_of_m, p.current, p.total) else "")
                    Spacer(Modifier.height(8.dp))
                    if (p.ofBytes > 0) LinearProgressIndicator(progress = { (p.bytes.toFloat() / p.ofBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { },
        )
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
        null -> Unit
        BrowserDialog.NewFolder -> TextInputDialog(
            title = stringResource(R.string.dialog_new_folder), label = stringResource(R.string.folder_name_label),
            onConfirm = { dialog = null; vm.createFolder(folder, it) }, onDismiss = { dialog = null },
        )
        BrowserDialog.MoveItems -> FolderPickerDialog(vm.folders(), excluding = null, current = folder,
            onPick = { dialog = null; vm.moveSelected(it) }, onDismiss = { dialog = null })
        BrowserDialog.DeleteItems -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            text = pluralStringResource(R.plurals.dialog_delete_items, selection.size, selection.size),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { dialog = null; vm.deleteSelected() }, onDismiss = { dialog = null },
        )
        is BrowserDialog.DeleteOriginals -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_originals_title),
            text = pluralStringResource(R.plurals.dialog_delete_originals, d.sources.size, d.sources.size),
            confirmLabel = stringResource(R.string.action_delete_originals), destructive = true,
            onConfirm = { dialog = null; vm.deleteOriginals(d.sources) }, onDismiss = { dialog = null },
        )
        is BrowserDialog.FolderMenu -> FolderMenuDialog(d.folder,
            onRename = { dialog = BrowserDialog.RenameFolder(d.folder) },
            onMove = { dialog = BrowserDialog.MoveFolder(d.folder) },
            onDelete = { dialog = BrowserDialog.DeleteFolder(d.folder) },
            onDismiss = { dialog = null })
        is BrowserDialog.RenameFolder -> TextInputDialog(
            title = stringResource(R.string.dialog_rename), initial = d.folder.name, label = stringResource(R.string.folder_name_label),
            onConfirm = { dialog = null; vm.renameFolder(d.folder, it) }, onDismiss = { dialog = null },
        )
        is BrowserDialog.MoveFolder -> FolderPickerDialog(vm.folders(), excluding = d.folder.path, current = d.folder.parent,
            onPick = { dialog = null; vm.moveFolder(d.folder, it) }, onDismiss = { dialog = null })
        is BrowserDialog.DeleteFolder -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            text = stringResource(R.string.dialog_delete_folder, d.folder.name),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { dialog = null; vm.deleteFolder(d.folder) }, onDismiss = { dialog = null },
        )
        is BrowserDialog.ItemMenu -> ItemMenuDialog(d.item,
            onOpen = { dialog = null; withNotice { vm.openItem(d.item, false) } },
            onEdit = { dialog = null; withNotice { vm.openItem(d.item, true) } },
            onShare = { dialog = null; vm.share(listOf(d.item)) },
            onExport = { dialog = null; exportItem = d.item; exportOne.launch(d.item.name) },
            onRename = { dialog = BrowserDialog.RenameItem(d.item) },
            onMove = { dialog = null; vm.selectAll(listOf(d.item.id)); dialog = BrowserDialog.MoveItems },
            onDelete = { dialog = BrowserDialog.DeleteItem(d.item) },
            onDismiss = { dialog = null })
        is BrowserDialog.RenameItem -> TextInputDialog(
            title = stringResource(R.string.dialog_rename), initial = d.item.name, label = stringResource(R.string.file_name_label),
            onConfirm = { dialog = null; vm.renameItem(d.item, it) }, onDismiss = { dialog = null },
        )
        is BrowserDialog.DeleteItem -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            text = stringResource(R.string.dialog_delete_item, d.item.displayTitle),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { dialog = null; vm.deleteItem(d.item) }, onDismiss = { dialog = null },
        )
    }
}

sealed class BrowserDialog {
    object NewFolder : BrowserDialog()
    object MoveItems : BrowserDialog()
    object DeleteItems : BrowserDialog()
    data class DeleteOriginals(val sources: List<ImportSource>) : BrowserDialog()
    data class FolderMenu(val folder: Folder) : BrowserDialog()
    data class RenameFolder(val folder: Folder) : BrowserDialog()
    data class MoveFolder(val folder: Folder) : BrowserDialog()
    data class DeleteFolder(val folder: Folder) : BrowserDialog()
    data class ItemMenu(val item: Item) : BrowserDialog()
    data class RenameItem(val item: Item) : BrowserDialog()
    data class DeleteItem(val item: Item) : BrowserDialog()
}

fun sortLabel(key: SortKey): Int = when (key) {
    SortKey.NAME -> R.string.sort_name
    SortKey.DATE -> R.string.sort_date
    SortKey.SIZE -> R.string.sort_size
    SortKey.KIND -> R.string.sort_kind
}

fun iconFor(item: Item): ImageVector = when {
    item.kind == ItemKind.NOTE -> Icons.Filled.Description
    item.mime.startsWith("image/") -> Icons.Filled.Image
    item.mime.startsWith("video/") -> Icons.Filled.Movie
    item.mime.startsWith("audio/") -> Icons.Filled.AudioFile
    item.mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun FabEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = 2.dp, onClick = onClick) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        SmallFloatingActionButton(onClick = onClick) { Icon(icon, label) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onLongClick) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(folder: Folder, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.padding(4.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(folder.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(vm: BrowserViewModel, item: Item, selected: Boolean, showPath: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.thumb) { value = vm.thumbnail(item) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(iconFor(item), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                if (showPath && item.folder.isNotEmpty()) append(item.folder).append(" · ")
                append(Format.size(item.size)).append(" · ").append(Format.date(context, item.modifiedAt))
                if (item.tags.isNotEmpty()) append(" · ").append(item.tags.joinToString(" ") { "#$it" })
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemTile(vm: BrowserViewModel, item: Item, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.thumb) { value = vm.thumbnail(item) }
    Column(
        Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(iconFor(item), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
        }
        Text(item.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FolderPickerDialog(folders: List<Folder>, excluding: String?, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val choices = listOf("") + folders.map { it.path }.filter { excluding == null || !Names.isWithin(it, excluding) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_move_to)) },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                items(choices) { path ->
                    val enabled = path != current
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = { onPick(path) }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(12.dp))
                        Text(if (path.isEmpty()) stringResource(R.string.folder_root) else path, color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun FolderMenuDialog(folder: Folder, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column {
                MenuLine(Icons.Filled.Description, stringResource(R.string.action_rename), onRename)
                MenuLine(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move), onMove)
                MenuLine(Icons.Filled.Delete, stringResource(R.string.action_delete), onDelete)
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ItemMenuDialog(
    item: Item,
    onOpen: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onExport: () -> Unit,
    onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                MenuLine(Icons.AutoMirrored.Filled.InsertDriveFile, stringResource(R.string.action_open_with), onOpen)
                MenuLine(Icons.Filled.Description, stringResource(R.string.action_edit_with), onEdit)
                MenuLine(Icons.Filled.Share, stringResource(R.string.action_share), onShare)
                MenuLine(Icons.Filled.SaveAlt, stringResource(R.string.action_export), onExport)
                MenuLine(Icons.Filled.Description, stringResource(R.string.action_rename), onRename)
                MenuLine(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.action_move), onMove)
                MenuLine(Icons.Filled.Delete, stringResource(R.string.action_delete), onDelete)
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
