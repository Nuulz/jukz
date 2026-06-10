package dev.jukz.core.host

/**
 * Best-effort request to make a locally-bound [port] reachable from outside the NAT (typically a
 * UPnP port mapping on the home router). This is the optional companion to [EndpointResolver]: the
 * resolver decides *what address* to announce, the forwarder tries to *open the path* to it.
 *
 * Implementations are best-effort and MUST NOT be relied upon. [ForwardingEndpointResolver] runs
 * them on the host path and swallows any failure, so a missing or disabled gateway never blocks
 * hosting. The returned note (the mapped public address, or null when nothing was opened) is purely
 * informational — for logging — not a contract.
 */
fun interface PortForwarder {
    fun open(port: Int): String?
}
