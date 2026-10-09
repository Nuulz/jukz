package dev.jukz.client.web

import dev.jukz.compat.GuiGraphics
import dev.jukz.compat.jukzText
import dev.jukz.compat.openScreen
import dev.jukz.compat.windowHandle
import dev.nuulz.pane.Pane
import dev.nuulz.pane.PaneView
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.net.URI

/**
 * A web page inside Minecraft, in the hub's look: a navy window with the gok- dots, a bar (back,
 * forward, reload, the address, open outside, close) and the page under it, which gets the mouse and
 * keyboard. Esc closes it. The first time, it shows Chromium's download instead.
 */
class WebScreen(private val parent: Screen?, private val url: String) : Screen(Component.literal("jukz")) {
    private var page: PaneView? = null
    private val texture = BrowserTexture()
    private var buttons: List<BarButton> = emptyList()
    private var pointerInPage = false

    // The window and the page area inside it, in GUI pixels.
    private var wx = 0; private var wy = 0; private var ww = 0; private var wh = 0
    private var px = 0; private var py = 0; private var pw = 0; private var ph = 0

    private fun scale() = minecraft!!.window.guiScale
    private fun physical(v: Int) = (v * scale()).toInt()
    private fun inPage(x: Double, y: Double) = x >= px && x < px + pw && y >= py && y < py + ph
    private fun pageX(x: Double) = ((x - px) * scale()).toInt()
    private fun pageY(y: Double) = ((y - py) * scale()).toInt()

    override fun init() {
        val margin = maxOf(6, (minOf(width, height) * 0.03).toInt())
        wx = margin; wy = margin; ww = width - 2 * margin; wh = height - 2 * margin
        px = wx + 4; py = wy + BAR + 4; pw = ww - 8; ph = wh - BAR - 8
        page?.resize(physical(pw), physical(ph))
        val y = wy + 4
        buttons = listOf(
            BarButton(wx + 34, y, 16, "‹", "Back (Alt+←)", { page?.hasBack() == true }) { page?.back() },
            BarButton(wx + 52, y, 16, "›", "Forward (Alt+→)", { page?.hasForward() == true }) { page?.forward() },
            BarButton(wx + 70, y, 16, "↻", "Reload (Ctrl+R)", { page != null }) { page?.reload() },
            BarButton(wx + ww - 74, y, 52, "Outside ↗", "Open it in your browser", { true }) {
                ConfirmLinkScreen.confirmLinkNow(this, page?.url() ?: url, true)
            },
            BarButton(wx + ww - 20, y, 16, "✕", "Close (Esc)", { true }) { onClose() },
        )
    }

    private fun ensurePage() {
        if (page != null || !Web.running) return
        page = Pane.open(url).also {
            it.resize(physical(pw), physical(ph))
            it.focus(true)
        }
    }

    private fun draw(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        Pane.pump()
        ensurePage()
        val page = page

        // window
        g.fill(wx + 2, wy + wh, wx + ww - 2, wy + wh + 3, 0x55000000)
        frame(g, wx, wy, ww, wh, RIM, WINDOW)
        listOf(RED, YELLOW, GREEN).forEachIndexed { i, c -> dot(g, wx + 7 + i * 8, wy + 4 + 7, c) }

        // address pill
        val ax = wx + 90; val aw = ww - 90 - 80
        frame(g, ax, wy + 4, aw, 14, if (page?.loading() == true) BLUE else WELL_RIM, WELL)
        val shown = page?.url()?.takeIf { it.isNotEmpty() } ?: url
        val (lock, host, rest) = split(shown)
        var tx = ax + 5
        if (lock) { g.jukzText(font, "🔒", tx, wy + 7, GREEN); tx += font.width("🔒") + 3 }
        g.jukzText(font, host, tx, wy + 7, TEXT); tx += font.width(host)
        val room = ax + aw - 5 - tx
        if (room > 8) g.jukzText(font, font.plainSubstrByWidth(rest, room), tx, wy + 7, TEXT_DIM)

        buttons.forEach { it.draw(g, mouseX, mouseY) }

        // page
        frame(g, px - 1, py - 1, pw + 2, ph + 2, WELL_RIM, 0xFF0A1734.toInt())
        if (page != null && texture.update(page)) texture.draw(g, px, py, pw, ph)
        else waiting(g)
        if (page?.loading() == true) loadingLine(g)

        buttons.firstOrNull { it.over(mouseX, mouseY) }?.let { b ->
            val w = font.width(b.tip) + 8
            val x = minOf(mouseX + 6, width - w - 2)
            frame(g, x, mouseY + 12, w, 13, RIM, WINDOW)
            g.jukzText(font, b.tip, x + 4, mouseY + 15, TEXT)
        }
    }

