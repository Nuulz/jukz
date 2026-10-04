package dev.jukz.client.gui

import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import dev.jukz.cosmetics.CosmeticCatalog.Availability
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import dev.jukz.cosmetics.Cosmetics
import dev.jukz.cosmetics.Cosmetics.Account
import dev.jukz.cosmetics.ModelIcon
import dev.jukz.cosmetics.PlayerPreview
import io.wispforest.owo.ui.base.BaseComponent
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.Containers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIDrawContext
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import net.minecraft.client.gui.screen.ConfirmLinkScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Dress up: one tab per slot (tab-list badge, hat, face, back), a card per catalog item of that slot and
 * a 3D preview of you wearing your picks (drag it to turn). Clicking an owned card puts it on; "None"
 * empties the slot. Locked items (paid or granted ones you don't have) are faded and show their price —
 * today every item is free, so that state only appears once something is sold. Also links Ko-fi.
 *
 * Layout: `assets/jukz/owo_ui/cosmetics.xml` (style from `theme.xml`).
 */
class CosmeticsScreen(private val parent: Screen?) : JukzUiScreen("cosmetics") {

    private var tab = Slot.HAT
    private var shown: Triple<Account, CosmeticCatalog, Slot>? = null
    @Volatile private var saveError: String? = null
    @Volatile private var saving = false

    override fun build(root: FlowLayout) {
        Cosmetics.ensureSignedIn()
        val account = Cosmetics.account
        val catalog = Cosmetics.catalog
        shown = Triple(account, catalog, tab)
        val signedIn = account as? Account.SignedIn

        label(root, "title").text(Text.literal("Cosmetics"))
        val (message, color) = accountLine(account)
        label(root, "account").text(Text.literal(message)).color(Color.ofArgb(color))
        accent(root, if (account is Account.Failed) ACCENT_ERROR else ACCENT_INFO)

        buildPreview(root)

        val tabs = root.childById(FlowLayout::class.java, "tabs")
        Slot.entries.forEach { slot ->
            val button = themed(ButtonComponent::class.java, "button", mapOf("id" to "tab-${slot.key}", "width" to "$TAB_WIDTH"))
            button.message = Text.literal(slot.label)
            button.active = slot != tab // the open tab reads as pressed
            button.onPress {
                tab = slot
                rebuild()
            }
            tabs.child(button)
        }

        val grid = root.childById(FlowLayout::class.java, "grid")
        val cards = listOf<FlowLayout>(noneCard(signedIn)) + catalog.ofSlot(tab).map { card(it, signedIn) }
        cards.chunked(PER_ROW).forEach { row ->
            grid.child(Containers.horizontalFlow(Sizing.content(), Sizing.content()).gap(4).also { r -> row.forEach(r::child) })
        }

        label(root, "hint").text(Text.literal(
            if (tab == Slot.BADGE) "Your badge sits left of your name in the tab list, for everyone running jukz."
            else "Everyone running jukz sees what you wear. All cosmetics are free for now."
        ))

        if (account is Account.Failed) {
            addButton(root, "buttons", Text.literal("Try again"), width = 90) {
                Cosmetics.ensureSignedIn(force = true)
                rebuild()
            }
        }
        addButton(root, "buttons", Text.literal("Support jukz (Ko-fi)"), width = 120) {
            ConfirmLinkScreen.open(this, KOFI_URL)
        }
        addButton(root, "buttons", Text.literal("Done"), width = 80) { client?.setScreen(parent) }
    }

    /** You, slowly turning (drag to turn yourself), wearing what you picked; your tab-list line under it. */
    private fun buildPreview(root: FlowLayout) {
        val profile = client?.gameProfile ?: return
        root.childById(FlowLayout::class.java, "model").child(PreviewComponent(PlayerPreview(profile), profile.id))

        val line = root.childById(FlowLayout::class.java, "preview")
        Cosmetics.wearing(Slot.BADGE)?.let { line.child(ItemIcon(it, 8)) }
        line.child(Components.label(Text.literal(profile.name)))
    }

    private class PreviewComponent(private val preview: PlayerPreview, private val player: java.util.UUID) : BaseComponent() {
        private var dragYaw = 0f

        init {
            sizing(Sizing.fixed(90), Sizing.fixed(118))
            cursorStyle(CursorStyle.MOVE)
        }

        override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val sway = 20f * kotlin.math.sin(System.currentTimeMillis() / 1600.0).toFloat()
            preview.draw(context, x, y, width, height, -25f + sway + dragYaw, Cosmetics.loadoutFor(player))
        }

        override fun onMouseDrag(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double, button: Int): Boolean {
            dragYaw += deltaX.toFloat() * 2f
            return true
        }

        override fun canFocus(source: io.wispforest.owo.ui.core.Component.FocusSource): Boolean = source == io.wispforest.owo.ui.core.Component.FocusSource.MOUSE_CLICK
    }

    private fun noneCard(me: Account.SignedIn?): FlowLayout {
        val card = ui!!.expandTemplate(FlowLayout::class.java, "item-card", mapOf("id" to "none"))
        val wearingNothing = me != null && Cosmetics.wearing(tab) == null
        card.childById(FlowLayout::class.java, "icon-none").child(
            Components.label(Text.literal("None")).color(Color.ofArgb(COLOR_SUBTLE))
        )
        card.childById(LabelComponent::class.java, "state-none")
            .text(Text.literal(if (wearingNothing) "Wearing" else ""))
            .color(Color.ofArgb(COLOR_LIVE))
        wire(card, if (wearingNothing) COLOR_LIVE else OUTLINE, clickable = me != null && !wearingNothing) { save(Cosmetics.NO_BADGE) }
        card.tooltip(Text.literal(if (tab == Slot.BADGE) "Hide your badge" else "Wear nothing here"))
        return card
    }

    private fun card(item: CosmeticCatalog.Item, me: Account.SignedIn?): FlowLayout {
        val card = ui!!.expandTemplate(FlowLayout::class.java, "item-card", mapOf("id" to item.id))
        val owned = me != null && item.id in me.owned
        val wearing = me != null && Cosmetics.wearing(item.slot)?.id == item.id
        val locked = me != null && !owned

        card.childById(FlowLayout::class.java, "icon-${item.id}").child(ItemIcon(item, ICON, dimmed = locked))
        val (state, stateColor) = when {
            wearing -> "Wearing" to COLOR_LIVE
            locked -> (item.price?.label() ?: if (item.availability == Availability.GRANT) "Special" else "Locked") to ACCENT_ACTION
            item.availability == Availability.GRANT -> "Special" to ACCENT_ACTION
            else -> "Free" to COLOR_SUBTLE
        }
        card.childById(LabelComponent::class.java, "state-${item.id}").text(Text.literal(state)).color(Color.ofArgb(stateColor))
        card.tooltip(listOf(Text.literal(item.name), Text.literal(item.description).withColor(COLOR_SUBTLE)))
        wire(card, if (wearing) COLOR_LIVE else OUTLINE, clickable = owned && !wearing) { save(item.id) }
        return card
    }

    private fun wire(card: FlowLayout, outline: Int, clickable: Boolean, onPick: () -> Unit) {
        card.surface(cardSurface(outline))
        if (!clickable) return
        card.cursorStyle(CursorStyle.HAND)
        card.mouseEnter().subscribe { card.surface(cardSurface(ACCENT_INFO)) }
        card.mouseLeave().subscribe { card.surface(cardSurface(outline)) }
        card.mouseDown().subscribe { _, _, _ -> onPick(); true }
    }

    private fun save(item: String) {
        if (saving) return
        saving = true
        saveError = null
        Cosmetics.equip(tab, item) { error ->
            saving = false
            saveError = error
            client?.execute { rebuild() }
        }
    }

    private fun accountLine(account: Account): Pair<String, Int> = when {
        !Cosmetics.enabled -> "Cosmetics need the rendezvous server (rendezvous.url is \"none\")." to ACCENT_ERROR
        saveError != null -> "Couldn't save: $saveError" to ACCENT_ERROR
        account is Account.Failed -> "Couldn't sign in: ${account.reason}" to ACCENT_ERROR
        account is Account.SignedIn -> "Click something to wear it." to COLOR_SUBTLE
        else -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
    }

    override fun tick() {
        super.tick()
        if (Triple(Cosmetics.account, Cosmetics.catalog, tab) != shown) rebuild() // signed in, or the live catalog arrived
    }

    override fun shouldCloseOnEsc(): Boolean = true

    /** An item at [px] square: a badge's ASCII art, or a 3D piece turned to show its front and top. */
    class ItemIcon(private val item: CosmeticCatalog.Item, private val px: Int, private val dimmed: Boolean = false) : BaseComponent() {
        init {
            sizing(Sizing.fixed(px), Sizing.fixed(px))
        }

        override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val opacity = if (dimmed) 0.35f else 1f
            if (item.art != null) BadgeRenderer.draw(context, item, x, y, px, opacity)
            else item.model?.let { ModelIcon.draw(context, it, x, y, px, opacity, fromBehind = item.slot == Slot.BACK) }
        }
    }

    companion object {
        private const val PER_ROW = 5
        private const val ICON = 28
        private const val TAB_WIDTH = 66
        private const val OUTLINE = 0xFF2A3B5C.toInt()
        const val KOFI_URL = "https://ko-fi.com/nobmz"

        private fun cardSurface(outline: Int): Surface = Surface.flat(0x66101828).and(Surface.outline(outline))
    }
}
