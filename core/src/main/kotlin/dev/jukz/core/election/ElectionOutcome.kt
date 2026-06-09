package dev.jukz.core.election

import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.Endpoint

/** Result of running [HostElection.elect]. */
sealed interface ElectionOutcome {
    /** This node won the claim and should host. The caller persists [record].token.hostGeneration. */
    data class BecameHost(val record: WorldRecord) : ElectionOutcome

    /** A live host won; the player joins it as a guest by connecting to [redirect]. */
    data class BecameGuest(val redirect: Endpoint, val host: WorldRecord) : ElectionOutcome
}

/** Thrown when election cannot converge within [ElectionConfig.maxDuelRounds]. */
class ElectionException(message: String) : Exception(message)
