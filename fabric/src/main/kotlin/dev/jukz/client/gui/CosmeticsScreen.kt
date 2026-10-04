package dev.jukz.client.gui

import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import dev.jukz.cosmetics.CosmeticCatalog.Availability
import dev.jukz.cosmetics.Cosmetics
import dev.jukz.cosmetics.Cosmetics.Account
import io.wispforest.owo.ui.base.BaseComponent
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
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
 * Pick the badge shown next to your name in the tab list. Every catalog item gets a card; clicking an
 * owned one equips it. Locked items (paid or granted ones you don't have) are dimmed and show their
 * price — today every item is free, so that state only appears once something is sold. Also the
 * in-game home of the Ko-fi link.
 *
 * Layout: `assets/jukz/owo_ui/cosmetics.xml` (style from `theme.xml`).
 */
class CosmeticsScreen(private val parent: Screen?) : JukzUiScreen("cosmetics") {

    private var shown: Pair<Account, CosmeticCatalog>? = null
    @Volatile private var saveError: String? = null
    @Volatile private var saving = false

    override fun build(root: FlowLayout) {
        Cosmetics.ensureSignedIn()
        val account = Cosmetics.account
        val catalog = Cosmetics.catalog
        shown = account to catalog
        val signedIn = account as? Account.SignedIn

        label(root, "title").text(Text.literal("Cosmetics"))
        val (message, color) = accountLine(account)
        label(root, "account").text(Text.literal(message)).color(Color.ofArgb(color))
        accent(root, if (account is Account.Failed) ACCENT_ERROR else ACCENT_INFO)

        val preview = root.childById(FlowLayout::class.java, "preview")
        Cosmetics.myBadge()?.let { preview.child(BadgeComponent(it, 8)) }
        preview.child(Components.label(Text.literal(client?.session?.username ?: "You")))

        val grid = root.childById(FlowLayout::class.java, "grid")
        catalog.items.chunked(PER_ROW).forEach { items ->
            val row = Containers.row()
            items.forEach { row.child(card(it, signedIn)) }
            grid.child(row)
        }

        label(root, "hint").text(Text.literal(
            "Everyone running jukz sees your badge in the tab list. All badges are free for now."
        ))

        if (signedIn != null) {
            val hidden = signedIn.equipped == Cosmetics.NO_BADGE
            addButton(root, "buttons", Text.literal(if (hidden) "Show my badge" else "Hide my badge"), width = 100) {
                save(if (hidden) catalog.defaultBadge else Cosmetics.NO_BADGE)
            }
        } else if (account is Account.Failed) {
            addButton(root, "buttons", Text.literal("Try again"), width = 100) {
                Cosmetics.ensureSignedIn()
                rebuild()
            }
        }
        addButton(root, "buttons", Text.literal("Support jukz (Ko-fi)"), width = 120) {
            ConfirmLinkScreen.open(this, KOFI_URL)
        }
        addButton(root, "buttons", Text.literal("Done"), width = 80) { client?.setScreen(parent) }
    }

    private fun card(badge: CosmeticCatalog.Badge, me: Account.SignedIn?): FlowLayout {
        val card = ui!!.expandTemplate(FlowLayout::class.java, "badge-card", mapOf("id" to badge.id, "name" to badge.name))
        val owned = me != null && badge.id in me.owned
        val equipped = me != null && Cosmetics.myBadge()?.id == badge.id
        val locked = me != null && !owned

        card.childById(FlowLayout::class.java, "art-${badge.id}").child(BadgeComponent(badge, 32, dimmed = locked))
        val (state, stateColor) = when {
            equipped -> "Equipped" to COLOR_LIVE
            locked -> (badge.price?.label() ?: if (badge.availability == Availability.GRANT) "Special" else "Locked") to ACCENT_ACTION
            badge.availability == Availability.GRANT -> "Special" to ACCENT_ACTION
            else -> "Free" to COLOR_SUBTLE
        }
        card.childById(LabelComponent::class.java, "state-${badge.id}").text(Text.literal(state)).color(Color.ofArgb(stateColor))

        val outline = if (equipped) COLOR_LIVE else OUTLINE
        card.surface(cardSurface(outline))
        card.tooltip(listOf(Text.literal(badge.name), Text.literal(badge.description).withColor(COLOR_SUBTLE)))
        if (owned && !equipped) {
            card.cursorStyle(CursorStyle.HAND)
            card.mouseEnter().subscribe { card.surface(cardSurface(ACCENT_INFO)) }
            card.mouseLeave().subscribe { card.surface(cardSurface(outline)) }
            card.mouseDown().subscribe { _, _, _ -> save(badge.id); true }
        }
        return card
    }

    private fun save(item: String) {
        if (saving) return
        saving = true
        saveError = null
        Cosmetics.equip(item) { error ->
            saving = false
            saveError = error
            client?.execute { rebuild() }
        }
    }

    private fun accountLine(account: Account): Pair<String, Int> = when {
        !Cosmetics.enabled -> "Cosmetics need the rendezvous server (rendezvous.url is \"none\")." to ACCENT_ERROR
        saveError != null -> "Couldn't save: $saveError" to ACCENT_ERROR
        account is Account.Failed -> "Couldn't sign in: ${account.reason}" to ACCENT_ERROR
        account is Account.SignedIn -> "Pick the badge shown next to your name." to COLOR_SUBTLE
        else -> "Signing in with your Minecraft account…" to COLOR_SUBTLE
    }

    override fun tick() {
        super.tick()
        val current = Cosmetics.account to Cosmetics.catalog
        if (current != shown) rebuild() // signed in, or the live catalog arrived
    }

    override fun shouldCloseOnEsc(): Boolean = true

    /** A badge's ASCII art at [px] square, inside owo layouts. */
    class BadgeComponent(private val badge: CosmeticCatalog.Badge, private val px: Int, private val dimmed: Boolean = false) : BaseComponent() {
        init {
            sizing(Sizing.fixed(px), Sizing.fixed(px))
        }

        override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            BadgeRenderer.draw(context, badge, x, y, px, opacity = if (dimmed) 0.35f else 1f)
        }
    }

    private object Containers {
        fun row(): FlowLayout = io.wispforest.owo.ui.container.Containers.horizontalFlow(Sizing.content(), Sizing.content()).gap(4) as FlowLayout
    }

    companion object {
        private const val PER_ROW = 5
        private const val OUTLINE = 0xFF2A3B5C.toInt()
        const val KOFI_URL = "https://ko-fi.com/nobmz"

        private fun cardSurface(outline: Int): Surface = Surface.flat(0x66101828).and(Surface.outline(outline))
    }
}
