package dev.jukz.runtime

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.host.ConnectionServer
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostResult
import dev.jukz.core.host.LanOpener
import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The self-heal teardown a superseded auto-host runs (RC1). When a newer host takes over, the loser
 * must stop serving and withdraw its record — but via the token CAS, so it never clobbers the winner —
 * and it must NOT hand off or back up its losing fork. Before this existed, a superseded auto-host kept
 * serving and re-announcing forever (the 2026-06-13 permanent split-brain).
 */
class HostSessionSupersedeTest {

    private val world = WorldId.random()
    private val nodeId = NodeId(ByteArray(16).also { it[15] = 7 })
    private val resolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) }
    private fun fakeServer(listenPort: Int) = object : ConnectionServer {
        override fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long) = listenPort
        override fun close() {}
    }

    @Test
    fun `stopHostingSuperseded withdraws our record and clears the session`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val controller = HostController(registry, LanOpener { 45_678 }, fakeServer(50_000), resolver, nodeId, clock)
        controller.host(world, generation = 1)
        assertTrue(registry.lookup(world) != null)

        HostSession.onServerStarting() // reset the stopped flag so install takes
        HostSession.install(controller)
        assertTrue(HostSession.isHosting)

        HostSession.stopHostingSuperseded()

        assertFalse(HostSession.isHosting)
        assertNull(registry.lookup(world)) // our record withdrawn (CAS on our own token)
    }

    @Test
    fun `stopHostingSuperseded does not clobber the winner's record`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val controller = HostController(registry, LanOpener { 45_678 }, fakeServer(50_000), resolver, nodeId, clock)
        controller.host(world, generation = 1)

        // A newer host supersedes us in the registry.
        val winner = dev.jukz.core.discovery.WorldRecord(
            world, ClaimToken(2, 2_000, NodeId(ByteArray(16).also { it[15] = 9 })), Endpoint("10.0.0.9", 25565), 0,
        )
        registry.publishIfNewer(winner)

        HostSession.onServerStarting()
        HostSession.install(controller)
        HostSession.stopHostingSuperseded()

        // Our CAS-on-our-token withdraw must leave the winner untouched.
        assertEquals(winner, registry.lookup(world))
        assertFalse(HostSession.isHosting)
    }

    /**
     * The split-brain storm from the 2026-06-14 logs, end to end: two clients reopen at once, both load
     * the same cloud generation and bump it to the SAME next generation, and both auto-host. The token
     * CAS picks one winner on the equal-generation tiebreak; the loser must self-heal (its heartbeat
     * reports loss) AND, on its world close, neither re-publish nor back its fork up to the cloud — the
     * two durability invariants that, missing, let a losing fork re-pollute discovery/R2.
     */
    @Test
    fun `a host superseded at the same generation neither re-publishes nor backs up its fork`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val nodeA = NodeId(ByteArray(16).also { it[15] = 7 })
        val nodeB = NodeId(ByteArray(16).also { it[15] = 9 }) // higher nodeId wins the equal-gen tiebreak
        val dir = Files.createTempDirectory("jukz-split-brain")
        val loser = HostController(registry, LanOpener { 45_678 }, fakeServer(50_000), resolver, nodeA, clock)
        val winner = HostController(registry, LanOpener { 45_679 }, fakeServer(50_001), resolver, nodeB, clock)
        GhostUpload.clear()
        try {
            // The loser opens first and is the live host...
            assertTrue(loser.host(world, generation = 47) is HostResult.Hosting)
            HostSession.onServerStarting()
            HostSession.install(loser)
            GhostUpload.markArmed() // the world is otherwise eligible for a cloud backup

            // ...then a competing opener at the SAME generation wins the CAS (higher nodeId).
            assertTrue(winner.host(world, generation = 47) is HostResult.Hosting)
            assertEquals(nodeB, registry.lookup(world)?.token?.nodeId)

            // The loser notices its lease is gone (heartbeat CAS fails) and self-heals.
            assertFalse(loser.beat(), "the superseded host's heartbeat must report loss")
            HostSession.stopHostingSuperseded()

            // Closing the loser's world must NOT back its losing fork up to the cloud...
            HostSession.onServerStopping(dir, world, 47L) {}
            assertNull(GhostUpload.pending(), "a superseded fork must not re-pollute the cloud")

            // ...and the winner is still the sole owner in discovery (the loser never clobbered it).
            assertEquals(nodeB, registry.lookup(world)?.token?.nodeId)
        } finally {
            GhostUpload.clear()
            HostSession.onServerStarting()
            winner.close()
        }
    }
}
