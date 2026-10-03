package dev.jukz.client.gui

import dev.jukz.JukzMod
import dev.jukz.runtime.GhostUpload
import dev.jukz.sync.R2SnapshotStore
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.text.Text
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shown after a guest-less close while the world is backed up to the cloud (model `upload.xml`), so a
 * friend can pick it up later. It drives the upload itself, retrying until it lands; after a few failed
 * attempts it offers an escape valve ("Exit anyway") and keeps retrying behind it. There is no back
 * button and Esc is vetoed while uploading, so the backup isn't abandoned by accident.
 */
class UploadingWorldScreen : JukzUiScreen("upload") {

    private val sent = AtomicLong(0)
    private val total = AtomicLong(-1)
    private val done = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var attempts = 0
    @Volatile private var escapeOffered = false
    @Volatile private var messageIndex = 0
    private var lastMessageSwapMs = 0L

    private var status: LabelComponent? = null
    private var tip: LabelComponent? = null
    private var bar: ProgressBar? = null
    private var root: FlowLayout? = null
    private var escapeShown = false

    fun isUploading(): Boolean = !done.get()

    override fun build(root: FlowLayout) {
        this.root = root
        label(root, "title").text(Text.literal("Saving your world to the cloud"))
        status = label(root, "message")
        tip = label(root, "tip")
        bar = progressBar(root, "bar")
        escapeShown = false
        refreshText(force = true)
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun tick() {
        super.tick()
        val pending = GhostUpload.pending()
        if (!started && pending != null) {
            started = true
            startUpload(pending)
        }
        if (done.get()) {
            GhostUpload.clear()
            MinecraftClient.getInstance().setScreen(TitleScreen())
            return
        }
        if (escapeOffered && !escapeShown) showEscape()
        refreshText(force = false)
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
                if (attempts >= MAX_ATTEMPTS_BEFORE_ESCAPE) escapeOffered = true
                Thread.sleep(RETRY_DELAY_MS)
            }
        }.apply { isDaemon = true; name = "jukz-ghost-upload" }.start()
    }

    private fun showEscape() {
        val r = root ?: return
        escapeShown = true
        addButton(r, "buttons", Text.literal("Exit anyway (no cloud backup)"), width = 220) {
            JukzMod.logger.warn("jukz: player skipped the ghost upload — world has no cloud backup")
            done.set(true) // unblocks tick() -> returns to the title screen; the upload thread stops
        }
    }

    private var shownStatus = ""

    private fun refreshText(force: Boolean) {
        val line = when {
            failed.get() && escapeOffered -> "Upload failed — retrying. You can exit without a backup."
            failed.get() -> "Upload hiccup — retrying…"
            !started -> "Preparing your world…"
            else -> progressLine()
        }
        if (force || line != shownStatus) {
            shownStatus = line
            status?.text(Text.literal(line))
        }
        val now = System.currentTimeMillis()
        if (force || now - lastMessageSwapMs > MESSAGE_SWAP_MS) {
            if (!force) messageIndex = (messageIndex + 1) % MESSAGES.size
            lastMessageSwapMs = now
            tip?.text(Text.literal(MESSAGES[messageIndex]))
        }
    }

    private fun progressLine(): String {
        val s = sent.get()
        val t = total.get()
        return if (t > 0) "Uploading… %.1f / %.1f MB".format(s / 1_048_576.0, t / 1_048_576.0)
        else "Uploading…"
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val t = total.get()
        bar?.progress(if (t > 0) sent.get().toDouble() / t else 0.0, COLOR_LIVE)
        super.render(context, mouseX, mouseY, delta)
    }

    companion object {
        private const val MAX_ATTEMPTS_BEFORE_ESCAPE = 3
        private const val RETRY_DELAY_MS = 3_000L
        private const val MESSAGE_SWAP_MS = 5_000L

        private val MESSAGES = listOf(
            "Tip: anyone with your world code can hop in — share it only with friends.",
            "Did you know? jukz worlds have no central server; you ARE the server.",
            "Keeping your world safe in the cloud so a friend can pick it up later…",
            "Tip: the green dot on the world list means someone is hosting it right now.",
            "Fun fact: the world only needs one host online to stay alive.",
        )
    }
}
