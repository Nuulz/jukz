package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.DialTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DialTargetsTest {

    private fun record(relay: RelayOffer?) = WorldRecord(
        worldId = WorldId.random(),
        token = ClaimToken(1, 1, NodeId(ByteArray(NodeId.SIZE))),
        endpoints = listOf(Endpoint("10.0.0.2", 1), Endpoint("8.8.8.8", 2)),
        heartbeatSeq = 0,
        relay = relay,
    )

    @Test
    fun `lists direct endpoints first then the relay`() {
        val targets = record(RelayOffer("cap-z")).dialTargets()
        assertEquals(
            listOf(
                DialTarget.Direct(Endpoint("10.0.0.2", 1)),
                DialTarget.Direct(Endpoint("8.8.8.8", 2)),
                DialTarget.ViaRelay("cap-z"),
            ),
            targets,
        )
    }

    @Test
    fun `omits the relay when none is offered`() {
        val targets = record(null).dialTargets()
        assertEquals(2, targets.size)
        assertEquals(DialTarget.Direct(Endpoint("10.0.0.2", 1)), targets.first())
    }
}
