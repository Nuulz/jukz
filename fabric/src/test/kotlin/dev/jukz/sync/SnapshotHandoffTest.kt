package dev.jukz.sync

import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostConnectionServer
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.SocketChannel
import dev.jukz.runtime.GhostUpload
import dev.jukz.runtime.HostSession
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/**
 * The F4 world handoff over the live connection: a leaving host arms its [HostConnectionServer] with
 * the save pack and the guest pulls it over a [dev.jukz.core.transport.ConnectionType.SNAPSHOT]
 * channel on the SAME listen port the game uses — the path that already crosses NAT, so the handoff
 * works over the internet, not just the LAN. All loopback here; the cross-NAT reachability is the
 * connection server's, already proven by the join tests.
 */
class SnapshotHandoffTest {

    private val worldId = WorldId.random()
    private val token = ClaimToken(5, 1_700_000_000_000, NodeId.random())

    /** Start a host connection server armed with [hostDir]'s pack under [gate]; returns it + its port. */
    private fun armedHost(hostDir: Path, gate: String): Pair<HostConnectionServer, Int> {
        val server = HostConnectionServer(bindHost = "127.0.0.1")
        val port = server.start(worldId, token, Endpoint("127.0.0.1", 1)) { 0 }
        val pack = SnapshotPack.build(hostDir, JGitWorldSync()) ?: error("snapshot pack should build")
        server.armSnapshot(pack.bytes, pack.head, gate)
        return server to port
    }

