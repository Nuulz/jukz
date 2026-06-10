package dev.jukz.sync

import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SnapshotHandoffTest {

    private val worldId = WorldId.random()
    private val token = ClaimToken(5, 1_700_000_000_000, NodeId.random())

    @Test
    fun `host serves a snapshot and the guest pulls it, updating the sidecar generation`() {
        val hostDir = Files.createTempDirectory("jukz-host")
        Files.writeString(hostDir.resolve("level.dat"), "world-state-at-gen-5")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 5))

        val server = SnapshotServer.serve(hostDir, JGitWorldSync(), "127.0.0.1")
            ?: error("snapshot server should have started")
        try {
            val record = WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0, snapshot = server.offer)
            val guestDir = Files.createTempDirectory("jukz-guest")

            runBlocking { JGitWorldSync().pullLatest(guestDir, record) }

            assertEquals("world-state-at-gen-5", Files.readString(guestDir.resolve("level.dat")))
            assertEquals(5L, WorldIdSidecar.read(guestDir)?.generation)
        } finally {
            server.close()
        }
    }

    @Test
    fun `host rejects a wrong token with 403 and the guest does not corrupt its copy`() {
        val hostDir = Files.createTempDirectory("jukz-host2")
        Files.writeString(hostDir.resolve("level.dat"), "host-state")
        WorldIdSidecar.write(hostDir, WorldIdSidecar.Info(worldId.uuid, 9))

        val server = SnapshotServer.serve(hostDir, JGitWorldSync(), "127.0.0.1")
            ?: error("snapshot server should have started")
        try {
            // A record whose offer carries a bogus token: the download is rejected, pull stays non-fatal.
            val badOffer = server.offer.copy(token = "deadbeef".repeat(8))
            val record = WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0, snapshot = badOffer)
            val guestDir = Files.createTempDirectory("jukz-guest2")
            Files.writeString(guestDir.resolve("level.dat"), "local-copy")

            assertDoesNotThrow { runBlocking { JGitWorldSync().pullLatest(guestDir, record) } }

            // The 403 leaves the guest's local copy untouched.
            assertEquals("local-copy", Files.readString(guestDir.resolve("level.dat")))
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

        val server = SnapshotServer.serve(hostDir, JGitWorldSync(), "127.0.0.1")
            ?: error("snapshot server should have started even with a session.lock present")
        try {
            val record = WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0, snapshot = server.offer)
            val guestDir = Files.createTempDirectory("jukz-lock-guest")

            runBlocking { JGitWorldSync().pullLatest(guestDir, record) }

            assertTrue(Files.exists(guestDir.resolve("level.dat")))
            assertFalse(Files.exists(guestDir.resolve("session.lock"))) // never transferred
        } finally {
            server.close()
        }
    }

    @Test
    fun `pullLatest with no snapshot offer logs a warning and does not throw`() {
        val guestDir = Files.createTempDirectory("jukz-guest3")
        Files.writeString(guestDir.resolve("level.dat"), "untouched-local-copy")
        val record = WorldRecord(worldId, token, listOf(Endpoint("127.0.0.1", 1)), 0) // no snapshot

        assertDoesNotThrow { runBlocking { JGitWorldSync().pullLatest(guestDir, record) } }

        assertEquals("untouched-local-copy", Files.readString(guestDir.resolve("level.dat")))
    }
}
