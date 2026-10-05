package dev.jukz.client.gui

import dev.jukz.client.CloudWorlds
import dev.jukz.client.CloudWorlds.State
import dev.jukz.client.CloudWorlds.Upload
import dev.jukz.cosmetics.Cosmetics
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Your jukz account, in game. Signed in with a Microsoft account: your plan and today's usage, the worlds
 * in your cloud (bring one here, or forget it) and the worlds only on this PC (upload one to your cloud,
 * so your other PCs get it), plus your cosmetics and creations. Without one: what an account adds — jukz
 * plays the same without it. The website (password, delete my data) is one button away.
 *
 * Layout: `assets/jukz/owo_ui/account.xml` (style from `theme.xml`).
 */
class AccountScreen(private val parent: Screen?) : JukzUiScreen("account") {

    private var shown: List<Any?> = emptyList()
    private var requested = false
    @Volatile private var loading = false

    init {
        Cosmetics.ensureSignedIn()
    }

    private fun refresh() {
        loading = true
        CloudWorlds.refreshAccount { loading = false; rebuild() }
    }

    override fun build(root: FlowLayout) {
        val account = Cosmetics.account
        val signedIn = Cosmetics.sessionToken() != null
        val summary = CloudWorlds.summary
        label(root, "title").text(Component.literal(if (signedIn && summary != null && summary.name.isNotBlank()) summary.name else "Your account"))
        accent(root, if (account is Cosmetics.Account.Failed) ACCENT_ERROR else ACCENT_INFO)

        val (plan, planColor) = when {
            signedIn && summary != null ->
                "Microsoft account · backups up to ${summary.maxBackupMb} MB, kept ${summary.keepDays} days · " +
                    "${summary.uploadsToday}/${summary.uploadsPerDay} today" to COLOR_SUBTLE
            signedIn -> "Looking at your account…" to COLOR_SUBTLE
            account is Cosmetics.Account.Failed ->
                "Playing without an account. jukz works the same; signing in with a Microsoft account adds your worlds on every PC, bigger backups and cosmetics." to ACCENT_ACTION
            else -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
        }
        label(root, "plan").text(Component.literal(plan)).color(Color.ofArgb(planColor))

        val rows = root.childById(FlowLayout::class.java, "rows")
        if (signedIn) buildLists(rows) else buildGuest(rows)

        label(root, "extras").text(Component.literal(extrasLine(signedIn)))
        if (account is Cosmetics.Account.Failed) {
            addButton(root, "buttons", Component.literal("Try again"), width = 70) { Cosmetics.ensureSignedIn(force = true); rebuild() }
        }
        addButton(root, "buttons", Component.literal("Cosmetics"), width = 70) { minecraft?.setScreen(CosmeticsScreen(this)) }
        addButton(root, "buttons", Component.literal("Website"), width = 64) {
            Cosmetics.accountPageUrl { url -> minecraft?.execute { ConfirmLinkScreen.confirmLinkNow(this, url, true) } }
        }.tooltip(Component.literal("nuulm.com/jukz/cuenta: your password, the plans, and \"delete my data\""))
        addButton(root, "buttons", Component.literal("Done"), width = 60) { onClose() }
    }

