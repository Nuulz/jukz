package dev.jukz.sync

import dev.jukz.core.model.WorldId
import dev.jukz.core.model.WorldKey
import dev.jukz.world.WorldIdSidecar
import dev.jukz.world.WorldKeyStore
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** The world key rides the snapshot, so whoever receives the world can sign for it. */
class WorldKeySnapshotTest {

    @Test
    fun `the key travels in the snapshot and can be read from it without applying it`() {
        val worldId = WorldId.random()
        val dir = Files.createTempDirectory("jukz-key")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(worldId.uuid, 3))
        Files.write(dir.resolve("level.dat"), byteArrayOf(1, 2, 3))
        val key = WorldKeyStore.loadOrCreate(dir, worldId) ?: error("key")

        val pack = SnapshotPack.build(dir, JGitWorldSync()) ?: error("pack")
        val packFile = Files.createTempFile("jukz-key", ".pack").also { Files.write(it, pack.bytes) }
        val downloaded = JGitWorldSync.Downloaded(packFile, ObjectId.fromString(pack.head))

        val fromPack = JGitWorldSync().readFileAtHead(downloaded, WorldKeyStore.FILE_NAME)
        assertNotNull(fromPack)
        assertArrayEquals(key.publicKey, WorldKey.decode(String(fromPack!!, Charsets.UTF_8)).publicKey)
        assertNull(JGitWorldSync().readFileAtHead(downloaded, "missing.file"))
    }

    @Test
    fun `an existing key is kept, not regenerated`() {
        val worldId = WorldId.random()
        val dir = Files.createTempDirectory("jukz-key2")
        val first = WorldKeyStore.loadOrCreate(dir, worldId)!!
        val again = WorldKeyStore.loadOrCreate(dir, worldId)!!
        assertArrayEquals(first.publicKey, again.publicKey)
    }
}
