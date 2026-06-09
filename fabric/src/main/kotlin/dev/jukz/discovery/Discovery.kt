package dev.jukz.discovery

import dev.jukz.JukzMod
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.util.SystemClock

/**
 * The single discovery backend for the whole client: the world-open interceptor's lookups and the
 * host's publishes/heartbeats share it, so they agree on who is hosting what. Defaults to real
 * cross-machine [LanMulticastWorldRegistry] (two instances on the same network find each other); if
 * multicast can't be set up (no network, blocked), it falls back to the loopback-safe
 * [InMemoryWorldRegistry]. The internet-scale DHT ([MldhtWorldRegistry]) slots in here behind the
 * same [WorldRegistry] interface.
 */
object Discovery {
    val registry: WorldRegistry = runCatching { LanMulticastWorldRegistry() as WorldRegistry }
        .getOrElse { e ->
            JukzMod.logger.warn("jukz: LAN multicast discovery unavailable ({}); using in-memory", e.message)
            InMemoryWorldRegistry(SystemClock)
        }
}