    /** Before the page exists: the download's progress, or why there's no browser. */
    private fun waiting(g: GuiGraphics) {
        val cx = px + pw / 2; val cy = py + ph / 2
        val (title, sub) = when {
            Web.failed -> "The browser couldn't start" to (Pane.failure() ?: "")
            Pane.state() == Pane.State.INSTALLING && Web.step == "Downloading" -> "Getting the browser ready" to "Only the first time. About 120 MB."
            Pane.state() == Pane.State.INSTALLING -> "Getting the browser ready" to "${Web.step}…"
            else -> "Loading…" to ""
        }
        g.jukzText(font, title, cx - font.width(title) / 2, cy - 16, TEXT)
        if (sub.isNotEmpty()) g.jukzText(font, font.plainSubstrByWidth(sub, pw - 20), cx - minOf(font.width(sub), pw - 20) / 2, cy - 4, TEXT_DIM)
        if (Pane.state() == Pane.State.INSTALLING) {
            val bw = minOf(200, pw - 40); val bx = cx - bw / 2; val by = cy + 10
            frame(g, bx, by, bw, 7, WELL_RIM, WELL)
            val f = Web.fraction
            if (f >= 0) g.fill(bx + 1, by + 1, bx + 1 + ((bw - 2) * f).toInt(), by + 6, BLUE)
            else { val s = ((System.currentTimeMillis() / 6) % (bw + 40)).toInt() - 40; g.fill(maxOf(bx + 1, bx + s), by + 1, minOf(bx + bw - 1, bx + s + 40), by + 6, BLUE) }
            if (f >= 0) { val p = "${(f * 100).toInt()}%"; g.jukzText(font, p, cx - font.width(p) / 2, by + 11, TEXT_DIM) }
        }
        if (Web.failed) {
            val hint = "Use \"Outside ↗\" to open it in your browser."
            g.jukzText(font, hint, cx - font.width(hint) / 2, cy + 10, TEXT_DIM)
        }
    }

    /** A bright segment running along the top of the page while it loads. */
    private fun loadingLine(g: GuiGraphics) {
        val run = pw + 60
        val s = ((System.currentTimeMillis() / 3) % run).toInt() - 60
        g.fill(maxOf(px, px + s), py, minOf(px + pw, px + s + 60), py + 1, 0xFF8FB8FF.toInt())
    }

    /** https?, host and the rest of an address, for the pill. */
    private fun split(address: String): Triple<Boolean, String, String> = runCatching {
        val u = URI(address)
        val rest = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        Triple(u.scheme == "https", u.host ?: address, rest.takeIf { it != "/" } ?: "")
    }.getOrDefault(Triple(false, address, ""))

