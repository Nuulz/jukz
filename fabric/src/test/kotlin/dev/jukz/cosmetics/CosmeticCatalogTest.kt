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
        assertTrue(catalog.ofSlot(CosmeticCatalog.Slot.BADGE).all { it.art!!.runs.isNotEmpty() })
        // Every 3D slot ships something, and every model has faces to draw.
        for (slot in listOf(CosmeticCatalog.Slot.HAT, CosmeticCatalog.Slot.FACE, CosmeticCatalog.Slot.BACK)) {
            assertTrue(catalog.ofSlot(slot).isNotEmpty(), "no $slot items")
            assertTrue(catalog.ofSlot(slot).all { it.model!!.quads.isNotEmpty() })
        }
    }

    @Test
    fun `runs merge same-colour pixels and skip transparent ones`() {
        val badge = CosmeticCatalog.parse(catalog(listOf("aab.", "....", "bbbb", "a..a"))).items.single().art!!
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
    fun `voxel models only keep the faces that touch empty space`() {
        // Two touching voxels: 12 faces, minus the 2 they share.
        val model = CosmeticCatalog.parse(hat(listOf(listOf("aa")))).items.single().model!!
        assertEquals(10, model.quads.size)
        // A see-through voxel next to an opaque one keeps the shared face, so the opaque one shows through.
        val glass = CosmeticCatalog.parse(hat(listOf(listOf("ag")))).items.single().model!!
        assertEquals(11, glass.quads.size)
        // Slices stack upwards: layer 1 sits above layer 0 (smaller y — bone space points down).
        val tower = CosmeticCatalog.parse(hat(listOf(listOf("a"), listOf("a")))).items.single().model!!
        val top = tower.quads.first { it.ny == -1f }
        assertEquals(-10f, top.corners[1]) // origin y -8, two 1-px slices
    }

    @Test
    fun `items of an unknown kind are skipped, not fatal`() {
        val text = """
            {"version":1,"defaultBadge":"t","items":[{"id":"x","kind":"shoes"},
             {"id":"t","kind":"badge","name":"T","availability":"free","palette":{"a":"FF112233"},
              "art":["aaaa","aaaa","aaaa","aaaa"]}]}
        """.trimIndent()
        assertEquals(listOf("t"), CosmeticCatalog.parse(text).items.map { it.id })
    }

    @Test
    fun `prices read as money`() {
        assertEquals("$1.99", CosmeticCatalog.Price(199, "USD").label())
        assertEquals("3.00 EUR", CosmeticCatalog.Price(300, "EUR").label())
    }

    private fun hat(layers: List<List<String>>) = """
        {"version":1,"defaultBadge":"t","items":[{"id":"h","kind":"hat","name":"H","availability":"free",
         "palette":{"a":"FF112233","g":"80FFFFFF"},"model":{"voxel":1,"origin":[0,-8,0],
         "layers":[${layers.joinToString(",") { l -> l.joinToString(",", "[", "]") { "\"$it\"" } }}]}}]}
    """.trimIndent()

    private fun catalog(art: List<String>) = """
        {"version":1,"defaultBadge":"t","items":[{"id":"t","kind":"badge","name":"T","availability":"free",
         "palette":{"a":"FF112233","b":"FF445566"},"art":[${art.joinToString(",") { "\"$it\"" }}]}]}
    """.trimIndent()
}
