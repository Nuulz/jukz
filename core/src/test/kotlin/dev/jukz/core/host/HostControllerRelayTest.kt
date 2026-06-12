package dev.jukz.core.host

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class HostControllerRelayTest {

    private val listenPort = 50_000
    private fun fakeServer() = object : ConnectionServer {
        override fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long) = listenPort
        override fun close() {}
    }

    private fun controller(registry: InMemoryWorldRegistry, registrar: RelayRegistrar) = HostController(
        registry = registry,
        lanOpener = LanOpener { 40_000 },
        connectionServer = fakeServer(),
        endpointResolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) },
        nodeId = NodeId(ByteArray(16) { 9 }),
        clock = SystemClock,
        relayRegistrar = registrar,
    )

    @Test
    fun `attaches the relay offer the registrar returns`() = runBlocking {
        val registry = InMemoryWorldRegistry(SystemClock)
        val world = WorldId.random()
        val seen = AtomicInteger(0)
        val registrar = RelayRegistrar { port -> seen.set(port); RelayOffer("cap-host") }
        controller(registry, registrar).host(world, generation = 1)
        val published = registry.lookup(world)!!
        assertEquals(RelayOffer("cap-host"), published.relay)
        assertEquals(50_000, seen.get()) // registrar saw the connection-server listen port
    }

    @Test
    fun `leaves relay null when the registrar declines`() = runBlocking {
        val registry = InMemoryWorldRegistry(SystemClock)
        val world = WorldId.random()
        controller(registry, RelayRegistrar { null }).host(world, generation = 1)
        assertNull(registry.lookup(world)!!.relay)
    }
}
