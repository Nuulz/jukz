package dev.jukz.net

import dev.jukz.core.model.Endpoint

/**
 * UDP hole punching coordinator (FLAGGED — requires live-network testing across real NATs).
 *
 * Real flow:
 *  1. Both peers learn their server-reflexive candidates via [StunClient].
 *  2. They exchange candidates through the rendezvous/signaling channel.
 *  3. Both simultaneously send UDP probes to each other's candidate; once a probe round-trips,
 *     the path is open. Succeeds for cone NATs (~92-96% of pairs), fails on symmetric/CGNAT.
 *  4. On failure, the caller falls back to a TURN relay (see the fallback ladder in the spec).
 */
class HolePuncher {
    /** Attempt to open a bidirectional UDP path to [peer]; returns the chosen local UDP port. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun punch(peer: Endpoint, localCandidate: Endpoint): Int =
        throw NotImplementedError("UDP hole punching requires live-network testing across real NATs")
}
