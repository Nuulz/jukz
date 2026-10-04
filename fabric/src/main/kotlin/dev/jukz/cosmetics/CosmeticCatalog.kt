package dev.jukz.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The cosmetics catalog: `cosmetics/catalog.json` at the repository root, the same file the rendezvous
 * Worker serves at `/v1/cosmetics/catalog` (Gradle copies it into the jar as the offline fallback, and
 * the live copy replaces it once fetched, so new items need no mod update).
 *
 * Everything is ASCII art keyed to an AARRGGBB palette ("." is empty):
 * - **badges** (tab list) are one square drawing; [Art.runs] pre-merges each row into same-colour spans;
 * - **hats, face and back pieces** are voxel models written as horizontal slices, bottom first; each
 *   slice's rows run front to back and its characters left to right as seen from the front. [Model.quads]
 *   holds only the faces that touch empty space, ready to draw.
 */
class CosmeticCatalog(val version: Int, val defaultBadge: String, val items: List<Item>) {

    fun item(id: String?): Item? = id?.let { wanted -> items.firstOrNull { it.id == wanted } }

    fun ofSlot(slot: Slot): List<Item> = items.filter { it.slot == slot }

    enum class Availability { FREE, PAID, GRANT }

    /** Where an item goes. The JSON `kind` is the lower-case name. */
    enum class Slot(val label: String) {
        BADGE("Badges"), HAT("Hats"), FACE("Face"), BACK("Back");

        val key: String get() = name.lowercase()

        companion object {
            fun of(key: String): Slot? = entries.firstOrNull { it.key == key }
        }
    }

    class Price(val amount: Int, val currency: String) {
        /** "$1.99"-style label; unknown currencies fall back to "1.99 EUR". */
        fun label(): String {
            val value = "%d.%02d".format(amount / 100, amount % 100)
            return if (currency == "USD") "$$value" else "$value $currency"
        }
    }

    /** One horizontal span of [length] pixels of [argb], starting at column [x] of row [y]. */
    class Run(val x: Int, val y: Int, val length: Int, val argb: Int)

    class Art(val size: Int, val runs: List<Run>)

    /** One square face: four corners (x, y, z in bone pixels, counter-clockwise from outside), its normal and colour. */
    class Quad(val corners: FloatArray, val nx: Float, val ny: Float, val nz: Float, val argb: Int)

    enum class Animation { NONE, BOB, SPIN }

    class Model(val quads: List<Quad>, val animation: Animation, val centerX: Float, val centerZ: Float)

    class Item(
        val id: String,
        val slot: Slot,
        val name: String,
        val description: String,
        val availability: Availability,
        val price: Price?,
        val art: Art?,
        val model: Model?,
    )

