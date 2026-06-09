package dev.jukz.discovery

import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.util.SystemClock

/**
 * The single discovery backend for the whole client: the world-open interceptor's lookups and the
 * host's publishes/heartbeats share it, so they agree on who is hosting what.
 *
 * Composition (per the WorldSync decisions):
 *  - LAN layer: real cross-machine [LanMulticastWorldRegistry]; falls back to the loopback-safe
 *    [InMemoryWorldRegistry] if multicast can't be set up (no network, blocked).
 *  - Internet layer: [RendezvousWorldRegistry], only when `rendezvous.url` is configured in
 *    `config/jukz.properties`. Both are combined by [CompositeWorldRegistry] (LAN first, token
 *    order resolves discrepancies). With no URL configured the mod is LAN-only.
 */
object Discovery {
    val registry: WorldRegistry = build()

    private fun build(): WorldRegistry {
        val lan = runCatching { LanMulticastWorldRegistry() as WorldRegistry }
            .getOrElse { e ->
                JukzMod.logger.warn("jukz: LAN multicast discovery unavailable ({}); using in-memory", e.message)
                InMemoryWorldRegistry(SystemClock)
            }
        val url = JukzConfig.rendezvousUrl ?: run {
            JukzMod.logger.info("jukz: no rendezvous.url configured; discovery is LAN-only")
            return lan
        }
        JukzMod.logger.info("jukz: rendezvous discovery enabled via {}", url)
        return CompositeWorldRegistry(lan, RendezvousWorldRegistry(url, JukzConfig.rendezvousAuthToken))
    }
}
