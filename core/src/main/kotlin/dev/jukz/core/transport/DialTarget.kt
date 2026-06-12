package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint

/**
 * How a guest reaches a host for one channel. [Direct] dials a reachable endpoint over TCP;
 * [ViaRelay] connects through the rendezvous relay to the host's registered session. The guest's
 * connect ladder ([dev.jukz.core.discovery.WorldRecord.dialTargets]) lists every direct target
 * first, then the relay, so the relay is only used when no direct endpoint connects.
 */
sealed interface DialTarget {
    data class Direct(val endpoint: Endpoint) : DialTarget
    data class ViaRelay(val sessionId: String) : DialTarget
}
