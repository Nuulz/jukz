package dev.jukz.client.gui

import dev.jukz.client.CloudWorlds
import dev.jukz.client.GuestAdmission
import dev.jukz.compat.hasRealAccount
import dev.jukz.config.JukzConfig
import dev.jukz.client.CloudWorlds.State
import dev.jukz.client.CloudWorlds.Upload
import dev.jukz.compat.BaseUIComponent
import dev.jukz.compat.FocusSource
import dev.jukz.compat.OwoUIGraphics
import dev.jukz.compat.UIComponents
import dev.jukz.compat.UIContainers
import dev.jukz.compat.currentGame
import dev.jukz.compat.openScreen
import dev.jukz.core.model.GameVersion
import dev.jukz.core.model.VersionFit
import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import dev.jukz.cosmetics.CosmeticCatalog.Availability
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import dev.jukz.cosmetics.Cosmetics
import dev.jukz.cosmetics.CosmeticFit
import dev.jukz.cosmetics.Cosmetics.Account
import dev.jukz.cosmetics.ModelIcon
import dev.jukz.cosmetics.PlayerPreview
import dev.jukz.net.SkinPayload
import dev.jukz.skins.LocalSkins
import dev.jukz.skins.MojangSkins
import dev.jukz.client.gui.JukzSurface.Tone
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin

/**
 * The jukz hub: one window for your account, your cosmetics and your cloud worlds. A side menu picks the
 * section; you stand on the right in 3D on every section, and in Cosmetics pointing at a card tries it on.
 *
 * A section is a [Section] entry plus one `build…` function filling `content` (and optionally `tabs`,
 * `hint`, `buttons`): adding one (friends, settings) is an entry and a function, nothing else.
 *
 * Layout: `assets/jukz/owo_ui/hub.xml`; the pixel look: [JukzSurface].
 */
open class HubScreen(private val parent: Screen?, private var section: Section = Section.PROFILE) : JukzUiScreen("hub") {

    enum class Section(val label: String, val icon: CosmeticCatalog.Art) {
        PROFILE("Profile", HubIcons.PERSON), COSMETICS("Cosmetics", HubIcons.PALETTE_ICON), SKIN("Skin", HubIcons.PENCIL),
        WORLDS("Worlds", HubIcons.GLOBE), SETTINGS("Settings", HubIcons.GEAR)
    }

    private var tab = Slot.HAT
    private var shown: List<Any?> = emptyList()
    private var requested = false
    @Volatile private var loading = false
    @Volatile private var saving = false
    @Volatile private var saveError: String? = null

    /** What the pointer is over in Cosmetics: worn by the preview until it leaves. */
    private var trying: CosmeticCatalog.Item? = null
    private var preview: PreviewComponent? = null
    /** When the section last changed: its content slides in from the right for [SLIDE_MS]. */
    private var openedAt = 0L

    override val fullSize: Boolean get() = true

    init {
        Cosmetics.ensureSignedIn()
    }

    override fun build(root: FlowLayout) {
        val account = Cosmetics.account
        accent(root, if (account is Account.Failed) ACCENT_ERROR else ACCENT_INFO)
        root.childById(FlowLayout::class.java, "side").horizontalSizing(Sizing.fixed(sideWidth))
        root.childById(FlowLayout::class.java, "preview-column").horizontalSizing(Sizing.fixed(previewWidth))
        listOf("status", "hint").forEach { label(root, it).maxWidth(mainWidth - 4) }
        root.childById(FlowLayout::class.java, "side").surface(JukzSurface.sideWell())
        root.childById(FlowLayout::class.java, "main").surface(JukzSurface.well())
        root.childById(FlowLayout::class.java, "preview-column").surface(JukzSurface.stage())
        val dots = root.childById(FlowLayout::class.java, "dots")
        HubIcons.DOTS.forEach { dots.child(HubIcons.Icon(it, 7)) }
        root.childById(FlowLayout::class.java, "who-icon").child(HubIcons.Icon(HubIcons.CLOUD))
        label(root, "who").text(Component.literal(whoLine())).color(Color.ofArgb(JukzSurface.TEXT_DIM))
        root.childById(FlowLayout::class.java, "heading-icon").child(HubIcons.Icon(section.icon))

        buildSide(root)
        buildPreview(root)
        label(root, "heading").text(Component.literal(section.label.uppercase())).color(Color.ofArgb(JukzSurface.TEXT_DIM))
        val content = root.childById(FlowLayout::class.java, "content")
        hoverables.clear()
        hovered = null
        root.childById(FlowLayout::class.java, "top").child(SlideIn(content, openedAt) { x, y -> pointAt(x, y) })
        if (compact) content.gap(3)
        if (section != Section.COSMETICS) detach(root, root.childById(FlowLayout::class.java, "tabs"))
        when (section) {
            Section.PROFILE -> buildProfile(root, content)
            Section.COSMETICS -> buildCosmetics(root, content)
            Section.WORLDS -> buildWorlds(root, content)
            Section.SKIN -> buildSkin(root, content)
            Section.SETTINGS -> buildSettings(root, content)
        }
    }

    // ---- frame ----------------------------------------------------------------------------------

    private fun whoLine(): String {
        val name = minecraft?.gameProfile?.name ?: ""
        val s = CloudWorlds.summary
        return when {
            Cosmetics.sessionToken() == null -> "$name · guest"
            s == null -> name
            else -> "$name · ${s.uploadsToday}/${s.uploadsPerDay} uploads today"
        }
    }

    private fun buildSide(root: FlowLayout) {
        val side = root.childById(FlowLayout::class.java, "side")
        if (compact) side.gap(3) // the sections plus Ko-fi and Done have to fit at GUI scale 4
        Section.entries.forEach { s ->
            side.child(sideButton(s.label, s.icon, { section == s }) { open(s) })
        }
        side.child(UIContainers.verticalFlow(Sizing.fill(100), Sizing.expand(100)))
        side.child(sideButton("Ko-fi", HubIcons.HEART) { dev.jukz.client.web.Web.open(this, KOFI_URL) }
            .tooltip(Component.literal("Support jukz. Thank you!")))
        side.child(sideButton("Done", HubIcons.DOOR) { onClose() })
    }

