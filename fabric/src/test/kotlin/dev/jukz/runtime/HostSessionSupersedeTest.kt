package dev.jukz.runtime

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.host.ConnectionServer
import dev.jukz.core.host.HostController
import dev.jukz.core.host.LanOpener
import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
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
}
