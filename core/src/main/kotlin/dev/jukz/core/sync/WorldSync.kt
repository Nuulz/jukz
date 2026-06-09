package dev.jukz.core.sync

import dev.jukz.core.discovery.WorldRecord
import java.nio.file.Path

/** Identifies a committed world snapshot (e.g. a git commit hash). */
@JvmInline
value class CommitId(val value: String)

/**
 * Cold-start world transfer + canonical-state guard (spec §4.5). Implemented for real by a
 * JGit-backed adapter. The monotonic [WorldRecord.hostGeneration] is the anti-stale guard:
 * a node may only become host once its local copy's generation is >= the highest seen.
 */
interface WorldSync {
    /** The generation of the local save copy. */
    fun currentGeneration(saveDir: Path): Long

    /** Snapshot the save at the given generation; returns the snapshot id. */
    suspend fun commit(saveDir: Path, generation: Long): CommitId

    /** Fetch the latest snapshot for [target] into [saveDir] before becoming host. */
    suspend fun pullLatest(saveDir: Path, target: WorldRecord)
}
