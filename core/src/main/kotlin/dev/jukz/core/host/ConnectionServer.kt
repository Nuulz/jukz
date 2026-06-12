package dev.jukz.core.host

import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import java.util.concurrent.CountDownLatch

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

    /** Number of guests with an open control channel right now (a reliable "is anyone connected"). */
    fun connectedGuestCount(): Int = 0

    /**
     * Push a [Message.HostLeaving] to every connected guest over its live control channel, so they can
     * take over hosting (F4 handoff) without racing discovery. [snapshot], when present, tells them
     * where to pull the latest save from. Best-effort.
     */
    fun notifyGuestsLeaving(snapshot: SnapshotOffer?) {}

    /**
     * Arm this server to answer [ConnectionType.SNAPSHOT] channels with [pack] (the JGit pack of the
     * world save), gated by [token]; [head] is the commit id the guest resets to. Serving the snapshot
     * over the same listen port the game uses means it rides the one NAT traversal that already works
     * (no second port to forward). Returns a latch that counts down each time a guest finishes a
     * download, or null for test doubles that cannot serve. The arming lasts until [close].
     */
    fun armSnapshot(pack: ByteArray, head: String, token: String): CountDownLatch? = null
}
