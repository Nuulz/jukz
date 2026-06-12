package dev.jukz.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Properties

class WorldAccessFlagTest {

    @Test
    fun `disable writes the flag to disk and isDisabled reads it back`() {
        val dir = Files.createTempDirectory("jukz-access")
        assertFalse(WorldAccessFlag.isDisabled(dir))

        WorldAccessFlag.disable(dir)

        assertTrue(WorldAccessFlag.isDisabled(dir))
        assertTrue(Files.exists(dir.resolve("jukz.properties")))
    }

    @Test
    fun `enable deletes the flag`() {
        val dir = Files.createTempDirectory("jukz-access2")
        WorldAccessFlag.disable(dir)
        assertTrue(WorldAccessFlag.isDisabled(dir))

        WorldAccessFlag.enable(dir)

        assertFalse(WorldAccessFlag.isDisabled(dir))
    }

    @Test
    fun `enable keeps unrelated keys and only drops the access flag`() {
        val dir = Files.createTempDirectory("jukz-access3")
        WorldAccessFlag.disable(dir)
        val file = dir.resolve("jukz.properties")
        Properties().apply {
            Files.newBufferedReader(file).use { load(it) }
            setProperty("other.key", "keepme")
            Files.newBufferedWriter(file).use { store(it, null) }
        }

        WorldAccessFlag.enable(dir)

        assertFalse(WorldAccessFlag.isDisabled(dir))
        assertTrue(Files.exists(file)) // kept because another key remains
        val reloaded = Properties().apply { Files.newBufferedReader(file).use { load(it) } }
        assertEquals("keepme", reloaded.getProperty("other.key"))
    }
}
