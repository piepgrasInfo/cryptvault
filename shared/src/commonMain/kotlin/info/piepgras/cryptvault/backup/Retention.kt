package info.piepgras.cryptvault.backup

/** What garbage collection removes after a successful commit (docs/VAULT_LAYOUT.md §6.2 step 7). */
data class GcPlan(val deleteSnapshots: List<Long>, val deleteVersions: List<String>) {
    val isEmpty: Boolean get() = deleteSnapshots.isEmpty() && deleteVersions.isEmpty()
}

object Retention {
    const val DEFAULT_KEEP = 5
    const val MIN_KEEP = 1
    const val MAX_KEEP = 20

    fun clampKeep(keep: Int): Int = keep.coerceIn(MIN_KEEP, MAX_KEEP)

    /** The sequence numbers to keep: the newest [keep] of those present. */
    fun retainedSeqs(present: Collection<Long>, keep: Int): List<Long> =
        present.sortedDescending().take(clampKeep(keep)).sorted()

    /**
     * [retained] are the manifests of the retained snapshots (all of them — the caller fetches
     * what it does not have); [versionsPresent] the hashes in `versions/`. A version survives
     * while any retained snapshot references its hash, whether or not the mirror holds the
     * same bytes under some path.
     */
    fun gc(present: Collection<Long>, retained: List<Snapshot>, keep: Int, versionsPresent: Collection<String>): GcPlan {
        val keepSeqs = retainedSeqs(present, keep).toSet()
        require(retained.map { it.seq }.toSet().containsAll(keepSeqs)) { "gc needs every retained snapshot" }
        val referenced = HashSet<String>()
        for (s in retained) if (s.seq in keepSeqs) s.files.forEach { referenced += it.sha256 }
        return GcPlan(
            deleteSnapshots = present.filter { it !in keepSeqs }.sorted(),
            deleteVersions = versionsPresent.filter { it !in referenced }.sorted(),
        )
    }
}

/**
 * Where a restore finds the bytes of a file listed in some retained snapshot: in the mirror if the
 * newest snapshot has the same hash somewhere (the mirror *is* the newest snapshot), else in
 * `versions/`. Hash-addressed, so renames and reverts need no bookkeeping.
 */
object Locate {
    fun mirrorIndex(newest: Snapshot): Map<String, String> = newest.files.associate { it.sha256 to it.path }

    fun remotePath(file: SnapshotFile, mirrorIndex: Map<String, String>): String =
        mirrorIndex[file.sha256] ?: RemoteLayout.versionPath(file.sha256)
}
