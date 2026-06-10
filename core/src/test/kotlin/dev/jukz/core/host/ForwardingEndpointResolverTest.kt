package dev.jukz.core.host

import dev.jukz.core.model.Endpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The correctness-critical invariant of NAT port-forwarding on the host path: it is best-effort and
 * must NEVER fail the host. These tests pin that down with fakes, so the real UPnP adapter in fabric
 * can stay flagged/untested without putting hosting at risk.
 */
class ForwardingEndpointResolverTest {

    private val lan = EndpointResolver { port -> Endpoint("192.168.1.50", port) }

    @Test
    fun `opens the port then returns the delegate address`() {
        val opened = AtomicInteger(-1)
        val forwarder = PortForwarder { port -> opened.set(port); "203.0.113.9:$port" }
        val resolver = ForwardingEndpointResolver(forwarder, lan)

        val endpoint = resolver.resolve(50_000)

        assertEquals(50_000, opened.get())                       // the forwarder was asked to open it
        assertEquals(Endpoint("192.168.1.50", 50_000), endpoint) // but the announced address is the LAN one
    }

    @Test
    fun `still returns the delegate address when forwarding throws`() {
        val forwarder = PortForwarder { error("no UPnP gateway") }
        val resolver = ForwardingEndpointResolver(forwarder, lan)

        // Must not propagate: hosting cannot fail just because the router has no UPnP.
        val endpoint = resolver.resolve(50_000)

        assertEquals(Endpoint("192.168.1.50", 50_000), endpoint)
    }

    @Test
    fun `still returns the delegate address when no gateway is available`() {
        var called = false
        val forwarder = PortForwarder { called = true; null } // null = nothing mapped
        val resolver = ForwardingEndpointResolver(forwarder, lan)

        val endpoint = resolver.resolve(50_000)

        assertTrue(called)
        assertEquals(Endpoint("192.168.1.50", 50_000), endpoint)
    }
}
