package dev.jukz.client.gui

import dev.jukz.client.CloudWorlds
import dev.jukz.client.CloudWorlds.State
import dev.jukz.cosmetics.Cosmetics
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.world.SelectWorldScreen
import net.minecraft.text.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "My cloud": the worlds backed up on your Minecraft account, from any PC. New ones come over on
 * their own when you start the game signed in (see [CloudWorlds]); this screen shows which are here,
 * brings one on purpose (also one you deleted here before) and can forget one from the account.
 *
 * Layout: `assets/jukz/owo_ui/cloud.xml` (style from `theme.xml`).
 */
class CloudWorldsScreen(private val parent: Screen?) : JukzUiScreen("cloud") {

    private var shown: List<Any?> = emptyList()
    private var requested = false
    @Volatile private var loading = false

    init {
        Cosmetics.ensureSignedIn()
    }

    private fun refresh() {
        loading = true
        CloudWorlds.refresh { loading = false; rebuild() }
    }

    override fun build(root: FlowLayout) {
        label(root, "title").text(Text.literal("My cloud"))
        val worlds = CloudWorlds.worlds
        val signedIn = Cosmetics.sessionToken() != null
        val account = Cosmetics.account
        val (message, color) = when {
            account is Cosmetics.Account.Failed -> "Your cloud needs a Microsoft (premium) account: ${account.reason}" to ACCENT_ERROR
            !signedIn -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
            worlds == null -> "Looking at your cloud…" to COLOR_SUBTLE
            worlds.isNullOrEmpty() -> "Nothing here yet. Worlds you close on your own are backed up to your account." to COLOR_SUBTLE
            else -> "Your worlds from every PC you play on." to COLOR_SUBTLE
        }
        label(root, "message").text(Text.literal(message)).color(Color.ofArgb(color))
        accent(root, if (account is Cosmetics.Account.Failed) ACCENT_ERROR else ACCENT_INFO)

        val rows = root.childById(FlowLayout::class.java, "rows")
        worlds.orEmpty().forEachIndexed { i, world ->
            val row = ui!!.expandTemplate(FlowLayout::class.java, "world-row", mapOf("id" to "$i"))
            row.childById(LabelComponent::class.java, "name-$i").text(Text.literal(world.name))
            val state = CloudWorlds.state(world)
            val (status, statusColor) = when (state) {
                State.HERE -> "on this PC" to COLOR_LIVE
                State.AWAY -> "not on this PC" to COLOR_SUBTLE
                State.BRINGING -> "bringing it…" to ACCENT_INFO
                State.FAILED -> "couldn't bring it" to ACCENT_ERROR
            }
            row.childById(LabelComponent::class.java, "info-$i")
                .text(Text.literal("saved ${DATE.format(Instant.ofEpochMilli(world.updated))} · $status"))
                .color(Color.ofArgb(statusColor))
            val actions = row.childById(FlowLayout::class.java, "actions-$i")
            if (state == State.AWAY || state == State.FAILED) {
                actions.child(button("Bring here", 70) { CloudWorlds.bring(world) { rebuild() }; rebuild() })
            }
            if (state != State.BRINGING) actions.child(button("Forget", 50) { CloudWorlds.forget(world) { rebuild() } }
                .tooltip(Text.literal("Remove it from your account's list. The world itself isn't deleted anywhere.")))
            rows.child(row)
        }

        label(root, "hint").text(Text.literal("Only you can see this list. Offline (non-premium) accounts don't have a cloud."))
        addButton(root, "buttons", Text.literal("My account"), width = 90) {
            Cosmetics.accountPageUrl { url -> client?.execute { net.minecraft.client.gui.screen.ConfirmLinkScreen.open(this, url, true) } }
        }
        addButton(root, "buttons", Text.literal("Refresh"), width = 80) { if (Cosmetics.sessionToken() != null) refresh() }
        addButton(root, "buttons", Text.literal("Done"), width = 80) { close() }
    }

    private fun button(text: String, width: Int, onPress: () -> Unit): ButtonComponent =
        Components.button(Text.literal(text)) { onPress() }.also { it.horizontalSizing(io.wispforest.owo.ui.core.Sizing.fixed(width)) }

    override fun tick() {
        super.tick()
        if (!requested && Cosmetics.sessionToken() != null) { // signed in (now or just now): read the list
            requested = true
            refresh()
        }
        val now = listOf(Cosmetics.account, loading, CloudWorlds.worlds, CloudWorlds.worlds?.map(CloudWorlds::state))
        if (now != shown) {
            shown = now
            rebuild()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = true

    /** Back to a fresh world list, so worlds brought meanwhile show up in it. */
    override fun close() {
        client?.setScreen(if (parent is SelectWorldScreen) SelectWorldScreen(TitleScreen()) else parent)
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
    }
}
