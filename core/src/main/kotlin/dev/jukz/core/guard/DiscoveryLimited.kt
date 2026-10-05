package dev.jukz.core.guard

/**
 * The rendezvous refused a call for opening or joining too many worlds in a short time (the anti-abuse
 * limit). [retryAfterSecs] is how long until it lets this device through again.
 */
class DiscoveryLimited(val retryAfterSecs: Long) : RuntimeException("too many worlds opened or joined; try again in ${retryAfterSecs}s")