    companion object {
        private const val RESOURCE = "/assets/jukz/cosmetics/catalog.json"

        /** The copy baked into the jar. */
        fun bundled(): CosmeticCatalog {
            val text = CosmeticCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
                ?: error("missing $RESOURCE")
            return parse(text)
        }

        /** Parse and validate; throws on ragged art/slices or a colour missing from an item's palette. */
        fun parse(text: String): CosmeticCatalog {
            val root = JsonParser.parseString(text).asJsonObject
            // Unknown kinds (from a newer catalog) are skipped, not fatal.
            val items = root.getAsJsonArray("items").mapNotNull { parseItem(it.asJsonObject) }
            return CosmeticCatalog(root.get("version").asInt, root.get("defaultBadge").asString, items)
        }

        private fun parseItem(json: JsonObject): Item? {
            val id = json.get("id").asString
            val slot = Slot.of(json.get("kind").asString) ?: return null
            val palette = json.getAsJsonObject("palette").entrySet().associate { (key, value) ->
                require(key.length == 1) { "$id: palette keys are single characters" }
                key[0] to java.lang.Long.parseLong(value.asString, 16).toInt()
            }
            fun color(ch: Char, where: String) = palette[ch] ?: error("$id: $where uses '$ch', not in the palette")

            val art = json.getAsJsonArray("art")?.map { it.asString }?.let { rows -> parseArt(id, rows, ::color) }
            val model = json.getAsJsonObject("model")?.let { parseModel(id, it, ::color) }
            require(if (slot == Slot.BADGE) art != null else model != null) { "$id: ${slot.key} items need ${if (slot == Slot.BADGE) "art" else "a model"}" }
            val price = json.getAsJsonObject("price")?.let { Price(it.get("amount").asInt, it.get("currency").asString) }
            return Item(
                id = id,
                slot = slot,
                name = json.get("name").asString,
                description = json.get("description")?.asString ?: "",
                availability = Availability.valueOf(json.get("availability").asString.uppercase()),
                price = price,
                art = art,
                model = model,
            )
        }

        /** ASCII art drawn outside the catalog (UI icons): rows of characters keyed to [palette]. */
        fun art(rows: List<String>, palette: Map<Char, Int>): Art =
            parseArt("icon", rows) { ch, where -> palette[ch] ?: error("icon: $where uses '$ch', not in the palette") }

        private fun parseArt(id: String, art: List<String>, color: (Char, String) -> Int): Art {
            val size = art.size
            val runs = mutableListOf<Run>()
            art.forEachIndexed { y, row ->
                require(row.length == size) { "$id: row $y is ${row.length} wide, art must be ${size}x$size" }
                var x = 0
                while (x < size) {
                    val ch = row[x]
                    var end = x + 1
                    while (end < size && row[end] == ch) end++
                    if (ch != '.') runs += Run(x, y, end - x, color(ch, "row $y"))
                    x = end
                }
            }
            return Art(size, runs)
        }

        private fun parseModel(id: String, json: JsonObject, color: (Char, String) -> Int): Model {
            val voxel = json.get("voxel").asFloat
            val origin = json.getAsJsonArray("origin").map { it.asFloat }
            require(origin.size == 3) { "$id: origin is [x, y, z]" }
            val layers = json.getAsJsonArray("layers").map { layer -> layer.asJsonArray.map { it.asString } }
            val depth = layers.first().size
            val width = layers.first().first().length
            layers.forEachIndexed { l, layer ->
                require(layer.size == depth) { "$id: layer $l has ${layer.size} rows, the model is $depth deep" }
                layer.forEachIndexed { z, row -> require(row.length == width) { "$id: layer $l row $z is ${row.length} wide" } }
            }
            fun at(x: Int, l: Int, z: Int): Char =
                if (l in layers.indices && z in 0 until depth && x in 0 until width) layers[l][z][x] else '.'

            // Bone space has y pointing down: slice l spans [oy - (l+1)v, oy - lv].
            val (ox, oy, oz) = origin
            val quads = mutableListOf<Quad>()
            for (l in layers.indices) for (z in 0 until depth) for (x in 0 until width) {
                val ch = at(x, l, z)
                if (ch == '.') continue
                val argb = color(ch, "layer $l row $z")
                val x0 = ox + x * voxel; val x1 = x0 + voxel
                val y1 = oy - l * voxel; val y0 = y1 - voxel
                val z0 = oz + z * voxel; val z1 = z0 + voxel
                // A face is drawn only where the neighbour is empty (or see-through and different).
                fun open(nx: Int, nl: Int, nz: Int): Boolean {
                    val other = at(nx, nl, nz)
                    if (other == '.') return true
                    val otherArgb = color(other, "layer $nl row $nz")
                    return other != ch && (otherArgb ushr 24) < 0xFF
                }
                if (open(x, l + 1, z)) quads += Quad(floatArrayOf(x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0), 0f, -1f, 0f, argb) // top
                if (open(x, l - 1, z)) quads += Quad(floatArrayOf(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1), 0f, 1f, 0f, argb) // bottom
                if (open(x, l, z - 1)) quads += Quad(floatArrayOf(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0), 0f, 0f, -1f, argb) // front
                if (open(x, l, z + 1)) quads += Quad(floatArrayOf(x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1), 0f, 0f, 1f, argb) // back
                if (open(x - 1, l, z)) quads += Quad(floatArrayOf(x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1), -1f, 0f, 0f, argb) // left
                if (open(x + 1, l, z)) quads += Quad(floatArrayOf(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0), 1f, 0f, 0f, argb) // right
            }
            val animation = json.get("animation")?.asString?.uppercase()?.let { Animation.valueOf(it) } ?: Animation.NONE
            return Model(quads, animation, centerX = ox + width * voxel / 2, centerZ = oz + depth * voxel / 2)
        }
    }
}
