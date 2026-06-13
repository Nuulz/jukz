package dev.jukz.client.gui

import dev.jukz.JukzMod
import dev.jukz.runtime.GhostUpload
import dev.jukz.sync.R2SnapshotStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shown when a host closes a world with no guests: it uploads the world snapshot to R2 so a later
 * guest can take over (ghost takeover). Non-dismissable while the upload runs — the window-close
 * guard in JukzClient also refuses the X — so the world is reliably backed up. A rotating message
 * slot entertains the wait; after repeated failures an "Exit anyway" button appears so the player is
 * never trapped (an OS force-kill is the only uncatchable case, by design).
 */
class UploadingWorldScreen : Screen(Text.literal("Saving your world to the cloud")) {

    private val sent = AtomicLong(0)
    private val total = AtomicLong(-1)
    private val done = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var attempts = 0
    @Volatile private var exitButton: ButtonWidget? = null
    @Volatile private var messageIndex = 0
    private var lastMessageSwapMs = 0L

    /** A jukz upload in progress must block exit; read by the window-close guard. */
    fun isUploading(): Boolean = !done.get()

    override fun init() {
        // No back button while uploading; the escape valve is added on persistent failure.
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun tick() {
        val pending = GhostUpload.pending()
        if (!started && pending != null) {
            started = true
            startUpload(pending)
        }
        if (done.get()) {
            GhostUpload.clear()
            MinecraftClient.getInstance().setScreen(TitleScreen())
        }
    }

    private fun startUpload(pending: GhostUpload.Pending) {
        Thread {
            while (!done.get()) {
                attempts++
                sent.set(0)
                total.set(pending.pack.size.toLong())
                val ok = R2SnapshotStore.uploadGhost(
                    pending.worldId, pending.generation, pending.pack, pending.head,
                ) { s, t -> sent.set(s); if (t > 0) total.set(t) }
                if (ok) {
                    JukzMod.logger.info("jukz: ghost snapshot uploaded")
                    done.set(true)
                    return@Thread
                }
                failed.set(true)
                // Surface the escape valve after a few attempts, then keep retrying behind it.
                if (attempts >= MAX_ATTEMPTS_BEFORE_ESCAPE) {
                    MinecraftClient.getInstance().execute { addExitButton() }
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }.apply { isDaemon = true; name = "jukz-ghost-upload" }.start()
    }

    private fun addExitButton() {
        if (exitButton != null) return
        val button = ButtonWidget.builder(Text.literal("Exit anyway (no cloud backup)")) {
            JukzMod.logger.warn("jukz: player skipped the ghost upload — world has no cloud backup")
            done.set(true) // unblocks tick() -> returns to the title screen; the upload thread stops
        }.dimensions(width / 2 - 110, height / 2 + 40, 220, 20).build()
        exitButton = button
        addDrawableChild(button)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta) // background + widgets (escape button) first, per repo idiom
        val cx = width / 2

        context.drawCenteredTextWithShadow(textRenderer, title, cx, height / 2 - 48, 0xFFFFFFFF.toInt())

        val status = when {
            failed.get() && exitButton != null -> "Upload failed — retrying. You can exit without a backup."
            failed.get() -> "Upload hiccup — retrying…"
            !started -> "Preparing your world…"
            else -> progressLine()
        }
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, height / 2 - 28, 0xFFC0C0C0.toInt())

        drawProgressBar(context, cx)

        // Rotating message slot.
        val now = System.currentTimeMillis()
        if (now - lastMessageSwapMs > MESSAGE_SWAP_MS) {
            lastMessageSwapMs = now
            messageIndex = (messageIndex + 1) % MESSAGES.size
        }
        context.drawCenteredTextWithShadow(
            textRenderer, Text.literal(MESSAGES[messageIndex]), cx, height / 2 + 12, 0xFF808080.toInt(),
        )
    }

    private fun progressLine(): String {
        val s = sent.get()
        val t = total.get()
        return if (t > 0) "Uploading… %.1f / %.1f MB".format(s / 1_048_576.0, t / 1_048_576.0)
        else "Uploading…"
    }

    private fun drawProgressBar(context: DrawContext, cx: Int) {
        val barW = 220
        val barH = 6
        val x = cx - barW / 2
        val y = height / 2 - 12
        context.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xFF3F3F3F.toInt())
        val t = total.get()
        val frac = if (t > 0) (sent.get().toDouble() / t).coerceIn(0.0, 1.0) else 0.0
        context.fill(x, y, x + (barW * frac).toInt(), y + barH, 0xFF6BCB6B.toInt())
    }

    companion object {
        private const val MAX_ATTEMPTS_BEFORE_ESCAPE = 3
        private const val RETRY_DELAY_MS = 3_000L
        private const val MESSAGE_SWAP_MS = 5_000L

        /** Rotating tips/jokes; expand freely. */
        private val MESSAGES = listOf(
            "Tip: anyone with your world code can hop in — share it only with friends.",
            "Did you know? jukz worlds have no central server; you ARE the server.",
            "Keeping your world safe in the cloud so a friend can pick it up later…",
            "Tip: the green dot on the world list means someone is hosting it right now.",
            "Fun fact: the world only needs one host online to stay alive.",
        )
    }
}
