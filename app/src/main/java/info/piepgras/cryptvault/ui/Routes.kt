package info.piepgras.cryptvault.ui

import kotlinx.serialization.Serializable

/** Navigation Compose routes. The browser pushes one route per folder, so Back goes up. */
@Serializable object VaultsRoute
@Serializable object CreateVaultRoute
@Serializable data class UnlockRoute(val vaultId: String)
@Serializable data class BrowserRoute(val vaultId: String, val folder: String = "")
@Serializable data class ItemRoute(val vaultId: String, val itemId: String)
@Serializable data class NoteRoute(val vaultId: String, val folder: String = "", val itemId: String? = null)
@Serializable data class VaultSettingsRoute(val vaultId: String)
@Serializable data class BackupRoute(val vaultId: String)
@Serializable data class RestoreRoute(val targetId: String? = null, val folder: String? = null)
@Serializable data class ReceiveRoute(val uri: String)
/** Shows the words waiting in `Container.recoveryKeyToShow` for this vault; [thenUnlock] after creation. */
@Serializable data class RecoveryKeyRoute(val vaultId: String, val thenUnlock: Boolean = false)
/** "Forgot password": enter the 44 words and a new password. */
@Serializable data class RecoverRoute(val vaultId: String)
@Serializable object SettingsRoute
@Serializable object AboutRoute
@Serializable object PermissionsRoute
