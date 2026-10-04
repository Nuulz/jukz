package dev.jukz.config

import dev.jukz.JukzMod
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.Properties

/**
 * Small things the mod remembers between launches (not settings): `config/jukz-state.properties`.
 * Today only the last version the Ko-fi note was shown for, so it appears once per install and once
 * per update, never on every launch.
 */
object JukzState {
    private const val FILE_NAME = "jukz-state.properties"
    private const val KEY_SUPPORT_SEEN = "support.seen-version"

    private val file get() = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    /** This jar's version, from fabric.mod.json. */
    val modVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("jukz").map { it.metadata.version.friendlyString }.orElse("dev")
    }

    /** True on the first launch and on the first launch after an update. */
    fun supportNoteDue(): Boolean = read().getProperty(KEY_SUPPORT_SEEN) != modVersion

    /** No version recorded at all: a fresh install, rather than an update. */
    fun firstRun(): Boolean = read().getProperty(KEY_SUPPORT_SEEN) == null

    fun markSupportNoteSeen() {
        val props = read()
        props.setProperty(KEY_SUPPORT_SEEN, modVersion)
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { props.store(it, "jukz state (not settings; safe to delete)") }
        }.onFailure { JukzMod.logger.warn("jukz: couldn't save {}: {}", file, it.message) }
    }

    private fun read(): Properties = Properties().also { props ->
        runCatching { if (Files.exists(file)) Files.newBufferedReader(file).use(props::load) }
    }
}
