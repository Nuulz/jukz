package dev.jukz.config

import dev.jukz.JukzMod
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The mod's user configuration, a plain `config/jukz.properties` so self-hosters can edit it by
 * hand. Loaded once on first access; a missing file is created from a commented template so the
 * available keys are discoverable without docs.
 *
 * `rendezvous.url` is deliberately empty by default: with no URL the mod is LAN-only (multicast
 * discovery keeps working) and never talks to any external service. Pointing it at a rendezvous
 * server — self-hosted or public — turns on internet-wide discovery.
 */
object JukzConfig {

    private const val FILE_NAME = "jukz.properties"
    private const val KEY_RENDEZVOUS_URL = "rendezvous.url"
    private const val KEY_RENDEZVOUS_AUTH_TOKEN = "rendezvous.auth-token"

    private val TEMPLATE = """
        # jukz configuration
        #
        # rendezvous.url
        #   Base URL of a jukz rendezvous server (e.g. https://my-jukz.fly.dev) used for
        #   internet-wide world discovery. Leave empty to stay LAN-only: worlds are then only
        #   discoverable on your local network via multicast.
        #   Self-hosting: see rendezvous/README.md in the jukz repository.
        #
        # rendezvous.auth-token
        #   Optional bearer token, only needed when the configured server requires one.
        #
        rendezvous.url=
        rendezvous.auth-token=
    """.trimIndent() + "\n"

    private val properties: Properties by lazy { load() }

    /** Normalised rendezvous base URL (no trailing slash), or null when unset → LAN-only. */
    val rendezvousUrl: String?
        get() = properties.getProperty(KEY_RENDEZVOUS_URL)
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /** Optional bearer token for the rendezvous server, or null when unset. */
    val rendezvousAuthToken: String?
        get() = properties.getProperty(KEY_RENDEZVOUS_AUTH_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }

    internal fun configFile(): Path = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    private fun load(): Properties {
        val props = Properties()
        val file = configFile()
        runCatching {
            if (!Files.exists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, TEMPLATE)
                JukzMod.logger.info("jukz: wrote default config to {}", file)
            }
            Files.newBufferedReader(file).use { props.load(it) }
        }.onFailure { e ->
            JukzMod.logger.warn("jukz: could not read {} ({}); using defaults (LAN-only)", file, e.message)
        }
        return props
    }
}
