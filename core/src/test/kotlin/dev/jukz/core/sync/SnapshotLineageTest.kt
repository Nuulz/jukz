package dev.jukz.core.sync

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lineage guard that stops a takeover from silently replacing an already-applied snapshot with an
 * older or sibling one. Two split-brain forks share the same generation integer (e.g. both gen 40) but
 * differ by head commit, so generation alone cannot tell the canonical state from a stale fork — the
 * incident 2026-06-13 lost the shared-session snapshot (008f445…) to an older solo fork (d147416…),
 * both gen 40. [SnapshotLineage.shouldReplace] is what makes that overwrite impossible.
 */
class SnapshotLineageTest {

    @Test
    fun `replaces when nothing has been applied yet`() {
        assertTrue(SnapshotLineage.shouldReplace(null, SnapshotMarker(40, "d147416")))
    }

    @Test
    fun `replaces a strictly older generation`() {
        val applied = SnapshotMarker(39, "old")
        assertTrue(SnapshotLineage.shouldReplace(applied, SnapshotMarker(40, "new")))
    }

    @Test
    fun `does not replace a strictly newer generation with an older one`() {
        val applied = SnapshotMarker(40, "shared")
        assertFalse(SnapshotLineage.shouldReplace(applied, SnapshotMarker(39, "stale")))
    }

    @Test
    fun `does not replace a same-generation sibling fork`() {
        // The incident: applied the shared gen-40 snapshot; a stale gen-40 solo fork must not overwrite it.
        val applied = SnapshotMarker(40, "008f44591e3cc4a0334633fe67c9b9f5470ff357")
        val sibling = SnapshotMarker(40, "d147416522bf01e245768f855822d4d52dd57453")
        assertFalse(SnapshotLineage.shouldReplace(applied, sibling))
    }

    @Test
    fun `replaces with the identical commit (idempotent re-apply)`() {
        val applied = SnapshotMarker(40, "008f44591")
        assertTrue(SnapshotLineage.shouldReplace(applied, SnapshotMarker(40, "008f44591")))
    }
}
