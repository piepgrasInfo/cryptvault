package info.piepgras.cryptvault.dist

import info.piepgras.cryptvault.BuildConfig
import info.piepgras.cryptvault.backup.BackupTarget

/**
 * Which of the two builds this is (BUILD_BRIEF.md §13, decided 2026-09-19).
 *
 * `play` is the Google Play build; `foss` is the Play-services-free build distributed through
 * F-Droid, GitHub Releases and piepgras.info, with the applicationId suffix `.foss` so both can
 * be installed at once. The only functional difference is which backup targets exist: Google
 * Drive needs Google Play services, so it can only ever live in the `play` build.
 *
 * The list comes from a per-flavor [FlavorTargets], not from a runtime check, so a target the
 * build must not offer is not compiled into it at all.
 */
object Distribution {

    /** `"play"` or `"foss"`; shown in About so a bug report says which build it came from. */
    val id: String get() = BuildConfig.DISTRIBUTION

    /** Whether this build may link Google Play services. False for `foss`, by construction. */
    val playServicesAllowed: Boolean get() = BuildConfig.PLAY_SERVICES_ALLOWED

    /** The backup target kinds this build offers, in the order the Backup screen shows them. */
    val backupTargets: List<BackupTarget.Kind> get() = FlavorTargets.BACKUP_TARGETS

    /** The target kinds that need Google Play services on the device, and so never ship in `foss`. */
    val googleBackupTargets: Set<BackupTarget.Kind> = setOf(BackupTarget.Kind.DRIVE)

    fun offers(kind: BackupTarget.Kind): Boolean = kind in backupTargets
}
