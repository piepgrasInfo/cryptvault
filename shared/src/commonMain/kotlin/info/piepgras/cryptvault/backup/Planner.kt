package info.piepgras.cryptvault.backup

/** A local ciphertext file as the executor lists it: path relative to the vault folder. */
data class LocalFile(val path: String, val size: Long, val sha256: String)

/** Everything the planner needs to know about the local vault. */
data class LocalState(
    val files: List<LocalFile>,
    /** Keyed by [RemoteLayout.VAULT_CONFIG] and [RemoteLayout.MASTERKEY]. */
    val meta: Map<String, LocalFile>,
    val manifestGeneration: Long = 0,
)

/** Step 1 of docs/VAULT_LAYOUT.md §6.2: who wrote the remote last. */
sealed interface WriterCheck {
    /** No `latest` remotely: the first backup into this folder. */
    data object FirstBackup : WriterCheck

    /** The remote commit point is the one this installation wrote last. */
    data class Ours(val seq: Long) : WriterCheck

    /** Another installation (or a restore) wrote here; stop and ask (Restore from it / Take over). */
    data class Foreign(val remoteSeq: Long, val lastOwnSeq: Long) : WriterCheck
}

fun checkWriter(remote: Latest?, lastOwnSeq: Long): WriterCheck = when {
    remote == null -> WriterCheck.FirstBackup
    remote.seq == lastOwnSeq -> WriterCheck.Ours(remote.seq)
    else -> WriterCheck.Foreign(remote.seq, lastOwnSeq)
}

/** One idempotent remote operation; the executor may repeat a run after an interruption. */
sealed interface Step {
    /** Key that identifies the step across runs (content-addressed), for the run journal. */
    val key: String

    /** Move `mirror/path` to `versions/<sha>.c9r`; if that already exists, delete the mirror file instead. */
    data class Retire(val path: String, val sha256: String, val size: Long) : Step {
        override val key get() = "retire:$path:$sha256"
    }

    /** Upload the local file to `mirror/path` (overwriting). */
    data class Upload(val path: String, val size: Long, val sha256: String) : Step {
        override val key get() = "upload:$path:$sha256"
    }

    /** Move `versions/<sha>.c9r` back to `mirror/path` — a rename or a revert, no upload needed. */
    data class Revive(val path: String, val size: Long, val sha256: String) : Step {
        override val key get() = "revive:$path:$sha256"
    }

    /** Upload `vault.cryptomator` / `masterkey.cryptomator` because its hash changed. */
    data class UploadMeta(val name: String, val size: Long, val sha256: String) : Step {
        override val key get() = "meta:$name:$sha256"
    }
}

data class Plan(
    val seq: Long,
    val steps: List<Step>,
    /** The new snapshot's file list (every current file, all in the mirror after the run). */
    val files: List<SnapshotFile>,
    val unchanged: Int,
) {
    val uploadBytes: Long get() = steps.sumOf { if (it is Step.Upload) it.size else if (it is Step.UploadMeta) it.size else 0L }
    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * Diffs the local vault against the last snapshot this installation wrote (docs/VAULT_LAYOUT.md
 * §6.2 step 2) and produces the steps of the next run, in execution order: retirements first
 * (so the versions area holds every superseded file before anything is overwritten), then
 * revivals, uploads and meta files. Pure: no I/O, no clock.
 */
fun plan(local: LocalState, last: Snapshot?): Plan {
    val lastByPath = last?.files?.associateBy { it.path } ?: emptyMap()
    val localByPath = local.files.associateBy { it.path }
    val steps = ArrayList<Step>()
    var unchanged = 0

    // Missing locally, or modified: retire the old bytes.
    val retired = HashMap<String, ArrayDeque<SnapshotFile>>() // sha → retired entries
    for (old in lastByPath.values) {
        val now = localByPath[old.path]
        if (now == null || now.sha256 != old.sha256) {
            steps += Step.Retire(old.path, old.sha256, old.size)
            retired.getOrPut(old.sha256) { ArrayDeque() }.addLast(old)
        }
    }
    // New or modified: revive a just-retired file with the same bytes, else upload.
    val revived = HashSet<String>()
    for (file in local.files) {
        val old = lastByPath[file.path]
        if (old != null && old.sha256 == file.sha256) { unchanged++; continue }
        if (file.sha256 in retired && file.sha256 !in revived) {
            revived += file.sha256
            steps += Step.Revive(file.path, file.size, file.sha256)
        } else {
            steps += Step.Upload(file.path, file.size, file.sha256)
        }
    }
    for (name in RemoteLayout.META_FILES) {
        val now = local.meta[name] ?: continue
        val before = last?.meta?.get(name)
        if (before == null || before.sha256 != now.sha256) steps += Step.UploadMeta(name, now.size, now.sha256)
    }
    val files = local.files.map { SnapshotFile(it.path, it.size, it.sha256, Stored.MIRROR) }
    return Plan(seq = (last?.seq ?: 0L) + 1, steps = steps, files = files, unchanged = unchanged)
}

/** Builds the snapshot a completed run commits. */
fun snapshotFor(plan: Plan, local: LocalState, vaultId: String, vaultName: String, installation: String, createdAt: String): Snapshot =
    Snapshot(
        seq = plan.seq,
        vaultId = vaultId,
        vaultName = vaultName,
        createdAt = createdAt,
        installation = installation,
        manifestGeneration = local.manifestGeneration,
        meta = local.meta.mapValues { SnapshotMeta(it.value.size, it.value.sha256) },
        files = plan.files,
    )
