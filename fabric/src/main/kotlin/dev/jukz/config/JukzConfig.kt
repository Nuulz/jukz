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
 * By default the mod connects to the public rendezvous server at [DEFAULT_RENDEZVOUS_URL] so
 * internet-wide discovery works out of the box. Override `rendezvous.url` in the config file to
 * point at a self-hosted instance, or set it to `none` to run LAN-only.
 */
object JukzConfig {

    private const val FILE_NAME = "jukz.properties"
    private const val KEY_RENDEZVOUS_URL = "rendezvous.url"
    private const val KEY_RENDEZVOUS_AUTH_TOKEN = "rendezvous.auth-token"

    /** Public rendezvous server used when no override is configured. */
    const val DEFAULT_RENDEZVOUS_URL = "https://jukz-rendezvous-production.up.railway.app"

    private val TEMPLATE = """
        # jukz configuration
        #
        # rendezvous.url
        #   Base URL of the jukz rendezvous server used for internet-wide world discovery.
        #   Defaults to the public instance when this is blank.
        #   Set to "none" to disable internet discovery and stay LAN-only.
        #   Self-hosting: see rendezvous/README.md in the jukz repository.
        #
        # rendezvous.auth-token
        #   Optional bearer token, only needed when the configured server requires one.
        #
        rendezvous.url=
        rendezvous.auth-token=
    """.trimIndent() + "\n"

    private val properties: Properties by lazy { load() }

    /**
     * Normalised rendezvous base URL (no trailing slash).
     * Falls back to [DEFAULT_RENDEZVOUS_URL] when unconfigured.
     * Returns null only when explicitly set to "none" → LAN-only.
     */
    val rendezvousUrl: String?
        get() {
            val raw = properties.getProperty(KEY_RENDEZVOUS_URL)?.trim()?.trimEnd('/') ?: ""
            return when {
                raw.equals("none", ignoreCase = true) -> null
                raw.isEmpty() -> DEFAULT_RENDEZVOUS_URL
                else -> raw
            }
        }

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
