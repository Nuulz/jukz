package dev.jukz.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ChangelogTest {
    private val text = """
        # Changelog

        Intro text that isn't a section.

        ## 0.3.0

        - Third thing,
          wrapped onto two lines.
        - Another.

        ## [0.2.1] - 2026-10-10

        - A fix.

        ## 0.2.0

        - Cosmetics.
    """.trimIndent()

    private val sections = Changelog.parse(text)

    @Test
    fun `sections and bullets, with wrapped bullets joined`() {
        assertEquals(listOf("0.3.0", "0.2.1", "0.2.0"), sections.map { it.version })
        assertEquals(listOf("Third thing, wrapped onto two lines.", "Another."), sections[0].bullets)
    }

    @Test
    fun `an update shows every version since the last one played`() {
        assertEquals(listOf("0.3.0", "0.2.1"), Changelog.between(sections, "0.2.0", "0.3.0").map { it.version })
        assertEquals(listOf("0.2.1"), Changelog.between(sections, "0.2.0", "0.2.1").map { it.version })
        // Unknown previous version (a pre-0.2 install): just the current one.
        assertEquals(listOf("0.3.0"), Changelog.between(sections, null, "0.3.0").map { it.version })
        // A dev build of a version counts as that version.
        assertEquals(listOf("0.3.0"), Changelog.between(sections, "0.2.1", "0.3.0-dev.12").map { it.version })
        assertEquals(emptyList<String>(), Changelog.between(sections, "0.3.0", "0.3.0").map { it.version })
    }

    @Test
    fun `the real changelog has a section for the version in gradle properties`() {
        val version = File("../gradle.properties").readLines().first { it.startsWith("mod_version=") }.substringAfter('=')
        val real = Changelog.parse(File("../CHANGELOG.md").readText())
        assertTrue(real.any { it.version == version && it.bullets.isNotEmpty() }, "CHANGELOG.md needs a ## $version section")
    }
}
