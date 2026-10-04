package dev.jukz.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The cosmetics catalog: `cosmetics/catalog.json` at the repository root, the same file the rendezvous
 * Worker serves at `/v1/cosmetics/catalog` (Gradle copies it into the jar as the offline fallback, and
 * the live copy replaces it once fetched, so new items need no mod update).
 *
 * Each badge is ASCII art: one string per row, one character per pixel, keyed to an AARRGGBB palette
 * ("." is transparent). [Badge.runs] pre-merges each row into same-colour spans for drawing.
 */
class CosmeticCatalog(val version: Int, val defaultBadge: String, val items: List<Badge>) {

    fun item(id: String?): Badge? = id?.let { wanted -> items.firstOrNull { it.id == wanted } }

    enum class Availability { FREE, PAID, GRANT }

    class Price(val amount: Int, val currency: String) {
        /** "$1.99"-style label; unknown currencies fall back to "1.99 EUR". */
        fun label(): String {
            val value = "%d.%02d".format(amount / 100, amount % 100)
            return if (currency == "USD") "$$value" else "$value $currency"
        }
    }

    /** One horizontal span of [length] pixels of [argb], starting at column [x] of row [y]. */
    class Run(val x: Int, val y: Int, val length: Int, val argb: Int)

    class Badge(
        val id: String,
        val name: String,
        val description: String,
        val availability: Availability,
        val price: Price?,
        val size: Int,
        val runs: List<Run>,
    )

    companion object {
        private const val RESOURCE = "/assets/jukz/cosmetics/catalog.json"

        /** The copy baked into the jar. */
        fun bundled(): CosmeticCatalog {
            val text = CosmeticCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
                ?: error("missing $RESOURCE")
            return parse(text)
        }

        /** Parse and validate; throws on art that isn't square or uses a colour missing from its palette. */
        fun parse(text: String): CosmeticCatalog {
            val root = JsonParser.parseString(text).asJsonObject
            val items = root.getAsJsonArray("items").map { parseBadge(it.asJsonObject) }
            return CosmeticCatalog(root.get("version").asInt, root.get("defaultBadge").asString, items)
        }

        private fun parseBadge(json: JsonObject): Badge {
            val id = json.get("id").asString
            val palette = json.getAsJsonObject("palette").entrySet().associate { (key, value) ->
                require(key.length == 1) { "$id: palette keys are single characters" }
                key[0] to java.lang.Long.parseLong(value.asString, 16).toInt()
            }
            val art = json.getAsJsonArray("art").map { it.asString }
            val size = art.size
            val runs = mutableListOf<Run>()
            art.forEachIndexed { y, row ->
                require(row.length == size) { "$id: row $y is ${row.length} wide, art must be ${size}x$size" }
                var x = 0
                while (x < size) {
                    val ch = row[x]
                    var end = x + 1
                    while (end < size && row[end] == ch) end++
                    if (ch != '.') runs += Run(x, y, end - x, palette[ch] ?: error("$id: row $y uses '$ch', not in the palette"))
                    x = end
                }
            }
            val price = json.getAsJsonObject("price")?.let { Price(it.get("amount").asInt, it.get("currency").asString) }
            return Badge(
                id = id,
                name = json.get("name").asString,
                description = json.get("description")?.asString ?: "",
                availability = Availability.valueOf(json.get("availability").asString.uppercase()),
                price = price,
                size = size,
                runs = runs,
            )
        }
    }
}
