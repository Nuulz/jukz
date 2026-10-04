package dev.jukz.cosmetics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CosmeticCatalogTest {

    @Test
    fun `the bundled catalog parses and its default badge is free`() {
        val catalog = CosmeticCatalog.bundled()
        val default = catalog.item(catalog.defaultBadge)
        assertNotNull(default)
        assertEquals(CosmeticCatalog.Availability.FREE, default!!.availability)
        assertTrue(catalog.items.all { it.runs.isNotEmpty() })
    }

    @Test
    fun `runs merge same-colour pixels and skip transparent ones`() {
        val badge = CosmeticCatalog.parse(catalog(listOf("aab.", "....", "bbbb", "a..a"))).items.single()
        assertEquals(4, badge.size)
        val spans = badge.runs.map { Triple(it.x, it.y, it.length) }
        assertEquals(listOf(Triple(0, 0, 2), Triple(2, 0, 1), Triple(0, 2, 4), Triple(0, 3, 1), Triple(3, 3, 1)), spans)
        assertEquals(0xFF112233.toInt(), badge.runs.first().argb)
    }

    @Test
    fun `ragged art and unknown colours are rejected`() {
        assertThrows<IllegalArgumentException> { CosmeticCatalog.parse(catalog(listOf("aaaa", "aa", "aaaa", "aaaa"))) }
        assertThrows<IllegalStateException> { CosmeticCatalog.parse(catalog(listOf("aaaa", "aqaa", "aaaa", "aaaa"))) }
    }

    @Test
    fun `prices read as money`() {
        assertEquals("$1.99", CosmeticCatalog.Price(199, "USD").label())
        assertEquals("3.00 EUR", CosmeticCatalog.Price(300, "EUR").label())
    }

    private fun catalog(art: List<String>) = """
        {"version":1,"defaultBadge":"t","items":[{"id":"t","kind":"badge","name":"T","availability":"free",
         "palette":{"a":"FF112233","b":"FF445566"},"art":[${art.joinToString(",") { "\"$it\"" }}]}]}
    """.trimIndent()
}
