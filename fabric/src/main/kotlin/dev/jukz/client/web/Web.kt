package dev.jukz.client.web

import dev.jukz.JukzMod
import dev.jukz.compat.currentScreen
import dev.jukz.compat.openScreen
import dev.jukz.compat.windowHandle
import dev.nuulz.pane.Pane
import dev.nuulz.pane.PaneConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen

/**
 * The in-game browser, on pane (github.com/Nuulz/pane): Chromium off-screen, drawn by [WebScreen].
 * Chromium isn't in the jar: the first page opened downloads it into `jukz-browser/`, once.
 */
object Web {
    @Volatile var step = ""
        private set
    @Volatile var fraction = -1.0
        private set
    @Volatile private var installing = false

    val failed: Boolean get() = Pane.state() == Pane.State.FAILED
    val running: Boolean get() = Pane.running()

    fun open(parent: Screen?, url: String) {
        val mc = Minecraft.getInstance()
        if (failed) {
            ConfirmLinkScreen.confirmLinkNow(parent ?: mc.currentScreen ?: return, url, true)
            return
        }
        prepare()
        mc.openScreen(WebScreen(parent, url))
    }

    /** Configure once, then install (off-thread) and start Chromium on the render thread. */
    private fun prepare() {
        if (installing || Pane.state() != Pane.State.NEW) return
        installing = true
        val mc = Minecraft.getInstance()
        Pane.configure(PaneConfig.at(mc.gameDirectory.toPath().resolve("jukz-browser"))
            .userAgentProduct("jukz").glfwWindow { mc.windowHandle })
        Thread({
            val ok = Pane.install { s, f -> step = s; fraction = f }
            if (!ok) JukzMod.logger.warn("jukz: browser unavailable ({})", Pane.failure())
            else mc.execute { if (!Pane.start()) JukzMod.logger.warn("jukz: browser didn't start ({})", Pane.failure()) }
        }, "jukz-browser-install").apply { isDaemon = true }.start()
    }
}
