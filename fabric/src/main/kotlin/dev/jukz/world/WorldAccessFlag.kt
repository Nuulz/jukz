package dev.jukz.world

import dev.jukz.JukzMod
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Per-world access flag, stored as `jukz.access=disabled` in `<save>/jukz.properties` (the same dir
 * as `jukz.dat`). When present, opening the world skips the automatic announce, so a host can close a
 * shared world and have it stay private across re-opens until access is re-enabled (F4-D). Plain
 * properties so a self-hoster can edit it by hand; Minecraft-free so it is unit-tested directly.
 */
object WorldAccessFlag {
    private const val FILE = "jukz.properties"
    private const val KEY = "jukz.access"
    private const val DISABLED = "disabled"

    /** True when the world is flagged closed (announce should be skipped). Never throws. */
    fun isDisabled(saveDir: Path): Boolean = runCatching {
        val file = saveDir.resolve(FILE)
        if (!Files.exists(file)) return false
        loadProps(file).getProperty(KEY)?.trim().equals(DISABLED, ignoreCase = true)
    }.getOrDefault(false)

    /** Mark the world closed: write `jukz.access=disabled`. */
    fun disable(saveDir: Path) {
        runCatching {
            Files.createDirectories(saveDir)
            val file = saveDir.resolve(FILE)
            val props = if (Files.exists(file)) loadProps(file) else Properties()
            props.setProperty(KEY, DISABLED)
            store(file, props)
        }.onFailure { JukzMod.logger.warn("jukz: could not write access flag ({})", it.message) }
    }

    /** Re-open the world: drop the flag (and the file if it becomes empty). */
    fun enable(saveDir: Path) {
        runCatching {
            val file = saveDir.resolve(FILE)
            if (!Files.exists(file)) return
            val props = loadProps(file)
            props.remove(KEY)
            if (props.isEmpty) Files.deleteIfExists(file) else store(file, props)
        }.onFailure { JukzMod.logger.warn("jukz: could not clear access flag ({})", it.message) }
    }

    private fun loadProps(file: Path): Properties =
        Properties().apply { Files.newBufferedReader(file).use { load(it) } }

    private fun store(file: Path, props: Properties) =
        Files.newBufferedWriter(file).use { props.store(it, "jukz per-world settings") }
}