    private fun buildLists(rows: FlowLayout) {
        val cloud = CloudWorlds.worlds
        val cloudIds = cloud.orEmpty().map { it.worldId }.toSet()
        section(rows, "cloud", "In your cloud")
        when {
            cloud == null -> note(rows, "Loading…")
            cloud.isEmpty() -> note(rows, "Nothing yet. Upload a world below, or close one you play alone.")
            else -> cloud.forEachIndexed { i, world ->
                val state = CloudWorlds.state(world)
                val (status, color) = when (state) {
                    State.HERE -> "on this PC" to COLOR_LIVE
                    State.AWAY -> "not on this PC" to COLOR_SUBTLE
                    State.BRINGING -> "bringing it…" to ACCENT_INFO
                    State.FAILED -> "couldn't bring it" to ACCENT_ERROR
                }
                val actions = row(rows, "c$i", world.name, "saved ${DATE.format(Instant.ofEpochMilli(world.updated))} · $status", color)
                if (state == State.AWAY || state == State.FAILED) actions.child(button("Bring here", 62) { CloudWorlds.bring(world) { rebuild() }; rebuild() })
                if (state != State.BRINGING) actions.child(button("Forget", 44) { CloudWorlds.forget(world) { rebuild() } }
                    .tooltip(Component.literal("Take it off your account's list. The world itself isn't deleted anywhere.")))
            }
        }

        val local = CloudWorlds.localWorlds().filter { it.worldId !in cloudIds }
        section(rows, "local", "Only on this PC")
        if (local.isEmpty()) note(rows, "Every jukz world here is in your cloud.")
        val open = minecraft?.singleplayerServer?.worldData?.levelName // the world being played can't be packed now
        local.forEachIndexed { i, world ->
            val upload = CloudWorlds.uploads[world.worldId]
            val (status, color) = when (upload?.first) {
                Upload.UPLOADING -> upload.second to ACCENT_INFO
                Upload.DONE -> upload.second to COLOR_LIVE
                Upload.FAILED -> upload.second to ACCENT_ERROR
                null -> "not in your cloud" to COLOR_SUBTLE
            }
            val actions = row(rows, "l$i", world.name, status, color)
            if (upload?.first != Upload.UPLOADING && upload?.first != Upload.DONE) {
                val up = button("Upload", 50) { CloudWorlds.upload(world) { rebuild() }; rebuild() }
                up.active = open == null || open != world.name
                up.tooltip(Component.literal(if (up.active) "Back it up to your cloud, so your other PCs get it." else "Close this world first."))
                actions.child(up)
            }
        }
    }

    private fun buildGuest(rows: FlowLayout) {
        section(rows, "plans", "With a Microsoft account")
        listOf(
            "Your worlds on every PC: they come over by themselves",
            "Cloud backups up to 95 MB, kept 180 days (40 MB and 30 days without)",
            "Cosmetics: hats, glasses, wings and tab badges, free",
        ).forEach { note(rows, "+ $it") }
        section(rows, "same", "The same either way")
        note(rows, "Playing with friends, hosting, the handoff and the relay.")
    }

    private fun extrasLine(signedIn: Boolean): String {
        val s = CloudWorlds.summary ?: return if (signedIn) "" else "Accounts are optional and free."
        val creations = if (s.creationsPublished != null) " · ${s.creationsPublished} model(s) you made in jukz" else ""
        return "${s.ownedCosmetics} cosmetics yours$creations"
    }

    private fun section(rows: FlowLayout, id: String, text: String) {
        rows.child(ui!!.expandTemplate(LabelComponent::class.java, "section", mapOf("id" to "section-$id")).text(Component.literal(text)))
    }

    private fun note(rows: FlowLayout, text: String) {
        rows.child(Components.label(Component.literal(text)).color(Color.ofArgb(COLOR_SUBTLE)).maxWidth(300))
    }

    /** A world row; returns its action box. */
    private fun row(rows: FlowLayout, id: String, name: String, info: String, infoColor: Int): FlowLayout {
        val row = ui!!.expandTemplate(FlowLayout::class.java, "world-row", mapOf("id" to id))
        row.childById(LabelComponent::class.java, "name-$id").text(Component.literal(name))
        row.childById(LabelComponent::class.java, "info-$id").text(Component.literal(info)).color(Color.ofArgb(infoColor))
        rows.child(row)
        return row.childById(FlowLayout::class.java, "actions-$id")
    }

    private fun button(text: String, width: Int, onPress: () -> Unit): ButtonComponent =
        Components.button(Component.literal(text)) { onPress() }.also { it.horizontalSizing(Sizing.fixed(width)) }

    override fun tick() {
        super.tick()
        if (!requested && Cosmetics.sessionToken() != null) { // signed in (now or just now): load the account
            requested = true
            refresh()
        }
        val now = listOf(Cosmetics.account, loading, CloudWorlds.summary, CloudWorlds.worlds,
            CloudWorlds.worlds?.map(CloudWorlds::state), CloudWorlds.uploads.toMap())
        if (now != shown) {
            shown = now
            rebuild()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = true

    /** Back to a fresh world list, so worlds brought meanwhile show up in it. */
    override fun onClose() {
        minecraft?.setScreen(if (parent is SelectWorldScreen) SelectWorldScreen(TitleScreen()) else parent)
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
    }
}
