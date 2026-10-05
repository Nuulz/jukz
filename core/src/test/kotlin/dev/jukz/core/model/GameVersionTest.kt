package dev.jukz.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameVersionTest {
    private val v1211 = GameVersion("1.21.1", 3955)
    private val v12111 = GameVersion("1.21.11", 4671)

    @Test
    fun `same data version fits`() = assertEquals(VersionFit.SAME, VersionFit.of(v12111, GameVersion("1.21.11", 4671)))

    @Test
    fun `an older world can be upgraded`() = assertEquals(VersionFit.UPGRADE, VersionFit.of(v12111, v1211))

    @Test
    fun `a newer world can't be opened`() = assertEquals(VersionFit.TOO_NEW, VersionFit.of(v1211, v12111))

    @Test
    fun `no version means a 1,21,1 world`() {
        assertEquals(VersionFit.SAME, VersionFit.of(v1211, null))
        assertEquals(VersionFit.UPGRADE, VersionFit.of(v12111, null))
    }

    @Test
    fun `rejects nonsense`() {
        assertThrows<IllegalArgumentException> { GameVersion("", 1) }
        assertThrows<IllegalArgumentException> { GameVersion("1.21.1", 0) }
    }
}