    private fun sideButton(text: String, icon: CosmeticCatalog.Art, selected: () -> Boolean = { false }, onPress: () -> Unit): ButtonComponent =
        if (compact) JukzSurface.iconButton("", icon, selected, centred = true, onPress = onPress)
            .also { it.sizing(Sizing.fill(100), Sizing.fixed(18)); it.tooltip(Component.literal(text)) }
        else JukzSurface.iconButton(text, icon, selected, pointer = JukzSurface.Pointer.RIGHT, onPress = onPress)
            .also { it.sizing(Sizing.fill(100), Sizing.fixed(24)) }

    private fun open(next: Section) {
        if (next == section) return
        if (section == Section.SKIN) dropPending()
        skinNote = null
        section = next
        trying = null
        openedAt = System.currentTimeMillis()
        rebuild()
    }

    /** You, slowly turning (drag to turn), wearing your picks plus whatever you're pointing at. */
    private fun buildPreview(root: FlowLayout) {
        val profile = minecraft?.gameProfile ?: return
        val override = if (section == Section.SKIN && pending != null) LocalSkins.textureFor(LocalSkins.PREVIEW)
            else LocalSkins.textureFor(profile.id)
        val component = PreviewComponent(PlayerPreview(profile, override)) {
            val worn = Cosmetics.loadoutFor(profile.id)
            trying?.let { worn + (it.slot to it.id) } ?: worn
        }
        preview?.let { component.carryOver(it) }
        preview = component
        root.childById(FlowLayout::class.java, "model").child(component)

        val plate = root.childById(FlowLayout::class.java, "nameplate")
        Cosmetics.wearing(Slot.BADGE)?.let { plate.child(ItemIcon(it, 8)) }
        plate.child(UIComponents.label(Component.literal(profile.name)))
        plate.surface(JukzSurface.card(Tone.IDLE))
        label(root, "trying").text(Component.literal(trying?.let { "trying: ${it.name}" } ?: ""))

        val column = root.childById(FlowLayout::class.java, "preview-column")
        val game = UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(4)
        game.child(HubIcons.Icon(HubIcons.CUBE))
        game.child(UIComponents.label(Component.literal(if (compact) "Java Edition" else "Minecraft Java")).color(Color.ofArgb(JukzSurface.TEXT_DIM)))
        game.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        column.child(game)
        if (section != Section.SKIN) column.child(JukzSurface.iconButton("Edit skin", HubIcons.PENCIL, centred = true) {
            open(Section.SKIN)
        }.also { it.sizing(Sizing.fill(100), Sizing.fixed(20)) })
    }

    // ---- Profile --------------------------------------------------------------------------------

    private fun buildProfile(root: FlowLayout, content: FlowLayout) {
        val account = Cosmetics.account
        val signedIn = Cosmetics.sessionToken() != null
        val summary = CloudWorlds.summary
        val (status, color) = when {
            signedIn && summary != null -> "Signed in with your Microsoft account." to COLOR_LIVE
            signedIn -> "Looking at your account…" to COLOR_SUBTLE
            account is Account.Failed -> "Playing without an account. jukz works the same." to ACCENT_ACTION
            else -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
        }
        status(root, status, color)

        if (signedIn && summary != null) {
            detach(root, label(root, "status")) // the name card says it
            nameCard(content, summary.name.ifBlank { minecraft?.gameProfile?.name ?: "" })
            val used = summary.uploadsToday.toDouble() / summary.uploadsPerDay.coerceAtLeast(1)
            content.child(tile(HubIcons.CLOUD, "Storage", "up to ${summary.maxBackupMb} MB per backup", go = { open(Section.WORLDS) }))
            content.child(tileRow(
                tile(HubIcons.UPLOAD, "Uploads", "${summary.uploadsToday} of ${summary.uploadsPerDay}", bar = used, go = { open(Section.WORLDS) }),
                tile(HubIcons.CALENDAR, "Retention", "${summary.keepDays} days", go = { open(Section.WORLDS) }),
            ))
            content.child(tileRow(
                tile(HubIcons.SHIRT, "Cosmetics", "${summary.ownedCosmetics} yours", go = { open(Section.COSMETICS) }),
                tile(HubIcons.CUBE, "Creations", "${summary.creationsPublished ?: 0} made"),
            ))
        } else if (!signedIn) {
            section(content, "plans", "With a Microsoft account")
            listOf(
                "Your worlds on every PC: they come over by themselves",
                "Cloud backups up to 95 MB, kept 180 days (40 MB and 30 days without)",
                "Cosmetics: hats, glasses, wings and tab badges, free",
            ).forEach { note(content, "+ $it") }
            section(content, "same", "The same either way")
            note(content, "Playing with friends, hosting, the handoff and the relay.")
        }

        hint(root, "Password, plans and \"delete my data\" are on the website.")
        if (account is Account.Failed) retryButton(root)
        actionButton(root, "Website", HubIcons.GLOBE, 84) {
            Cosmetics.accountPageUrl { url -> minecraft?.execute { dev.jukz.client.web.Web.open(this, url) } }
        }.tooltip(Component.literal("nuulm.com/jukz/cuenta: your password, the plans, and \"delete my data\""))
    }

    /** The headline: your name, big, with a green light, over the account it's signed in with. */
    private fun nameCard(content: FlowLayout, name: String) {
        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(5)
        val top = UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(6)
        top.child(HubWidgets.BigText(name, 2, JukzSurface.TEXT))
        top.child(HubWidgets.Dot(COLOR_LIVE))
        top.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        val sub = UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(5)
        sub.child(HubIcons.Icon(HubIcons.CHECK))
        sub.child(UIComponents.label(Component.literal("Microsoft account")).color(Color.ofArgb(JukzSurface.TEXT_DIM)))
        sub.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        card.child(top)
        if (!compact) card.child(sub) else top.child(HubIcons.Icon(HubIcons.CHECK)).also { top.tooltip(Component.literal("Microsoft account")) }
        card.padding(Insets.of(if (compact) 6 else 10)).surface(JukzSurface.card(Tone.IDLE))
        content.child(card)
    }

