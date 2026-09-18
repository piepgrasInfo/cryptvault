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
@Serializable object SettingsRoute
@Serializable object AboutRoute
@Serializable object PermissionsRoute
