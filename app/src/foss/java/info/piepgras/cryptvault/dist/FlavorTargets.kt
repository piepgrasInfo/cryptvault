package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.backup.BackupTarget

/**
 * The Play-services-free build's backup targets. Google Drive is absent for good: its
 * authorisation runs through Google Play services, which this build does not link and the
 * devices it is made for do not have. Nothing else is missing - the vault format, backup
 * planner, restore, mail containers and the DocumentsProvider are the same code.
 */
internal object FlavorTargets {
    val BACKUP_TARGETS = listOf(BackupTarget.Kind.WEBDAV, BackupTarget.Kind.FOLDER)
}