    /**
     * A dashboard tile: icon and a small upper-case label, the value under it, optionally a progress
     * [bar]; with [go] it's clickable, lights up on hover and shows a "›" that nudges toward where it goes.
     */
    private fun tile(icon: CosmeticCatalog.Art, label: String, value: String, bar: Double? = null, go: (() -> Unit)? = null): FlowLayout {
        val tile = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(if (compact) 2 else 4)
        val head = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(5)
        head.child(HubIcons.Icon(icon))
        head.child(UIComponents.label(Component.literal(label.uppercase())).color(Color.ofArgb(JukzSurface.TEXT_DIM)))
        head.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        val body = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(6)
        body.child(UIComponents.label(Component.literal(value)).color(Color.ofArgb(JukzSurface.TEXT)).horizontalSizing(Sizing.expand(100)))
        if (go != null) body.child(HubWidgets.Chevron(tile))
        body.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        tile.child(head).child(body)
        if (bar != null) {
            val track = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(6)
            track.child(HubWidgets.Bar(bar))
            track.child(UIComponents.label(Component.literal("${(bar * 100).toInt()}%")).color(Color.ofArgb(0xFF8FB8FF.toInt())))
            track.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
            tile.child(track)
        }
        tile.padding(Insets.of(if (compact) 5 else 8)).surface(JukzSurface.card(Tone.IDLE))
        if (go != null) {
            hoverables += Hoverable(tile, Tone.IDLE, null, clickable = true)
            tile.cursorStyle(CursorStyle.HAND)
            //? if >=1.21.11 {
            /*tile.mouseDown().subscribe { _, _ -> go(); true }
            *///?} else {
            tile.mouseDown().subscribe { _, _, _ -> go(); true }
            //?}
        }
        return tile
    }

    /** Tiles side by side, sharing the width (owo doesn't split it between several "expand" children). */
    private fun tileRow(vararg tiles: FlowLayout): FlowLayout {
        val share = 100 / tiles.size - 1
        tiles.forEach { it.horizontalSizing(Sizing.fill(share)) }
        // Same height side by side: a tile without the bar line gets an empty line in its place.
        val lines = tiles.maxOf { it.children().size }
        tiles.filter { it.children().size < lines }.forEach { it.child(UIContainers.horizontalFlow(Sizing.fill(100), Sizing.fixed(9))) }
        return UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(if (compact) 4 else 6).also { r -> tiles.forEach(r::child) }
    }

    /** A Profile line: icon, name, value; with [go], a "›" and a click that opens where it's about. */
    private fun stat(content: FlowLayout, icon: CosmeticCatalog.Art, name: String, value: String, go: (() -> Unit)? = null) {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(8)
        row.child(HubIcons.Icon(icon))
        row.child(UIComponents.label(Component.literal(name)).color(Color.ofArgb(JukzSurface.TEXT_DIM)).horizontalSizing(Sizing.fixed(58)))
        row.child(UIComponents.label(Component.literal(value)).color(Color.ofArgb(COLOR_TITLE)).horizontalSizing(Sizing.expand(100)))
        row.verticalAlignment(io.wispforest.owo.ui.core.VerticalAlignment.CENTER)
        row.padding(Insets.of(if (compact) 5 else 7)).surface(JukzSurface.card(Tone.IDLE))
        if (go != null) {
            row.child(HubIcons.Icon(HubIcons.ARROW))
            hoverables += Hoverable(row, Tone.IDLE, null, clickable = true)
            row.cursorStyle(CursorStyle.HAND)
            //? if >=1.21.11 {
            /*row.mouseDown().subscribe { _, _ -> go(); true }
            *///?} else {
            row.mouseDown().subscribe { _, _, _ -> go(); true }
            //?}
        }
        content.child(row)
    }

