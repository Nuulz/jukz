package dev.jukz.core.sync

/**
 * Identity of a world snapshot for lineage comparisons: the monotonic fencing [generation] plus the
 * [headCommit] the snapshot resets to. Two divergent forks of one world can share a [generation]
 * (both bumped 39 -> 40 independently during a split-brain) yet differ by [headCommit], which is the
 * only field that distinguishes the canonical state from a stale sibling.
 */
data class SnapshotMarker(val generation: Long, val headCommit: String)

/**
 * Decides whether a candidate snapshot may replace the one already applied locally. Generation alone is
 * not a content-recency measure: a split-brain leaves two lineages with the same generation on
 * different commits, so a strictly-larger generation can still belong to the wrong branch. This guard
 * therefore replaces only when the candidate is a strictly-newer generation or the *identical* commit
 * (an idempotent re-apply) — never an older generation, and never a same-generation sibling fork. It is
 * the fix for the 2026-06-13 state loss, where a guest's just-applied shared-session snapshot was
 * silently overwritten by its own older solo fork (both gen 40) pulled back from the cloud.
 */
object SnapshotLineage {

    /** True if [candidate] should overwrite [applied]; see the class doc for the lineage rule. */
    fun shouldReplace(applied: SnapshotMarker?, candidate: SnapshotMarker): Boolean {
        if (applied == null) return true // nothing to protect
        if (candidate.generation > applied.generation) return true // strictly newer lineage
        if (candidate.generation == applied.generation) return candidate.headCommit == applied.headCommit
        return false // strictly older
    }
}
