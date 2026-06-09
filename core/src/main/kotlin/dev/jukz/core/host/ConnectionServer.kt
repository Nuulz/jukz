package dev.jukz.core.host

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId

/**
 * Serves inbound jukz connections for a hosted world: CONTROL channels run the handshake + fencing +
 * liveness, DATA channels pipe to the local game. A seam so [HostController] can be unit-tested
 * without binding real sockets; the real implementation is [HostConnectionServer].
 */
interface ConnectionServer : AutoCloseable {
    /**
     * Begin serving connections for [worldId] under [token], piping DATA channels to [gameEndpoint]
     * (the locally-opened Minecraft server) and answering Pings with the latest [heartbeatSeq].
     * Returns the local port the server is listening on — this is what gets announced (after NAT
     * mapping) so guests reach the game through here.
     */
    fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long): Int
}