    /** A rule with a sparkle in the middle. */
    private fun divider() = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.fixed(5)).also { it.surface(JukzSurface.divider()) }

    // ---- Cosmetics ------------------------------------------------------------------------------

    private fun buildCosmetics(root: FlowLayout, content: FlowLayout) {
        val account = Cosmetics.account
        val me = Cosmetics.canWear
        val local = Cosmetics.local
        val (message, color) = when {
            !Cosmetics.enabled -> "Cosmetics need the rendezvous server (rendezvous.url is \"none\")." to ACCENT_ERROR
            saveError != null -> "Couldn't save: $saveError" to ACCENT_ERROR
            local -> "No Mojang account: what you wear goes to the friends you play with through jukz." to ACCENT_ACTION
            account is Account.Failed -> "Couldn't sign in: ${account.reason}" to ACCENT_ERROR
            me -> "Point at something to try it on, click to wear it." to COLOR_SUBTLE
            else -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
        }
        status(root, message, color)

        val tabs = root.childById(FlowLayout::class.java, "tabs")
        Slot.entries.forEach { slot ->
            val icon = when (slot) { Slot.BADGE -> HubIcons.SHIELD; Slot.HAT -> HubIcons.HAT; Slot.FACE -> HubIcons.FACE; Slot.BACK -> HubIcons.BACKPACK; Slot.PET -> HubIcons.PAW; Slot.TRAIL -> HubIcons.SPARKLE; Slot.EMOTE -> HubIcons.BUBBLE }
            val button = JukzSurface.iconButton(if (compact) "" else slot.label, icon, { tab == slot }, centred = true, pointer = JukzSurface.Pointer.DOWN) {
                tab = slot; trying = null; rebuild()
            }
            button.sizing(Sizing.fixed(if (compact) 28 else 62), Sizing.fixed(22))
            if (compact) button.tooltip(Component.literal(slot.label))
            tabs.child(button)
        }

        val cards = listOf(noneCard(me)) + Cosmetics.catalog.ofSlot(tab).map { card(it, me) }
        cards.chunked(cardsPerRow()).forEach { row ->
            content.child(UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(CARD_GAP).also { r -> row.forEach(r::child) })
        }

        val fitting = me && !tab.flat && Cosmetics.wearing(tab) != null
        val fit = Cosmetics.myFit(tab)
        hint(root, when {
            tab == Slot.BADGE -> "Your badge sits left of your name in the tab list, for everyone running jukz."
            tab == Slot.TRAIL -> "Your trail floats around you while you stand and follows you when you move."
            tab == Slot.EMOTE -> "Press G in game to show it over your head (change the key in Controls)."
            fitting -> "Position: up ${fit.up} · out ${fit.out} px. ▲▼ move it up or down, ◀▶ closer to or farther from you."
            else -> "Everyone running jukz sees what you wear. All cosmetics are free for now."
        })
        if (account is Account.Failed && !local) retryButton(root)
        if (fitting) fitButtons(root, fit)
        actionButton(root, "Make your own", HubIcons.PENCIL, 112) {
            // Signed in: the link also verifies the creator account, so rewards reach this Minecraft account.
            Cosmetics.creatorPageUrl { url -> minecraft?.execute { ConfirmLinkScreen.confirmLinkNow(this, url, true) } }
        }.tooltip(Component.literal("Design a cosmetic in Blockbench and send it in. If it gets in, it's yours to keep."))
    }

    /** Lunar-style nudges for the worn piece of this tab: up/down and closer/farther, half a pixel a click. */
    private fun fitButtons(root: FlowLayout, fit: CosmeticFit) {
        fun nudge(label: String, tip: String, change: (CosmeticFit) -> CosmeticFit) =
            addButton(root, "buttons", Component.literal(label), width = 20) {
                val next = change(Cosmetics.myFit(tab))
                Cosmetics.setFit(tab, next.copy(up = CosmeticFit.clamp(next.up), out = CosmeticFit.clamp(next.out)))
                rebuild()
            }.tooltip(Component.literal(tip))
        nudge("▲", "Move up") { it.copy(up = it.up + CosmeticFit.STEP) }
        nudge("▼", "Move down") { it.copy(up = it.up - CosmeticFit.STEP) }
        nudge("◀", "Closer to you") { it.copy(out = it.out - CosmeticFit.STEP) }
        nudge("▶", "Farther from you") { it.copy(out = it.out + CosmeticFit.STEP) }
        if (!fit.isZero) addButton(root, "buttons", Component.literal("Reset"), width = 40) { Cosmetics.setFit(tab, CosmeticFit.NONE); rebuild() }
    }

    /**
     * Compact on a small GUI (1080p at GUI scale 4 is 480 wide): the side menu and the tabs show only
     * their icons (names in tooltips), the preview and the cards shrink.
     */
    private val compact get() = width < COMPACT_BELOW
    private val sideWidth get() = if (compact) 32 else 100
    private val previewWidth get() = if (compact) 92 else 130
    private val card get() = if (compact) 56 else 68
    private val icon get() = if (compact) 30 else 38

    /** Width of the open section's text column: the window minus the side menu, preview, gaps and padding. */
    private val mainWidth get() = (width * 0.94).toInt() - 32 - sideWidth - previewWidth - 2 * 10 - 2 * 10

    /** As many cards as fit the content area. */
    private fun cardsPerRow(): Int = ((mainWidth - 8 + CARD_GAP) / (card + CARD_GAP)).coerceIn(2, 8)

    private fun sizeCard(c: FlowLayout) = c.sizing(Sizing.fixed(card), Sizing.fixed(card - 4))

    private fun noneCard(me: Boolean): FlowLayout {
        val card = ui!!.expandTemplate(FlowLayout::class.java, "item-card", mapOf("id" to "none")).also(::sizeCard)
        val wearingNothing = me && Cosmetics.wearing(tab) == null
        card.childById(FlowLayout::class.java, "icon-none").child(HubIcons.Icon(HubIcons.NONE, 18))
        card.childById(LabelComponent::class.java, "state-none")
            .text(Component.literal(if (wearingNothing) "✔ on" else "None"))
            .color(Color.ofArgb(if (wearingNothing) COLOR_LIVE else JukzSurface.TEXT_DIM))
        card.tooltip(Component.literal(if (tab == Slot.BADGE) "Hide your badge" else "Wear nothing here"))
        wire(card, if (wearingNothing) Tone.ON else Tone.IDLE, null, clickable = me && !wearingNothing) { save(Cosmetics.NO_BADGE) }
        return card
    }

    private fun card(item: CosmeticCatalog.Item, me: Boolean): FlowLayout {
        val card = ui!!.expandTemplate(FlowLayout::class.java, "item-card", mapOf("id" to item.id)).also(::sizeCard)
        val owned = me && Cosmetics.owns(item)
        val wearing = me && Cosmetics.wearing(item.slot)?.id == item.id
        val needs = item.requires.mapNotNull { Cosmetics.catalog.item(it)?.name }
        val missing = me && owned && !Cosmetics.unlocked(item, Cosmetics.wornIds())
        val locked = me && (!owned || missing)

        card.childById(FlowLayout::class.java, "icon-${item.id}").child(ItemIcon(item, icon, dimmed = locked))
        val (state, stateColor) = when {
            wearing -> "✔ on" to COLOR_LIVE
            missing -> "Needs ${needs.joinToString(" + ")}" to ACCENT_ACTION
            locked -> (item.price?.label() ?: if (item.availability == Availability.GRANT) "Special" else "Locked") to ACCENT_ACTION
            item.availability == Availability.GRANT -> "Special" to ACCENT_ACTION
            else -> "Free" to COLOR_SUBTLE
        }
        card.childById(LabelComponent::class.java, "state-${item.id}").text(Component.literal(state)).color(Color.ofArgb(stateColor))
        card.tooltip(listOfNotNull(
            Component.literal(item.name),
            Component.literal(item.description).withColor(COLOR_SUBTLE),
            item.author?.let { Component.literal("by $it").withColor(COLOR_LIVE) },
            needs.takeIf { it.isNotEmpty() }?.let { Component.literal("Wear ${it.joinToString(" and ")} to use it").withColor(ACCENT_ACTION) },
        ))
        val tone = when { wearing -> Tone.ON; locked -> Tone.LOCKED; else -> Tone.IDLE }
        wire(card, tone, item, clickable = owned && !wearing) { save(item.id) }
        return card
    }

    /** Hover tries the item on (locked ones too: that's the shop window); click wears it if you can. */
    private fun wire(card: FlowLayout, tone: Tone, item: CosmeticCatalog.Item?, clickable: Boolean, onPick: () -> Unit) {
        card.surface(JukzSurface.card(tone))
        hoverables += Hoverable(card, tone, item, clickable)
        if (!clickable) return
        card.cursorStyle(CursorStyle.HAND)
        //? if >=1.21.11 {
        /*card.mouseDown().subscribe { _, _ -> onPick(); true }
        *///?} else {
        card.mouseDown().subscribe { _, _, _ -> onPick(); true }
        //?}
    }

    private class Hoverable(val card: FlowLayout, val tone: Tone, val item: CosmeticCatalog.Item?, val clickable: Boolean)
    private val hoverables = mutableListOf<Hoverable>()
    private var hovered: Hoverable? = null

    /**
     * Which card the pointer is over, checked every frame from the cursor position. owo's enter/leave
     * events don't reach a card while the pointer sits on its icon, so they can't drive this.
     */
    private fun pointAt(mouseX: Int, mouseY: Int) {
        val mx = mouseX.toDouble(); val my = mouseY.toDouble()
        // Only inside the scroll area: cards scrolled out of it still have bounds under the hint.
        val inView = uiAdapter?.rootComponent?.childById(FlowLayout::class.java, "content")?.parent()?.isInBoundingBox(mx, my) == true
        val now = if (inView) hoverables.firstOrNull { it.card.isInBoundingBox(mx, my) } else null
        if (now === hovered) return
        hovered?.let { it.card.surface(JukzSurface.card(it.tone)) }
        now?.let { if (it.clickable) it.card.surface(JukzSurface.card(Tone.HOVER)) }
        hovered = now
        trying = now?.item
        refreshTrying()
    }

    private fun refreshTrying() {
        uiAdapter?.rootComponent?.childById(LabelComponent::class.java, "trying")
            ?.text(Component.literal(trying?.let { "trying: ${it.name}" } ?: ""))
    }

    private fun save(item: String) {
        if (saving) return
        saving = true
        saveError = null
        Cosmetics.equip(tab, item) { error ->
            saving = false
            saveError = error
            minecraft?.execute {
                if (error == null) { trying = null; preview?.hop() }
                rebuild()
            }
        }
    }

    // ---- Skin -----------------------------------------------------------------------------------

    /** A PNG picked (button or dropped) but not applied yet; the preview wears it meanwhile. */
    private var pending: LocalSkins.Skin? = null
    private var pendingName = ""
    private var slimArms = LocalSkins.saved?.slim ?: false
    @Volatile private var skinBusy = false
    /** The last result line (text, colour), shown until the section changes. */
    @Volatile private var skinNote: Pair<String, Int>? = null

    private fun buildSkin(root: FlowLayout, content: FlowLayout) {
        MojangSkins.check()
        val account = MojangSkins.account
        val (line, color) = when (account) {
            MojangSkins.Account.CHECKING -> "Looking at your Minecraft account…" to COLOR_SUBTLE
            MojangSkins.Account.PREMIUM -> "Mojang account: the skin goes to your account and everyone sees it." to COLOR_LIVE
            MojangSkins.Account.OFFLINE -> "No Mojang account: your skin stays on this PC and goes only to the friends you play with through jukz." to ACCENT_ACTION
        }
        status(root, line, color)

        val current = when {
            pending != null -> "$pendingName (not applied yet)"
            account == MojangSkins.Account.PREMIUM -> "Your Mojang skin"
            LocalSkins.saved != null -> "Saved on this PC"
            else -> "The default skin"
        }
        content.child(tile(HubIcons.SHIRT, "Skin", current))

        val arms = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(4)
        listOf(false to "Classic arms", true to "Slim arms").forEach { (slim, label) ->
            arms.child(JukzSurface.iconButton(label, null, { slimArms == slim }, centred = true) {
                slimArms = slim
                pending?.let { pending = LocalSkins.Skin(it.png, slim); LocalSkins.preview(pending!!) }
                rebuild()
            }.also { it.sizing(Sizing.fill(49), Sizing.fixed(20)) })
        }
        content.child(arms)
        skinNote?.let { (text, c) -> note(content, text).also { it.color(Color.ofArgb(c)) } }

        hint(root, "Or drop a .png on this window. Skins are 64×64 (the old 64×32 works too).")
        actionButton(root, "Choose PNG", HubIcons.UPLOAD, 92) { pickPng() }
        val apply = JukzSurface.iconButton(if (skinBusy) "Applying…" else "Apply", null, centred = true, primary = true) { applySkin() }
        apply.active = pending != null && !skinBusy && account != MojangSkins.Account.CHECKING
        apply.sizing(Sizing.fixed(64), Sizing.fixed(22))
        root.childById(FlowLayout::class.java, "buttons").child(apply)
        val canReset = !skinBusy && (account == MojangSkins.Account.PREMIUM || LocalSkins.saved != null)
        JukzSurface.iconButton("Reset", null, centred = true) { resetSkin() }.also {
            it.active = canReset
            it.sizing(Sizing.fixed(48), Sizing.fixed(22))
            it.tooltip(Component.literal("Back to the default skin (Steve / Alex)."))
            root.childById(FlowLayout::class.java, "buttons").child(it)
        }
    }

    /** The system's file picker (off the render thread: it blocks until closed). */
    private fun pickPng() {
        Thread({
            val chosen = runCatching {
                org.lwjgl.system.MemoryStack.stackPush().use { stack ->
                    val filters = stack.mallocPointer(1)
                    filters.put(stack.UTF8("*.png")).flip()
                    org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Choose a skin", null, filters, "Skin (.png)", false)
                }
            }.getOrNull() ?: return@Thread
            minecraft?.execute { choose(java.nio.file.Path.of(chosen)) }
        }, "jukz-skin-picker").apply { isDaemon = true }.start()
    }

    /** A PNG dropped on the window goes to the Skin section. */
    override fun onFilesDrop(paths: List<java.nio.file.Path>) {
        val png = paths.firstOrNull { it.toString().endsWith(".png", ignoreCase = true) } ?: return
        if (section != Section.SKIN) { section = Section.SKIN; openedAt = System.currentTimeMillis() }
        choose(png)
    }

    private fun choose(path: java.nio.file.Path) {
        val bytes = runCatching { java.nio.file.Files.readAllBytes(path) }.getOrNull()
        if (bytes == null || !SkinPayload.isSkinPng(bytes)) {
            skinNote = "That isn't a skin: it has to be a 64×64 (or 64×32) PNG." to ACCENT_ERROR
            rebuild()
            return
        }
        pending = LocalSkins.Skin(bytes, slimArms)
        pendingName = path.fileName.toString()
        skinNote = null
        LocalSkins.preview(pending!!)
        rebuild()
    }

    private fun applySkin() {
        val skin = pending ?: return
        skinBusy = true
        if (MojangSkins.account == MojangSkins.Account.PREMIUM) {
            MojangSkins.upload(skin) { error -> minecraft?.execute {
                skinBusy = false
                if (error == null) {
                    LocalSkins.wearSelf(skin)
                    dropPending()
                    skinNote = "Done. Friends see it once their game reloads your profile (rejoining does it)." to COLOR_LIVE
                    preview?.hop()
                } else skinNote = error to ACCENT_ERROR
                rebuild()
            } }
        } else {
            LocalSkins.save(skin)
            skinBusy = false
            dropPending()
            skinNote = "Saved. Friends in this world see it now, the others next time you play together." to COLOR_LIVE
            preview?.hop()
            rebuild()
        }
    }

    private fun resetSkin() {
        skinBusy = true
        if (MojangSkins.account == MojangSkins.Account.PREMIUM) {
            MojangSkins.reset { error -> minecraft?.execute {
                skinBusy = false
                LocalSkins.clearSaved()
                skinNote = (error ?: "Back to the default skin. It shows everywhere after a restart.") to (if (error == null) COLOR_LIVE else ACCENT_ERROR)
                rebuild()
            } }
        } else {
            LocalSkins.clearSaved()
            skinBusy = false
            dropPending()
            skinNote = "Back to the default skin." to COLOR_LIVE
            rebuild()
        }
    }

    private fun dropPending() {
        if (pending != null) LocalSkins.clearPreview()
        pending = null
        pendingName = ""
    }

    // ---- Worlds ---------------------------------------------------------------------------------

    private fun buildWorlds(root: FlowLayout, content: FlowLayout) {
        if (Cosmetics.sessionToken() == null) {
            status(root, "Sign in with a Microsoft account to keep your worlds in the cloud.", ACCENT_ACTION)
            note(content, "Your worlds then come over to every PC you play on, by themselves.")
            if (Cosmetics.account is Account.Failed) retryButton(root)
            return
        }
        status(root, "Your worlds on every PC.", COLOR_SUBTLE)
        val cloud = CloudWorlds.worlds
        val cloudIds = cloud.orEmpty().map { it.worldId }.toSet()
        section(content, "cloud", "In your cloud")
        when {
            cloud == null -> note(content, "Loading…")
            cloud.isEmpty() -> note(content, "Nothing yet. Upload a world below, or close one you play alone.")
            else -> cloud.forEachIndexed { i, world ->
                val state = CloudWorlds.state(world)
                val (status, color) = when (state) {
                    State.HERE -> "here" to COLOR_LIVE
                    State.AWAY -> "not here" to COLOR_SUBTLE
                    State.BRINGING -> "bringing…" to ACCENT_INFO
                    State.FAILED -> "failed" to ACCENT_ERROR
                }
                // Another version's world says so; a newer one can't come here at all.
                val version = if (world.fit == VersionFit.SAME) "" else " · ${(world.game ?: GameVersion.LEGACY).name}"
                val actions = row(content, "c$i", world.name, "${DATE.format(Instant.ofEpochMilli(world.updated))}$version · $status", color)
                if (state == State.AWAY || state == State.FAILED) {
                    val bring = button("Bring", 40) { CloudWorlds.bring(world) { rebuild() }; rebuild() }
                    bring.renderer(JukzSurface.primary())
                    val saved = (world.game ?: GameVersion.LEGACY).name
                    when (world.fit) {
                        VersionFit.SAME -> bring.tooltip(Component.literal("Bring it to this PC."))
                        VersionFit.UPGRADE -> bring.tooltip(Component.literal("Saved on $saved. Opening it here will update it to ${currentGame.name}."))
                        VersionFit.TOO_NEW -> {
                            bring.active = false
                            bring.tooltip(Component.literal("Saved on Minecraft $saved, newer than your ${currentGame.name}. Open it with $saved."))
                        }
                    }
                    actions.child(bring)
                }
                if (state != State.BRINGING) actions.child(button("Forget", 40) { CloudWorlds.forget(world) { rebuild() } }
                    .tooltip(Component.literal("Take it off your account's list. The world itself isn't deleted anywhere.")))
            }
        }

        val local = CloudWorlds.localWorlds().filter { it.worldId !in cloudIds }
        section(content, "local", "Only on this PC")
        if (local.isEmpty()) note(content, "Every jukz world here is in your cloud.")
        val open = minecraft?.singleplayerServer?.worldData?.levelName // the world being played can't be packed now
        local.forEachIndexed { i, world ->
            val upload = CloudWorlds.uploads[world.worldId]
            val (status, color) = when (upload?.first) {
                Upload.UPLOADING -> upload.second to ACCENT_INFO
                Upload.DONE -> upload.second to COLOR_LIVE
                Upload.FAILED -> upload.second to ACCENT_ERROR
                null -> "not in your cloud" to COLOR_SUBTLE
            }
            val actions = row(content, "l$i", world.name, status, color)
            if (upload?.first != Upload.UPLOADING && upload?.first != Upload.DONE) {
                val up = button("Upload", 46) { CloudWorlds.upload(world) { rebuild() }; rebuild() }
                up.renderer(JukzSurface.primary())
                up.active = open == null || open != world.name
                up.tooltip(Component.literal(if (up.active) "Back it up to your cloud, so your other PCs get it." else "Close this world first."))
                actions.child(up)
            }
        }
        hint(root, "Forgetting a world only takes it off the list; nothing is deleted.")
    }

    // ---- settings -------------------------------------------------------------------------------

    /** `config/jukz.properties`, without opening the file. Each change is saved at once. */
    private fun buildSettings(root: FlowLayout, content: FlowLayout) {
        status(root, "Saved as you change them.", COLOR_SUBTLE)
        section(content, "guests", "Who can join your worlds")
        val premium = minecraft?.hasRealAccount == true
        val offline = JukzConfig.offlineGuests || !premium
        toggleRow(content, "offline", "Friends without a Microsoft account",
            when {
                !premium -> "Always on: you're playing without an account yourself."
                offline -> "On. They join under a name nobody can check, so only for friends you trust."
                else -> "Off. Only players Mojang verifies can join, like on a normal server."
            }, offline, enabled = premium) { on ->
            JukzConfig.set(JukzConfig.KEY_OFFLINE_GUESTS, on.toString())
            GuestAdmission.allowUnverifiedGuests = GuestAdmission.allowUnverified(premium, on)
        }

        section(content, "network", "Connection")
        val rendezvous = JukzConfig.rendezvousSetting
        val custom = rendezvous.isNotEmpty() && !rendezvous.equals("none", ignoreCase = true)
        val lanOnly = rendezvous.equals("none", ignoreCase = true)
        val actions = row(content, "rendezvous", "Play over the internet",
            when {
                custom -> "Your own server: $rendezvous"
                lanOnly -> "Off: only people on your network see your worlds. Applies after a restart."
                else -> "On, through jukz.nuulm.com. Applies after a restart."
            }, if (lanOnly) COLOR_SUBTLE else COLOR_LIVE)
        if (custom) actions.child(button("Use jukz", 50) { JukzConfig.set(JukzConfig.KEY_RENDEZVOUS_URL, ""); rebuild() }
            .tooltip(Component.literal("Go back to the public jukz server.")))
        else actions.child(switch(!lanOnly) { on -> JukzConfig.set(JukzConfig.KEY_RENDEZVOUS_URL, if (on) "" else "none") })
        toggleRow(content, "relay", "Always use the relay",
            "For testing: skip direct connections even when they'd work. Leave off.", JukzConfig.forceRelay) { on ->
            JukzConfig.set(JukzConfig.KEY_FORCE_RELAY, on.toString())
        }
        hint(root, "Everything here lives in config/jukz.properties, if you'd rather edit it by hand.")
    }

    private fun toggleRow(content: FlowLayout, id: String, name: String, info: String, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
        val switch = switch(on, change)
        switch.active = enabled
        row(content, id, name, info, if (on) COLOR_LIVE else COLOR_SUBTLE).child(switch)
    }

    /** An On/Off button; pressing it flips the setting and redraws the section. */
    private fun switch(on: Boolean, change: (Boolean) -> Unit): ButtonComponent =
        button(if (on) "On" else "Off", 34) { change(!on); rebuild() }.also { if (on) it.renderer(JukzSurface.primary()) }

    /** A world row; returns its action box. */
    private fun row(content: FlowLayout, id: String, name: String, info: String, infoColor: Int): FlowLayout {
        val row = ui!!.expandTemplate(FlowLayout::class.java, "world-row", mapOf("id" to id))
        row.surface(JukzSurface.card(Tone.IDLE))
        row.childById(LabelComponent::class.java, "name-$id").text(Component.literal(name))
        row.childById(LabelComponent::class.java, "info-$id").text(Component.literal(info)).color(Color.ofArgb(infoColor))
        content.child(row)
        return row.childById(FlowLayout::class.java, "actions-$id")
    }

    // ---- shared bits ----------------------------------------------------------------------------

    private fun status(root: FlowLayout, text: String, color: Int) {
        label(root, "status").text(Component.literal(text)).color(Color.ofArgb(color))
    }

    /** Small print under the section; dropped when compact, where the rows need the height more. */
    private fun hint(root: FlowLayout, text: String) {
        val hint = label(root, "hint")
        if (compact) detach(root, hint) else hint.text(Component.literal(text))
    }

    /** A bottom-row action with an icon and a trailing "›". */
    private fun actionButton(root: FlowLayout, text: String, icon: CosmeticCatalog.Art, width: Int, onPress: () -> Unit): ButtonComponent =
        JukzSurface.iconButton(text, icon, trailing = HubIcons.ARROW, onPress = onPress).also {
            it.sizing(Sizing.fixed(width), Sizing.fixed(22))
            root.childById(FlowLayout::class.java, "buttons").child(it)
        }

    private fun retryButton(root: FlowLayout) {
        addButton(root, "buttons", Component.literal("Try again"), width = 64) { Cosmetics.ensureSignedIn(force = true); rebuild() }
            .renderer(JukzSurface.primary())
    }

    private fun section(content: FlowLayout, id: String, text: String) {
        content.child(ui!!.expandTemplate(LabelComponent::class.java, "section", mapOf("id" to "section-$id")).text(Component.literal(text)))
    }

    private fun note(content: FlowLayout, text: String): LabelComponent =
        UIComponents.label(Component.literal(text)).color(Color.ofArgb(COLOR_SUBTLE)).maxWidth(mainWidth - 12).also { content.child(it) }

    private fun button(text: String, width: Int, onPress: () -> Unit): ButtonComponent =
        UIComponents.button(Component.literal(text)) { onPress() }.also { it.horizontalSizing(Sizing.fixed(width)) }

    override fun tick() {
        super.tick()
        if (!requested && Cosmetics.sessionToken() != null) { // signed in (now or just now): load the account
            requested = true
            loading = true
            CloudWorlds.refreshAccount { loading = false; minecraft?.execute { rebuild() } }
        }
        val now = listOf(section, tab, MojangSkins.account, skinBusy, skinNote, Cosmetics.account, Cosmetics.catalog, loading, saveError, CloudWorlds.summary,
            CloudWorlds.worlds, CloudWorlds.worlds?.map(CloudWorlds::state), CloudWorlds.uploads.toMap())
        if (now != shown) {
            shown = now
            rebuild()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = true

    /** Back where you came from; a world list comes back fresh, so worlds brought meanwhile show up. */
    override fun onClose() {
        dropPending()
        minecraft?.openScreen(if (parent is SelectWorldScreen) SelectWorldScreen(TitleScreen()) else parent)
    }

    // ---- components -----------------------------------------------------------------------------

    /**
     * Slides [target] in from the right after a section change: an invisible component whose draw (once
     * per frame, on every version) eases the target's left margin from [SLIDE_PX] to 0. It also hands the
     * screen the pointer each frame ([onFrame]), for the try-on hover.
     */
    private class SlideIn(private val target: FlowLayout, private val since: Long, private val onFrame: (Int, Int) -> Unit) : BaseUIComponent() {
        private var last = -1

        init {
            sizing(Sizing.fixed(1), Sizing.fixed(1)) // owo skips drawing a 0×0 component
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            onFrame(mouseX, mouseY)
            val t = ((System.currentTimeMillis() - since) / SLIDE_MS.toDouble()).coerceIn(0.0, 1.0)
            val eased = 1 - (1 - t) * (1 - t) * (1 - t) // ease-out cubic
            val left = (SLIDE_PX * (1 - eased)).toInt()
            if (left != last) {
                last = left
                target.margins(Insets.left(left))
            }
        }
    }

    /** The 3D you: sways, turns when dragged, and hops when you put something on. */
    private object View {
        var dragYaw = 0f
        var dragged = false
        var zoom = 1f
        const val MIN_ZOOM = 0.45f
    }

    class PreviewComponent(private val preview: PlayerPreview, private val loadout: () -> Map<Slot, String>) : BaseUIComponent() {
        private var hopAt = 0L

        init {
            sizing(Sizing.fill(100), Sizing.fill(100))
            cursorStyle(CursorStyle.MOVE)
        }

        /** Keep the turn and a running hop across a rebuild. */
        fun carryOver(old: PreviewComponent) {
            hopAt = old.hopAt
        }

        fun hop() {
            hopAt = System.currentTimeMillis()
        }

        /** A stepped glowing ellipse you stand on. */
        private fun platform(c: OwoUIGraphics, cx: Int, cy: Int) {
            val rows = intArrayOf(18, 26, 30, 30, 26, 18)
            rows.forEachIndexed { i, half -> c.fill(cx - half, cy - 3 + i, cx + half, cy - 2 + i, if (i in 2..3) 0x883E6FD8.toInt() else 0x552B56B0) }
            c.fill(cx - 20, cy - 1, cx + 20, cy + 1, 0x44101C3C)
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val sway = if (View.dragged) 0f else 20f * sin(System.currentTimeMillis() / 1600.0).toFloat()
            val t = (System.currentTimeMillis() - hopAt) / HOP_MS.toDouble()
            val lift = if (t in 0.0..1.0) (6 * sin(t * PI)).toInt() else 0
            platform(context, x + width / 2, y + height - 6)
            preview.draw(context, x, y - lift, width, height, -25f + sway + View.dragYaw, loadout(), View.zoom)
        }

        //? if >=1.21.11 {
        /*override fun onMouseDrag(click: net.minecraft.client.input.MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        *///?} else {
        override fun onMouseDrag(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double, button: Int): Boolean {
        //?}
            View.dragYaw += deltaX.toFloat() * 2f
            View.dragged = true
            return true
        }

        override fun onMouseScroll(mouseX: Double, mouseY: Double, amount: Double): Boolean {
            View.zoom = (View.zoom + amount.toFloat() * 0.1f).coerceIn(View.MIN_ZOOM, 1f)
            return true
        }

        override fun canFocus(source: FocusSource): Boolean = source == FocusSource.MOUSE_CLICK
    }

    /** An item at [px] square: a badge's ASCII art, or a 3D piece turned to show its front and top. */
    class ItemIcon(private val item: CosmeticCatalog.Item, private val px: Int, private val dimmed: Boolean = false) : BaseUIComponent() {
        init {
            sizing(Sizing.fixed(px), Sizing.fixed(px))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val opacity = if (dimmed) 0.35f else 1f
            if (item.art != null) BadgeRenderer.draw(context, item, x, y, px, opacity)
            else item.model?.let { ModelIcon.draw(context, it, x, y, px, opacity, fromBehind = item.slot == Slot.BACK) }
        }
    }

    companion object {
        const val KOFI_URL = "https://ko-fi.com/nobmz"
        private const val CARD_GAP = 6
        /** Scaled GUI width under which the hub goes compact (GUI scale 4 on 1080p is 480). */
        private const val COMPACT_BELOW = 600
        private const val SLIDE_MS = 220L
        private const val SLIDE_PX = 24
        private const val HOP_MS = 260L
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
    }
}
