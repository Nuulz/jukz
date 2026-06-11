package dev.jukz.transport

import dev.jukz.core.host.PortForwarder

/**
 * Wraps a [PortForwarder] and remembers whether its last [open] actually mapped a port, so the host
 * wiring can decide — after the resolver has run — whether to register a relay fallback. UPnP that
 * succeeded means internet guests reach the port directly; only when it returned null (no IGD /
 * CGNAT) does the host need the relay.
 */
class RecordingPortForwarder(private val delegate: PortForwarder) : PortForwarder {
    @Volatile
    var lastMapping: String? = null
        private set

    override fun open(port: Int): String? = delegate.open(port).also { lastMapping = it }

    /** True when the most recent [open] opened nothing — the host should register a relay session. */
    fun upnpFailed(): Boolean = lastMapping == null
}
