package dev.jukz.net

/**
 * Opportunistic UPnP / NAT-PMP port mapping (FLAGGED — requires a real router + live testing).
 *
 * The first rung of the traversal ladder: many home routers support UPnP IGD, so mapping the
 * host's TCP port gives a directly-reachable endpoint with no hole punching at all. ice4j bundles
 * weupnp; the live build delegates here. On failure the caller proceeds to ICE/hole-punch.
 */
class UpnpMapper {
    /** Try to map [internalPort] to an external port; returns the external port, or null if unavailable. */
    @Suppress("UNUSED_PARAMETER")
    fun tryMap(internalPort: Int): Int? =
        throw NotImplementedError("UPnP mapping requires a real router and live testing")
}
