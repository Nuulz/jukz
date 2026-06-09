package dev.jukz.config

import dev.jukz.JukzMod
import dev.jukz.core.model.NodeId
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The stable per-install [NodeId] (the final tiebreak in a ClaimToken), persisted as 32 hex chars
 * in `config/jukz.nodeid`. Generated once on first use; a corrupt or missing file is regenerated.
 * Stability matters once a real backend is involved: it keeps token comparisons consistent across
 * sessions and makes server-side logs correlatable per install.
 */
object PersistentNodeId {

    private const val FILE_NAME = "jukz.nodeid"

    val nodeId: NodeId by lazy { loadOrCreate() }

    private fun file(): Path = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    private fun loadOrCreate(): NodeId {
        val path = file()
        runCatching {
            if (Files.exists(path)) return NodeId.fromHex(Files.readString(path).trim())
        }.onFailure { e ->
            JukzMod.logger.warn("jukz: invalid node id in {} ({}); regenerating", path, e.message)
        }
        val fresh = NodeId.random()
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, fresh.toHex())
        }.onFailure { e ->
            JukzMod.logger.warn("jukz: could not persist node id to {} ({})", path, e.message)
        }
        return fresh
    }
}
