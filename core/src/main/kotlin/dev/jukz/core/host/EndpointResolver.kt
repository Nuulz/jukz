package dev.jukz.core.host

import dev.jukz.core.model.Endpoint

/**
 * Resolves the [Endpoint] a guest would dial to reach a locally-bound [port]. This is the NAT
 * frontier of the host flow: the real-today adapter returns the host's LAN address (works on a
 * local network), while the flagged adapter performs STUN/UPnP to obtain a public, cross-NAT
 * endpoint. Keeping it behind this seam lets cross-country reachability drop in without touching
 * [HostController].
 *
 * Invoked off the render thread (the host flow runs on a background thread), so a blocking
 * implementation is acceptable.
 */
fun interface EndpointResolver {
    fun resolve(port: Int): Endpoint
}
