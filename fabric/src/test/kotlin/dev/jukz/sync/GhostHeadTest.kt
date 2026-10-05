package dev.jukz.sync

import dev.jukz.core.model.GameVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GhostHeadTest {
    @Test
    fun `a head carries the version it was saved on`() =
        assertEquals(R2SnapshotStore.GhostHead(12, "abc", GameVersion("1.21.11", 4671)), R2SnapshotStore.parseHead("12 abc 4671 1.21.11"))

    @Test
    fun `version names with spaces survive`() =
        assertEquals(GameVersion("26.2 Pre-Release 1", 4800), R2SnapshotStore.parseHead("3 abc 4800 26.2 Pre-Release 1").game)

    @Test
    fun `a head from an older mod has no version`() =
        assertEquals(R2SnapshotStore.GhostHead(12, "abc", null), R2SnapshotStore.parseHead("12 abc\n"))

    @Test
    fun `a legacy head holding only the commit reads as generation 0`() =
        assertEquals(R2SnapshotStore.GhostHead(0, "abc", null), R2SnapshotStore.parseHead("abc"))
}
