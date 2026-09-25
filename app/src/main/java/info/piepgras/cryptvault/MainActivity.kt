package info.piepgras.cryptvault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.IntentCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import info.piepgras.cryptvault.prefs.AppPrefs
import info.piepgras.cryptvault.security.SecureWindow
import info.piepgras.cryptvault.ui.AboutRoute
import info.piepgras.cryptvault.ui.AboutScreen
import info.piepgras.cryptvault.ui.BackupRoute
import info.piepgras.cryptvault.ui.BackupScreen
import info.piepgras.cryptvault.ui.BrowserRoute
import info.piepgras.cryptvault.ui.BrowserScreen
import info.piepgras.cryptvault.ui.CreateVaultRoute
import info.piepgras.cryptvault.ui.CreateVaultScreen
import info.piepgras.cryptvault.ui.ItemDetailScreen
import info.piepgras.cryptvault.ui.ItemRoute
import info.piepgras.cryptvault.ui.NoteEditorScreen
import info.piepgras.cryptvault.ui.NoteRoute
import info.piepgras.cryptvault.ui.PermissionsRoute
import info.piepgras.cryptvault.ui.PermissionsScreen
import info.piepgras.cryptvault.ui.ReceiveRoute
import info.piepgras.cryptvault.ui.ReceiveScreen
import info.piepgras.cryptvault.ui.RecoverRoute
import info.piepgras.cryptvault.ui.RestoreRoute
import info.piepgras.cryptvault.ui.RestoreScreen
import info.piepgras.cryptvault.ui.RecoverScreen
import info.piepgras.cryptvault.ui.RecoveryKeyRoute
import info.piepgras.cryptvault.ui.RecoveryKeyScreen
import info.piepgras.cryptvault.ui.ScrollingDialogText
import info.piepgras.cryptvault.ui.SettingsRoute
import info.piepgras.cryptvault.ui.SettingsScreen
import info.piepgras.cryptvault.ui.UnlockRoute
import info.piepgras.cryptvault.ui.UnlockScreen
import info.piepgras.cryptvault.ui.VaultListScreen
import info.piepgras.cryptvault.ui.VaultSettingsRoute
import info.piepgras.cryptvault.ui.VaultSettingsScreen
import info.piepgras.cryptvault.ui.VaultsRoute
import info.piepgras.cryptvault.ui.theme.CryptVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single Activity: the navigation graph and the startup gates (legal disclaimer →
 * crash-reporting opt-in → app). Runtime permissions are never a gate; each is requested at the
 * point of use. Also the receiver of "Share to CryptVault": shared URIs wait in [pendingImport]
 * until the user has chosen and unlocked a vault.
 */
/*
 * FragmentActivity rather than ComponentActivity: androidx.biometric's BiometricPrompt needs one
 * (it hosts its own fragment). setContent works the same.
 */
class MainActivity : FragmentActivity() {

