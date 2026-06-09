package dev.jukz.discovery

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.util.SystemClock

/**
 * The single discovery backend for the whole client: the world-open interceptor's lookups and the
 * host's publishes/heartbeats share it, so they agree on who is hosting what. Loopback-safe
 * [InMemoryWorldRegistry] today (no remote readers, so lookups come back empty and every world opens
 * locally); the live DHT [MldhtWorldRegistry] swaps in here behind [WorldRegistry] for real
 * cross-machine discovery, with no change at the call sites.
 */
object Discovery {
    val registry: WorldRegistry = InMemoryWorldRegistry(SystemClock)
}