    private fun offerRecord(port: Int, gate: String) =
        WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0, snapshot = SnapshotOffer("127.0.0.1", port, gate))

    @Test
    fun `host serves a snapshot over the connection and the guest pulls it, updating the sidecar generation`() {
        val hostDir = Files.createTempDirectory("jukz-host")
        Files.writeString(hostDir.resolve("level.dat"), "world-state-at-gen-5")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 5))

        val (server, port) = armedHost(hostDir, "gate")
        try {
            val guestDir = Files.createTempDirectory("jukz-guest")

            val pulled = runBlocking { JGitWorldSync().pullLatest(guestDir, offerRecord(port, "gate")) }

            assertTrue(pulled)
            assertEquals("world-state-at-gen-5", Files.readString(guestDir.resolve("level.dat")))
            assertEquals(5L, WorldIdSidecar.read(guestDir)?.generation)
        } finally {
            server.close()
        }
    }

    @Test
    fun `downloads the snapshot while the host is up, then applies it after the host has gone`() {
        // The timing fix: the pack must be pulled while the host is still connected (download), and the
        // user's "take over" decision (apply) can come much later — even after the host has withdrawn.
        val hostDir = Files.createTempDirectory("jukz-host-timing")
        Files.writeString(hostDir.resolve("level.dat"), "host-world-gen-7")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 7))

        val (server, port) = armedHost(hostDir, "gate")
        val sync = JGitWorldSync()
        val downloaded = runBlocking { sync.downloadSnapshot(SnapshotOffer("127.0.0.1", port, "gate")) }!!
        server.close() // the host leaves and tears down its connection server BEFORE we apply

        val guestDir = Files.createTempDirectory("jukz-guest-timing")
        Files.writeString(guestDir.resolve("level.dat"), "stale-local-copy")
        val applied = runBlocking { sync.applySnapshot(guestDir, downloaded, worldId, 0L) }

        assertTrue(applied) // apply works from the already-downloaded pack, host gone notwithstanding
        assertEquals("host-world-gen-7", Files.readString(guestDir.resolve("level.dat")))
        assertEquals(7L, WorldIdSidecar.read(guestDir)?.generation)
        Files.deleteIfExists(downloaded.packPath)
    }

    @Test
    fun `guest pulls the snapshot over a relay dial target (handoff across the relay)`() {
        // Reproduces the desync bug: a guest connected via the relay must pull the host's world over
        // that SAME relay session, not a direct socket to a synthetic endpoint. The fake dialer maps
        // ViaRelay -> a loopback socket to the armed host (what the real relay does end to end) and
        // refuses Direct, so a green test proves the pull rode the relay.
        val hostDir = Files.createTempDirectory("jukz-relay-host")
        Files.writeString(hostDir.resolve("level.dat"), "host-world-via-relay")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 8))

        val (server, port) = armedHost(hostDir, "gate")
        try {
            val dialer = ChannelDialer { target ->
                when (target) {
                    is DialTarget.ViaRelay -> SocketChannel(Socket("127.0.0.1", port))
                    is DialTarget.Direct -> error("snapshot must pull over the relay, not direct")
                }
            }
            val guestDir = Files.createTempDirectory("jukz-relay-guest")
            Files.writeString(guestDir.resolve("level.dat"), "stale-local-copy")

            val sync = JGitWorldSync(dialer)
            val downloaded = runBlocking { sync.downloadSnapshot(DialTarget.ViaRelay("session-id"), "gate") }!!
            val applied = runBlocking { sync.applySnapshot(guestDir, downloaded, worldId, 0L) }

            assertTrue(applied)
            assertEquals("host-world-via-relay", Files.readString(guestDir.resolve("level.dat")))
            assertEquals(8L, WorldIdSidecar.read(guestDir)?.generation)
            Files.deleteIfExists(downloaded.packPath)
        } finally {
            server.close()
        }
    }

    @Test
    fun `host rejects a wrong token and the guest does not corrupt its copy`() {
        val hostDir = Files.createTempDirectory("jukz-host2")
        Files.writeString(hostDir.resolve("level.dat"), "host-state")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 9))

        val (server, port) = armedHost(hostDir, "right-token")
        try {
            val guestDir = Files.createTempDirectory("jukz-guest2")
            Files.writeString(guestDir.resolve("level.dat"), "local-copy")

            // The offer carries a bogus gate token: the host rejects it, and the pull stays non-fatal
            // (a thrown exception would fail this test, so the "never throws" contract is covered too).
            val pulled = runBlocking { JGitWorldSync().pullLatest(guestDir, offerRecord(port, "wrong-token")) }

            assertFalse(pulled)
            assertEquals("local-copy", Files.readString(guestDir.resolve("level.dat"))) // untouched
        } finally {
            server.close()
        }
    }

    @Test
    fun `snapshot excludes session_lock so a guest never receives the host's lock`() {
        val hostDir = Files.createTempDirectory("jukz-lock-host")
        Files.writeString(hostDir.resolve("level.dat"), "world")
        Files.writeString(hostDir.resolve("session.lock"), "host-lock") // Minecraft's per-world lock
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 2))

        val (server, port) = armedHost(hostDir, "gate")
        try {
            val guestDir = Files.createTempDirectory("jukz-lock-guest")

            runBlocking { JGitWorldSync().pullLatest(guestDir, offerRecord(port, "gate")) }

            assertTrue(Files.exists(guestDir.resolve("level.dat")))
            assertFalse(Files.exists(guestDir.resolve("session.lock"))) // never transferred
        } finally {
            server.close()
        }
    }

    @Test
    fun `pullLatest with no snapshot offer does not throw and leaves the local copy`() {
        val guestDir = Files.createTempDirectory("jukz-guest3")
        Files.writeString(guestDir.resolve("level.dat"), "untouched-local-copy")
        val record = WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0) // no snapshot

        val pulled = runBlocking { JGitWorldSync().pullLatest(guestDir, record) }

        assertFalse(pulled)
        assertEquals("untouched-local-copy", Files.readString(guestDir.resolve("level.dat")))
    }

    @Test
    fun `pullLatest against an unreachable endpoint stays non-fatal and keeps the local copy`() {
        val guestDir = Files.createTempDirectory("jukz-guest4")
        Files.writeString(guestDir.resolve("level.dat"), "local-only")
        // Port 1 is not listening: the cross-internet failure mode (host gone / unreachable).
        val record = offerRecord(1, "gate")

        val pulled = runBlocking { JGitWorldSync().pullLatest(guestDir, record) }

        assertFalse(pulled)
        assertEquals("local-only", Files.readString(guestDir.resolve("level.dat")))
    }

    @Test
    fun `snapshot pack carries only the current world, not the accumulated git history`() {
        val dir = Files.createTempDirectory("jukz-nogrowth")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, 1))
        val sync = JGitWorldSync()

        // Stand-in for a region file; rewrite it with fresh, incompressible bytes on each "save" so
        // every commit creates a distinct large blob — exactly how Minecraft churns region data.
        fun saveRegion(seed: Int) =
            Files.write(dir.resolve("r.0.0.mca"), kotlin.random.Random(seed).nextBytes(512 * 1024))

        saveRegion(1)
        val first = SnapshotPack.build(dir, sync) ?: error("first build")

        var last = first
        repeat(8) { i ->
            saveRegion(i + 2)
            WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, (i + 2).toLong()))
            last = SnapshotPack.build(dir, sync) ?: error("build $i")
        }

        // Old behaviour (pack everything reachable from HEAD) carried all 9 distinct ~512KB blobs
        // (~9x). Re-rooted at the current tree, the pack holds only the latest one, so it stays near
        // the first build's size no matter how many saves happened.
        assertTrue(
            last.bytes.size < first.bytes.size * 2,
            "snapshot pack accumulated history: first=${first.bytes.size}B last=${last.bytes.size}B",
        )

        // The served head must be a re-rooted, parentless commit.
        org.eclipse.jgit.api.Git.open(dir.toFile()).use { git ->
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { rw ->
                val commit = rw.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(last.head))
                assertEquals(0, commit.parentCount, "snapshot head must be parentless")
            }
        }
    }

    @Test
    fun `compaction reclaims the orphaned history while keeping the current world intact`() {
        val dir = Files.createTempDirectory("jukz-compact")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, 1))
        val sync = JGitWorldSync()
        fun saveRegion(seed: Int) =
            Files.write(dir.resolve("r.0.0.mca"), kotlin.random.Random(seed).nextBytes(512 * 1024))
        fun gitSize() = Files.walk(dir.resolve(".git")).use { s ->
            s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }

        saveRegion(1)
        SnapshotPack.build(dir, sync) ?: error("first build")
        repeat(10) { i ->
            saveRegion(i + 2)
            WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, (i + 2).toLong()))
            SnapshotPack.build(dir, sync) ?: error("build $i")
        }
        val latestRegion = Files.readAllBytes(dir.resolve("r.0.0.mca"))
        val before = gitSize()

        // Force a full prune (no threshold, no grace) — the gated background compaction made determin.
        sync.compactIfNeeded(dir, thresholdBytes = 0L, graceMillis = 0L)
        val after = gitSize()

        assertTrue(after < before / 2, "compaction did not reclaim disk: before=$before after=$after")

        // The current world must still serve a faithful snapshot after the repo was compacted.
        val (server, port) = armedHost(dir, "gate")
        try {
            val guestDir = Files.createTempDirectory("jukz-compact-guest")
            val pulled = runBlocking { JGitWorldSync().pullLatest(guestDir, offerRecord(port, "gate")) }
            assertTrue(pulled)
            assertTrue(
                latestRegion.contentEquals(Files.readAllBytes(guestDir.resolve("r.0.0.mca"))),
                "compaction corrupted or dropped the current world",
            )
            assertEquals(11L, WorldIdSidecar.read(guestDir)?.generation)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a guest-less close arms the ghost upload even when host announce never completed`() {
        // The race the live logs showed: a player opens a world and closes it within a second or two,
        // before auto-hosting finished installing a HostController. The world is still ours and must be
        // backed up — its id + generation come from the live WorldIdState, handed in by JukzMod.
        val dir = Files.createTempDirectory("jukz-race")
        Files.writeString(dir.resolve("level.dat"), "world-state")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, 41)) // a deliberately stale sidecar
        GhostUpload.clear()
        try {
            GhostUpload.markArmed() // JukzMod armed it; no controller was ever installed
            HostSession.onServerStopping(dir, worldId, 42L) {}

            val pending = GhostUpload.pending()
            assertTrue(pending != null, "a guest-less close must arm the ghost without a host controller")
            assertEquals(worldId, pending!!.worldId)
            assertEquals(42L, pending.generation) // the live generation handed in, not the stale sidecar's 41
        } finally {
            GhostUpload.clear()
        }
    }
}
