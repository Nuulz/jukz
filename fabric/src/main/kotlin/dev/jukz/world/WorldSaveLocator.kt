package dev.jukz.world

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Finds the local save folder for a jukz world id by scanning `saves/` and reading each folder's
 * `jukz.dat` sidecar. Used by the handoff (F4-B): when a guest must take over hosting, it needs the
 * folder name of its local copy of that world (to pull the host's snapshot into and then open).
 * Minecraft-free so it is unit-tested directly.
 */
object WorldSaveLocator {

    /** The save folder name whose `jukz.dat` carries [worldId], scanning [savesDir], or null if none. */
    fun findLevelName(savesDir: Path, worldId: UUID): String? = runCatching {
        if (!Files.isDirectory(savesDir)) return null
        Files.list(savesDir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { runCatching { WorldIdSidecar.read(it)?.worldId == worldId }.getOrDefault(false) }
                .map { it.fileName.toString() }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()
}
