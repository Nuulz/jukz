package dev.jukz.transport

import dev.jukz.core.model.Endpoint
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.Transport

/**
 * NAT-traversing transport (FLAGGED — requires ice4j + netty-quic and live-network testing).
 *
 * Fallback ladder (spec §4.3): UPnP direct -> ICE/STUN UDP hole punch -> QUIC reliable stream over
 * the punched path -> TURN relay -> community tunnel. Carries Minecraft's TCP bytes opaquely; the
 * resulting [JukzChannel] is fed to a [dev.jukz.core.transport.LocalTcpRelay] on the guest so the
 * vanilla client connects to 127.0.0.1.
 *
 * Note: ice4j and netty-codec-native-quic MUST be shaded/relocated to avoid clashing with the
 * Netty that Minecraft already ships.
 */
class IceTransport : Transport {
    override suspend fun connect(endpoint: Endpoint): JukzChannel =
        throw NotImplementedError("IceTransport requires ice4j + netty-quic and live-network testing")
}