    companion object {
        /** URIs shared into the app, waiting for a vault and a folder. */
        val pendingImport = MutableStateFlow<List<Uri>>(emptyList())
        /** A container opened from a mail app or file manager, waiting for the receive screen. */
        val pendingContainer = MutableStateFlow<Uri?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SecureWindow.apply(this)
        handleShare(intent)
        setContent {
            CryptVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Gates { App() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> { intent.data?.let { pendingContainer.value = it }; intent.action = null; return }
            else -> return
        }
        if (uris.isNotEmpty()) pendingImport.value = uris
        intent.action = null // do not re-import on configuration change
    }

    @Composable
    private fun Gates(content: @Composable () -> Unit) {
        var legalAccepted by remember { mutableStateOf(AppPrefs.legalAccepted(this)) }
        // A build without a crash-reporting endpoint has nothing to ask.
        var crashAsked by remember { mutableStateOf(!CrashReporting.isAvailable || CrashReporting.hasBeenAsked(this)) }
        when {
            !legalAccepted -> AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.gate_legal_title)) },
                text = { ScrollingDialogText(stringResource(R.string.gate_legal_body)) },
                confirmButton = {
                    TextButton(onClick = { AppPrefs.setLegalAccepted(this); legalAccepted = true }) { Text(stringResource(R.string.gate_legal_accept)) }
                },
                dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.gate_legal_decline)) } },
            )
            !crashAsked -> AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.gate_crash_title)) },
                text = { ScrollingDialogText(stringResource(R.string.gate_crash_body)) },
                confirmButton = {
                    TextButton(onClick = { CrashReporting.setEnabled(this, true); crashAsked = true }) { Text(stringResource(R.string.gate_crash_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { CrashReporting.setEnabled(this, false); crashAsked = true }) { Text(stringResource(R.string.gate_crash_no)) }
                },
            )
            else -> content()
        }
    }

    @Composable
    private fun App() {
        val nav = rememberNavController()
        val pending by pendingImport.collectAsState()
        val container by pendingContainer.collectAsState()
        androidx.compose.runtime.LaunchedEffect(container) {
            container?.let { uri -> pendingContainer.value = null; nav.navigate(ReceiveRoute(uri.toString())) }
        }
        NavHost(navController = nav, startDestination = VaultsRoute) {
            composable<VaultsRoute> {
                VaultListScreen(
                    pendingImportCount = pending.size,
                    onCreate = { nav.navigate(CreateVaultRoute) },
                    onUnlock = { nav.navigate(UnlockRoute(it)) },
                    onOpen = { nav.navigate(BrowserRoute(it)) },
                    onVaultSettings = { nav.navigate(VaultSettingsRoute(it)) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onRestore = { nav.navigate(RestoreRoute()) },
                )
            }
            composable<CreateVaultRoute> {
                CreateVaultScreen(
                    onBack = { nav.popBackStack() },
                    onCreated = { id -> nav.navigate(RecoveryKeyRoute(id, thenUnlock = true)) { popUpTo(VaultsRoute) } },
                )
            }
            composable<UnlockRoute> { entry ->
                val route = entry.toRoute<UnlockRoute>()
                UnlockScreen(
                    vaultId = route.vaultId,
                    onBack = { nav.popBackStack() },
                    onUnlocked = { nav.navigate(BrowserRoute(route.vaultId)) { popUpTo(VaultsRoute) } },
                    onRecover = { nav.navigate(RecoverRoute(route.vaultId)) },
                )
            }
            composable<RecoveryKeyRoute> { entry ->
                val route = entry.toRoute<RecoveryKeyRoute>()
                RecoveryKeyScreen(
                    vaultId = route.vaultId,
                    onDone = {
                        if (route.thenUnlock) nav.navigate(UnlockRoute(route.vaultId)) { popUpTo(VaultsRoute) }
                        else nav.popBackStack()
                    },
                )
            }
            composable<RecoverRoute> { entry ->
                val route = entry.toRoute<RecoverRoute>()
                RecoverScreen(
                    vaultId = route.vaultId,
                    onBack = { nav.popBackStack() },
                    onReset = { nav.navigate(UnlockRoute(route.vaultId)) { popUpTo(VaultsRoute) } },
                )
            }
            composable<BrowserRoute> { entry ->
                val route = entry.toRoute<BrowserRoute>()
                BrowserScreen(
                    vaultId = route.vaultId,
                    folder = route.folder,
                    pendingImport = pending,
                    onPendingImportConsumed = { pendingImport.value = emptyList() },
                    onLocked = { nav.navigate(UnlockRoute(route.vaultId)) { popUpTo(VaultsRoute) } },
                    onBack = { nav.popBackStack() },
                    onOpenFolder = { nav.navigate(BrowserRoute(route.vaultId, it)) },
                    onOpenItem = { nav.navigate(ItemRoute(route.vaultId, it)) },
                    onNewNote = { nav.navigate(NoteRoute(route.vaultId, route.folder)) },
                    onEditNote = { nav.navigate(NoteRoute(route.vaultId, route.folder, it)) },
                    onVaultSettings = { nav.navigate(VaultSettingsRoute(route.vaultId)) },
                )
            }
            composable<ItemRoute> { entry ->
                val route = entry.toRoute<ItemRoute>()
                ItemDetailScreen(
                    vaultId = route.vaultId,
                    itemId = route.itemId,
                    onLocked = { nav.navigate(UnlockRoute(route.vaultId)) { popUpTo(VaultsRoute) } },
                    onBack = { nav.popBackStack() },
                    onEditNote = { nav.navigate(NoteRoute(route.vaultId, itemId = route.itemId)) },
                )
            }
            composable<NoteRoute> { entry ->
                val route = entry.toRoute<NoteRoute>()
                NoteEditorScreen(
                    vaultId = route.vaultId,
                    folder = route.folder,
                    itemId = route.itemId,
                    onLocked = { nav.navigate(UnlockRoute(route.vaultId)) { popUpTo(VaultsRoute) } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<VaultSettingsRoute> { entry ->
                val route = entry.toRoute<VaultSettingsRoute>()
                VaultSettingsScreen(
                    vaultId = route.vaultId,
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.navigate(VaultsRoute) { popUpTo(VaultsRoute) { inclusive = true } } },
                    onShowRecoveryKey = { nav.navigate(RecoveryKeyRoute(route.vaultId)) },
                    onBackup = { nav.navigate(BackupRoute(route.vaultId)) },
                )
            }
            composable<BackupRoute> { entry ->
                val route = entry.toRoute<BackupRoute>()
                BackupScreen(
                    vaultId = route.vaultId,
                    onBack = { nav.popBackStack() },
                    onRestoreFrom = { targetId, folder -> nav.navigate(RestoreRoute(targetId, folder)) },
                )
            }
            composable<RestoreRoute> { entry ->
                val route = entry.toRoute<RestoreRoute>()
                RestoreScreen(
                    preselectTargetId = route.targetId,
                    preselectFolder = route.folder,
                    onBack = { nav.popBackStack() },
                    onDone = { nav.navigate(VaultsRoute) { popUpTo(VaultsRoute) { inclusive = true } } },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onAbout = { nav.navigate(AboutRoute) },
                    onPermissions = { nav.navigate(PermissionsRoute) },
                )
            }
            composable<ReceiveRoute> { entry ->
                val route = entry.toRoute<ReceiveRoute>()
                ReceiveScreen(
                    uri = route.uri,
                    onBack = { nav.popBackStack() },
                    onUnlock = { nav.navigate(UnlockRoute(it)) },
                    onOpenFolder = { vaultId, folder -> nav.navigate(BrowserRoute(vaultId, folder)) { popUpTo(VaultsRoute) } },
                )
            }
            composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }
            composable<PermissionsRoute> { PermissionsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
