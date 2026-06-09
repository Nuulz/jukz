package dev.jukz.core.host

import dev.jukz.core.model.Endpoint

/**
 * A live snapshot of the host's own discovery record, for the host UI's "is everything OK?" check.
 * [live] is true only when the registry still holds our record under our exact token (we are the
 * announced host); [heartbeatSeq] is the latest re-announce sequence; [endpoint] is what guests dial.
 */
data class HostStatus(
    val live: Boolean,
    val heartbeatSeq: Long,
    val endpoint: Endpoint,
)
