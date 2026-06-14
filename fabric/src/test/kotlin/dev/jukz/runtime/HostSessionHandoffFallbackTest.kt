package dev.jukz.runtime

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.host.ConnectionServer
import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.host.HostController
import dev.jukz.core.host.LanOpener
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The durability net for a close mid-handoff (the 2026-06-14 shutdown race). When a host leaves with
 * a guest still connected it prefers the live P2P handoff, but if no guest actually takes the world
 * over — they all dropped their control channel, or it timed out — the latest state would be stranded
 * on the leaving host's local disk. So a handoff that reaches nobody must fall back to arming the
 * cloud ghost upload, exactly as a guest-less close already does. A handoff a guest completes must
 * NOT also upload (the guest is the new host and will back it up itself).
 */
class HostSessionHandoffFallbackTest {

    private val world = WorldId.random()
    private val nodeId = NodeId(ByteArray(16).also { it[15] = 7 })
    private val resolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) }

    /** A guest is connected when the close begins, then drops the moment it is told the host is leaving
     *  and never pulls the snapshot — the live shutdown race, where the host tears down mid-transfer. */
    private fun guestLeavesWithoutPulling(port: Int = 50_000) = object : ConnectionServer {
        private val guests = AtomicInteger(1)
        override fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long) = port
        override fun connectedGuestCount() = guests.get()
        override fun notifyGuestsLeaving(snapshot: SnapshotOffer?) { guests.set(0) }
        override fun armSnapshot(pack: ByteArray, head: String, token: String): CountDownLatch = CountDownLatch(1)
        override fun close() {}
    }

    /** A guest pulls the snapshot: the arm latch is already counted down, so the await reports a
     *  completed download (the happy F4 handoff). */
    private fun guestPullsSnapshot(port: Int = 50_000) = object : ConnectionServer {
        override fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long) = port
        override fun connectedGuestCount() = 1
        override fun armSnapshot(pack: ByteArray, head: String, token: String): CountDownLatch = CountDownLatch(0)
        override fun close() {}
    }

    private fun worldDir(generation: Int): Path {
        val dir = Files.createTempDirectory("jukz-handoff-fallback")
        Files.writeString(dir.resolve("level.dat"), "latest-world-state")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(world.uuid, generation.toLong()))
        return dir
    }

    private fun hostWith(server: ConnectionServer): HostController {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val controller = HostController(registry, LanOpener { 45_678 }, server, resolver, nodeId, clock)
        runBlocking { controller.host(world, generation = 45) }
        HostSession.onServerStarting()
        HostSession.install(controller)
        return controller
    }

    @Test
    fun `a handoff no guest completes falls back to arming the cloud ghost upload`() {
        val dir = worldDir(generation = 45)
        GhostUpload.clear()
        try {
            hostWith(guestLeavesWithoutPulling())
            GhostUpload.markArmed() // JukzMod armed the cloud backup: the world is eligible

            HostSession.onServerStopping(dir, world, 45L) {}

            val pending = GhostUpload.pending()
            assertTrue(pending != null, "a handoff that reached nobody must back the world up to the cloud")
            assertEquals(world, pending!!.worldId)
            assertEquals(45L, pending.generation) // the live generation handed in, not stranded on disk
        } finally {
            GhostUpload.clear()
        }
    }

    @Test
    fun `a handoff a guest completes does not also arm the cloud ghost upload`() {
        val dir = worldDir(generation = 45)
        GhostUpload.clear()
        try {
            hostWith(guestPullsSnapshot())
            GhostUpload.markArmed() // eligible, but a guest takes over — no cloud upload needed

            HostSession.onServerStopping(dir, world, 45L) {}

            assertNull(GhostUpload.pending(), "a completed handoff must not also upload to the cloud")
        } finally {
            GhostUpload.clear()
        }
    }
}
