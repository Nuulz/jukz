package dev.jukz.config

import dev.jukz.JukzMod
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.Properties

/**
 * Small things the mod remembers between launches (not settings): `config/jukz-state.properties`.
 * Today the last jukz version that ran, so a fresh install gets the welcome / Ko-fi note once and an
 * update gets the "what's new" screen once — never on every launch.
 */
object JukzState {
    private const val FILE_NAME = "jukz-state.properties"
    private const val KEY_LAST_VERSION = "last-version"
    private const val KEY_LEGACY = "support.seen-version" // 0.2.0 dev builds wrote this

    private val configDir get() = FabricLoader.getInstance().configDir
    private val file get() = configDir.resolve(FILE_NAME)

    /** This jar's version, from fabric.mod.json. */
    val modVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("jukz").map { it.metadata.version.friendlyString }.orElse("dev")
    }

    /**
     * Whether jukz ran here before this launch, captured at mod init — before anything creates its
     * config files — so players coming from 0.1.0 (which kept no state file) count as updating.
     */
    private var installedBefore = false

    fun captureStartup() {
        // Only files jukz writes while playing: jukz.properties can be pre-made by launchers and scripts.
        installedBefore = listOf(FILE_NAME, "jukz.nodeid", "jukz-keys").any { Files.exists(configDir.resolve(it)) }
    }

    /** The version that ran last, or null (fresh install, or a pre-0.2 install that kept no state). */
    fun lastVersion(): String? = read().let { it.getProperty(KEY_LAST_VERSION) ?: it.getProperty(KEY_LEGACY) }

    /** True on the first launch of a new install, or of a new version. */
    fun versionChanged(): Boolean = lastVersion() != modVersion

    /** A brand-new install (rather than an update). */
    fun firstRun(): Boolean = lastVersion() == null && !installedBefore

    fun markVersionSeen() {
        val props = read()
        props.setProperty(KEY_LAST_VERSION, modVersion)
        props.remove(KEY_LEGACY)
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { props.store(it, "jukz state (not settings; safe to delete)") }
        }.onFailure { JukzMod.logger.warn("jukz: couldn't save {}: {}", file, it.message) }
    }

    private fun read(): Properties = Properties().also { props ->
        runCatching { if (Files.exists(file)) Files.newBufferedReader(file).use(props::load) }
    }
}