    //? if >=26.2 {
    /*override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        draw(g, mouseX, mouseY)
    }
    *///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(g, mouseX, mouseY, delta)
        draw(g, mouseX, mouseY)
    }
    //?}

    // ---- input ------------------------------------------------------------------------------------

    private fun press(x: Double, y: Double, button: Int, mods: Int): Boolean {
        buttons.firstOrNull { it.over(x.toInt(), y.toInt()) }?.let { if (it.enabled()) it.action(); return true }
        if (!inPage(x, y)) return false
        page?.apply { focus(true); mouseDown(pageX(x), pageY(y), button, mods) }
        return true
    }

    private fun release(x: Double, y: Double, button: Int, mods: Int) {
        page?.mouseUp(pageX(x), pageY(y), button, mods)
    }

    private fun key(key: Int, scan: Int, mods: Int): Boolean {
        if (key == GLFW.GLFW_KEY_ESCAPE) return false
        page?.keyDown(key, scan, mods)
        return true
    }

    //? if >=1.21.11 {
    /*override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean =
        press(event.x(), event.y(), event.button(), event.modifiers()) || super.mouseClicked(event, doubleClick)

    override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean {
        release(event.x(), event.y(), event.button(), event.modifiers())
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dx: Double, dy: Double): Boolean {
        page?.mouseMove(pageX(event.x()), pageY(event.y()))
        return true
    }

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean =
        key(event.key(), event.scancode(), event.modifiers()) || super.keyPressed(event)

    override fun keyReleased(event: net.minecraft.client.input.KeyEvent): Boolean {
        page?.keyUp(event.key(), event.scancode(), event.modifiers())
        return true
    }

    override fun charTyped(event: net.minecraft.client.input.CharacterEvent): Boolean {
        event.codepointAsString().forEach { page?.typed(it, 0) }
        return true
    }
    *///?} else {
    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean =
        press(x, y, button, mods()) || super.mouseClicked(x, y, button)

    override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
        release(x, y, button, mods())
        return super.mouseReleased(x, y, button)
    }

    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        page?.mouseMove(pageX(x), pageY(y))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        key(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        page?.keyUp(keyCode, scanCode, modifiers)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        page?.typed(c, modifiers)
        return true
    }

    /** Shift/ctrl/alt held now (1.21.1's mouse events don't carry them). */
    private fun mods(): Int {
        val w = minecraft!!.windowHandle
        var m = 0
        if (GLFW.glfwGetKey(w, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) m = m or GLFW.GLFW_MOD_SHIFT
        if (GLFW.glfwGetKey(w, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS) m = m or GLFW.GLFW_MOD_CONTROL
        return m
    }
    //?}

    override fun mouseMoved(x: Double, y: Double) {
        val inside = inPage(x, y)
        if (inside) page?.mouseMove(pageX(x), pageY(y))
        else if (pointerInPage) page?.apply { mouseLeave(); resetCursor() }
        pointerInPage = inside
    }

    override fun mouseScrolled(x: Double, y: Double, h: Double, v: Double): Boolean {
        if (inPage(x, y)) page?.wheel(pageX(x), pageY(y), v, 0)
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        page?.close()
        page = null
        texture.release()
        minecraft?.openScreen(parent)
    }

    // ---- drawing bits -------------------------------------------------------------------------------

    private inner class BarButton(
        val x: Int, val y: Int, val w: Int, val label: String, val tip: String,
        val enabled: () -> Boolean, val action: () -> Unit,
    ) {
        fun over(mx: Int, my: Int) = mx >= x && mx < x + w && my >= y && my < y + 14

        fun draw(g: GuiGraphics, mx: Int, my: Int) {
            val on = enabled()
            val (rim, body) = when {
                !on -> 0xFF16264A.toInt() to 0xFF0C1836.toInt()
                over(mx, my) -> 0xFF4A78D8.toInt() to 0xFF1C3A78.toInt()
                else -> 0xFF2A4C94.toInt() to 0xFF142A5A.toInt()
            }
            frame(g, x, y, w, 14, rim, body)
            g.jukzText(font, label, x + (w - font.width(label)) / 2, y + 3, if (on) TEXT else TEXT_DIM)
        }
    }

    /** A box without its corner pixels, with a 1px rim. */
    private fun frame(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, rim: Int, body: Int) {
        g.fill(x + 1, y, x + w - 1, y + h, rim)
        g.fill(x, y + 1, x + w, y + h - 1, rim)
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, body)
    }

    private fun dot(g: GuiGraphics, cx: Int, cy: Int, color: Int) {
        g.fill(cx - 2, cy - 3, cx + 2, cy + 3, color)
        g.fill(cx - 3, cy - 2, cx + 3, cy + 2, color)
    }

    private companion object {
        const val BAR = 18
        const val WINDOW = 0xF00A1430.toInt()
        const val RIM = 0xFF2B56B0.toInt()
        const val WELL = 0xFF0A1734.toInt()
        const val WELL_RIM = 0xFF1C3570.toInt()
        const val BLUE = 0xFF1E66F5.toInt()
        const val TEXT = 0xFFE6EEFF.toInt()
        const val TEXT_DIM = 0xFF7F93BF.toInt()
        const val RED = 0xFFED8796.toInt()
        const val YELLOW = 0xFFEED49F.toInt()
        const val GREEN = 0xFFA6DA95.toInt()
    }
}
