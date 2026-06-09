package dev.jukz.client.gui

import dev.jukz.client.HostCoordinator
import dev.jukz.core.host.HostStatus
import dev.jukz.runtime.HostSession
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Host-side status panel, reached from the pause-menu button once a world is shared. Shows the
 * shareable code (copyable), the world's identity (UUID + fencing generation), the endpoint guests
 * dial, and a live self-check — does the registry still hold our record under our token? — so the
 * host can confirm at a glance that the share is healthy. The check runs off the render thread (it
 * is a registry/network read) and is re-runnable with Refresh.
 */
class HostInfoScreen(private val parent: Screen?) : Screen(Text.literal("World info")) {

    private val record get() = HostSession.record

    @Volatile private var status: HostStatus? = null
    @Volatile private var checking = true

    override fun init() {
        val cx = width / 2
        val y = height - 40
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Copy code")) {
                record?.let { client?.keyboard?.clipboard = it.worldId.shortCode() }
            }.dimensions(cx - 154, y, 100, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Refresh")) { refresh() }
                .dimensions(cx - 50, y, 100, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Done")) { client?.setScreen(parent) }
                .dimensions(cx + 54, y, 100, 20).build(),
        )
        // Self-heal: every jukz world is auto-hosted on open, but if that hasn't taken (or failed),
        // kick it off now so opening this panel always ends with the world online.
        if (!HostSession.isHosting) {
            client?.server?.let { HostCoordinator.autoHost(it) }
        }
        refresh()
    }

    /** Re-poll the live status without blocking the render thread. */
    private fun refresh() {
        checking = true
        status = null
        Thread {
            status = runCatching { HostSession.currentStatus() }.getOrNull()
            checking = false
        }.apply { isDaemon = true; name = "jukz-host-status" }.start()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta) // background + buttons
        val cx = width / 2

        context.drawCenteredTextWithShadow(textRenderer, JukzStatusScreen.BRAND, cx, 28, JukzStatusScreen.ACCENT_INFO)
        context.drawCenteredTextWithShadow(textRenderer, title, cx, 44, JukzStatusScreen.COLOR_TITLE)

        val rec = record
        if (rec == null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Not hosting this world."), cx, 80, JukzStatusScreen.COLOR_SUBTLE)
            return
        }

        val rows = listOf(
            "Share code" to rec.worldId.shortCode(),
            "World UUID" to rec.worldId.uuid.toString(),
            "Generation" to rec.token.hostGeneration.toString(),
            "Endpoints" to rec.endpoints.joinToString(", ") { it.format() },
        )
        var y = 78
        for ((label, value) in rows) {
            drawRow(context, cx, y, label, value)
            y += 16
        }

        // Live self-check line.
        val (text, color) = when {
            checking -> "checking…" to JukzStatusScreen.COLOR_SUBTLE
            status?.live == true -> "live · heartbeat #${status!!.heartbeatSeq}" to COLOR_LIVE
            else -> "not announced" to JukzStatusScreen.ACCENT_ERROR
        }
        y += 8
        drawRow(context, cx, y, "Status", text, valueColor = color)
    }

    /** A "label   value" row: label right-aligned just left of centre, value left-aligned just right. */
    private fun drawRow(context: DrawContext, cx: Int, y: Int, label: String, value: String, valueColor: Int = JukzStatusScreen.COLOR_TITLE) {
        val labelText = Text.literal("$label:")
        context.drawTextWithShadow(textRenderer, labelText, cx - 8 - textRenderer.getWidth(labelText), y, JukzStatusScreen.COLOR_SUBTLE)
        context.drawTextWithShadow(textRenderer, Text.literal(value), cx + 8, y, valueColor)
    }

    override fun shouldCloseOnEsc(): Boolean = true

    companion object {
        private const val COLOR_LIVE = 0xFF6BCB6B.toInt()
    }
}
