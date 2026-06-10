package dev.jukz.core.discovery

/**
 * The host's offer of a one-shot world snapshot over HTTP, published in the [WorldRecord] so a guest
 * taking over hosting (F4 handoff) can pull the latest save before becoming host. The guest dials
 * `http://[host]:[port]/snapshot?token=[token]`; the token gates the download (the host rejects any
 * other token with 403).
 *
 * Design choice: this is a dedicated field on the record rather than another
 * [dev.jukz.core.model.Endpoint] in the candidate list, because those are dialed as *game* endpoints
 * (the join controller opens a jukz handshake against each one) — a snapshot URL is not dialable that
 * way. It rides the LAN-multicast binary record; the rendezvous server only relays `{host, port}`
 * endpoints, so a cross-internet handoff simply finds no offer and falls back to the guest's local
 * copy (the documented live-host trade-off).
 */
data class SnapshotOffer(val host: String, val port: Int, val token: String) {
    init {
        require(host.isNotBlank()) { "snapshot host must not be blank" }
        require(port in 1..65535) { "snapshot port out of range: $port" }
        require(token.isNotBlank()) { "snapshot token must not be blank" }
    }

    /** The full URL a guest GETs to fetch the JGit pack. */
    fun url(): String = "http://$host:$port/snapshot?token=$token"
}
