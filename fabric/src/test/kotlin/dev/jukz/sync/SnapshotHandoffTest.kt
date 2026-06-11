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
}
