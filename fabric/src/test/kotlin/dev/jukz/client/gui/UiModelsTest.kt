package dev.jukz.client.gui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The owo-ui models are only parsed in-game, so a typo would surface as a broken screen at runtime. This
 * checks them in plain JUnit: every model is well-formed, every `name@jukz:theme` template it uses exists,
 * and the ids the Kotlin screens look up are declared (directly or via a template's `<id>` parameter).
 */
class UiModelsTest {

    private val dir = File("src/main/resources/assets/jukz/owo_ui")
    private fun parse(name: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "$name.xml")).documentElement

    private fun Element.all(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Ids a model declares: `id` attributes plus `<id>` params passed to templates. */
    private fun ids(model: Element): Set<String> =
        (model.all("*").mapNotNull { it.getAttribute("id").takeIf(String::isNotEmpty) } +
            model.all("id").map { it.textContent.trim() }).toSet()

    @Test
    fun `every model parses and only uses theme templates that exist`() {
        val theme = parse("theme").all("template").map { it.getAttribute("name") }.toSet()
        val models = dir.listFiles { f -> f.extension == "xml" }!!.map { it.nameWithoutExtension }
        assertTrue("theme" in models)
        for (name in models) {
            for (use in parse(name).all("template").map { it.getAttribute("name") }.filter { "@" in it }) {
                val (template, model) = use.split("@")
                assertTrue(model == "jukz:theme" && template in theme, "$name.xml uses unknown template $use")
            }
        }
    }

    @Test
    fun `models declare the ids their screens look up`() {
        val expected = mapOf(
            "status" to setOf("title", "message", "bar", "buttons"),
            "host_info" to setOf("title", "rows", "access-button", "buttons"),
            "join_prompt" to setOf("title", "message", "code", "error", "buttons"),
            "upload" to setOf("title", "message", "bar", "tip", "buttons"),
            "cosmetics" to setOf("title", "account", "preview", "grid", "hint", "buttons"),
        )
        for ((name, wanted) in expected) {
            val missing = wanted - ids(parse(name))
            assertTrue(missing.isEmpty(), "$name.xml is missing ids $missing")
        }
        // The frame and shared bits every screen relies on.
        val themeIds = ids(parse("theme"))
        assertTrue(setOf("panel", "brand").all { it in themeIds }, "theme.xml must declare panel and brand")
    }
}
