package dev.jukz.client.gui

import dev.jukz.compat.BaseUIComponent
import dev.jukz.compat.OwoUIGraphics
import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import io.wispforest.owo.ui.core.Sizing

/**
 * The hub's 9×9 pixel icons (menu, tabs, buttons, decorations), drawn one art pixel per GUI pixel like
 * the badges. '.' is transparent; the palette letters are shared by all of them.
 */
object HubIcons {
    private val PALETTE = mapOf(
        'W' to 0xFFE6EEFF.toInt(), // near-white
        'B' to 0xFF8FB8FF.toInt(), // light jukz blue
        'b' to 0xFF2A5BC4.toInt(), // deep blue
        'G' to 0xFF5C6E94.toInt(), // muted grey-blue
        'R' to 0xFFED8796.toInt(), // red
        'Y' to 0xFFEED49F.toInt(), // yellow
        'g' to 0xFFA6DA95.toInt(), // green
        'S' to 0xFFFFF1C2.toInt(), // sparkle
    )

    private fun icon(vararg rows: String) = CosmeticCatalog.art(rows.toList(), PALETTE)

    val PERSON = icon(
        "...WWW...", "..WWWWW..", "..WWWWW..", "...WWW...", ".........",
        "..WWWWW..", ".WWWWWWW.", ".WWWWWWW.", ".........")
    val PALETTE_ICON = icon(
        "..WWWWW..", ".WRWWWYW.", "WWWWWWWWW", "WBWW..WWW", "WWW....WW",
        "WgWW..WWW", ".WWWWWWW.", "..WWWWW..", ".........")
    val GLOBE = icon(
        "..BBBBB..", ".B..B..B.", "B..B.B..B", "BBBBBBBBB", "B..B.B..B",
        "BBBBBBBBB", "B..B.B..B", ".B..B..B.", "..BBBBB..")
    val HEART = icon(
        ".........", ".RR...RR.", "RRRR.RRRR", "RRRRRRRRR", "RRRRRRRRR",
        ".RRRRRRR.", "..RRRRR..", "...RRR...", "....R....")
    val DOOR = icon(
        ".WWWWWW..", ".WbbbbW..", ".WbbbbW..", ".WbbbbW..", ".WbbbWW..",
        ".WbbbbW..", ".WbbbbW..", ".WbbbbW..", "WWWWWWWW.")
    val SHIELD = icon(
        "WWWWWWWWW", "W.......W", "W..BBB..W", "W.BBBBB.W", "W..BBB..W",
        "W...B...W", ".W.....W.", "..W...W..", "...WWW...")
    val HAT = icon(
        ".........", "...WWW...", "..WWWWW..", "..WWWWW..", "..BBBBB..",
        "WWWWWWWWW", ".WWWWWWW.", ".........", ".........")
    val FACE = icon(
        "WWWWWWWWW", "W.......W", "W.W...W.W", "W.W...W.W", "W.......W",
        "W.W...W.W", "W..WWW..W", "W.......W", "WWWWWWWWW")
    val BACKPACK = icon(
        "...WWW...", "..W...W..", ".WWWWWWW.", "W.......W", "W.WWWWW.W",
        "W.W...W.W", "W.WWWWW.W", "W.......W", ".WWWWWWW.")
    val NONE = icon(
        "..GGGGG..", ".G.....G.", "G.G...G.G", "G..G.G..G", "G...G...G",
        "G..G.G..G", "G.G...G.G", ".G.....G.", "..GGGGG..")
    val CLOUD = icon(
        ".........", "...BBB...", "..B...BB.", ".B......B", "B...B...B",
        "B..BBB..B", ".B..B..B.", "....B....", ".........")
    val SPARKLE = icon(
        "....S....", "....S....", "...SSS...", "SSSSWSSSS", "...SSS...",
        "....S....", "....S....", ".........", ".........")
    val PENCIL = icon(
        ".......WW", "......WWW", ".....WWW.", "....WWW..", "...WWW...",
        "..WWW....", ".BWW.....", ".BB......", ".........")
    val ARROW = icon(
        "..W......", "..WW.....", "...WW....", "....WW...", "...WW....",
        "..WW.....", "..W......", ".........", ".........")
    val UPLOAD = icon(
        "....W....", "...WWW...", "..WWWWW..", "....W....", "....W....",
        "....W....", ".........", "WWWWWWWWW", ".........")
    val DOWNLOAD = icon(
        "....W....", "....W....", "....W....", "..WWWWW..", "...WWW...",
        "....W....", ".........", "WWWWWWWWW", ".........")

    val CALENDAR = icon(
        ".W.....W.", "WWWWWWWWW", "WbbbbbbbW", "W.......W", "W.W.W.W.W",
        "W.......W", "W.W.W.W.W", "W.......W", "WWWWWWWWW")
    val SHIRT = icon(
        ".WW...WW.", "WWWW.WWWW", "WWWWWWWWW", ".WWWWWWW.", "..WWWWW..",
        "..WWWWW..", "..WWWWW..", "..WWWWW..", ".........")
    val CUBE = icon(
        "....W....", "..WW.WW..", "WW.....WW", "W.WW.WW.W", "W...W...W",
        "W...W...W", "W...W...W", ".WW.W.WW.", "...WWW...")
    val CHECK = icon(
        "..ggggg..", ".g.....g.", "g......gg", "g.....g.g", "g.g..g..g",
        "g..gg...g", "g.......g", ".g.....g.", "..ggggg..")
    val LOCK = icon(
        "...GGG...", "..G...G..", "..G...G..", ".GGGGGGG.", ".GGGGGGG.",
        ".GGG.GGG.", ".GGG.GGG.", ".GGGGGGG.", ".........")

    private fun dot(c: Char) = icon(
        ".........", "...ccc...".replace('c', c), "..ccccc..".replace('c', c), ".ccccccc.".replace('c', c),
        ".ccccccc.".replace('c', c), ".ccccccc.".replace('c', c), "..ccccc..".replace('c', c),
        "...ccc...".replace('c', c), ".........")
    val DOTS = listOf(dot('R'), dot('Y'), dot('g'))

    /** An icon as an owo component, [px] square (9 = one art pixel per GUI pixel). */
    class Icon(private val art: CosmeticCatalog.Art, private val px: Int = 9) : BaseUIComponent() {
        init {
            sizing(Sizing.fixed(px), Sizing.fixed(px))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            BadgeRenderer.draw(context, art, x, y, px)
        }
    }
}
