package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.backup.BackupTarget

/**
 * The Google Play build's backup targets. Dropbox, OneDrive and Google Drive are not built yet
 * (they await their developer registrations, docs/PROVIDER_SETUP.md); Drive is added to this
 * list - and only to this one - when it is.
 */
internal object FlavorTargets {
    val BACKUP_TARGETS = listOf(BackupTarget.Kind.WEBDAV, BackupTarget.Kind.FOLDER)
}
