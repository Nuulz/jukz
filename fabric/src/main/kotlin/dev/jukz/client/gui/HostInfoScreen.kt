package dev.jukz.client.gui

import dev.jukz.client.HostCoordinator
import dev.jukz.core.host.HostStatus
import dev.jukz.core.model.ClaimToken
import dev.jukz.runtime.HostSession
import io.wispforest.owo.ui.component.Components
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Insets
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Host-side status panel, reached from the pause-menu button once a world is shared. Shows the
 * shareable code (copyable), the world's identity (UUID + fencing generation), the endpoint guests
 * dial, and a live self-check — does the registry still hold our record under our token? — so the
 * host can confirm at a glance that the share is healthy. The check runs off the render thread (it
 * is a registry/network read), re-runs with Refresh, and re-polls on its own while not yet live. The
 * panel also rebuilds itself when the hosted record changes underneath it — closing or reopening
 * access withdraws/announces off-thread, so the first build often sees the state before the switch.
 *
 * Layout: `assets/jukz/owo_ui/host_info.xml` (style from `theme.xml`).
 */
class HostInfoScreen(private val parent: Screen?) : JukzUiScreen("host_info") {

    private val record get() = HostSession.record

    @Volatile private var status: HostStatus? = null
    @Volatile private var checking = true
    private var statusLabel: LabelComponent? = null
    private var shownStatus: Pair<String, Int>? = null
    private var ticksSinceCheck = 0
    private var builtFor: ClaimToken? = null // the hosting session this panel shows (null = not hosting)

    override fun build(root: FlowLayout) {
        label(root, "title").text(Text.literal("World info"))
        val rows = root.childById(FlowLayout::class.java, "rows")
        val rec = record
        builtFor = rec?.token
        if (rec == null) {
            rows.child(Components.label(Text.literal("Not hosting this world.")).color(Color.ofArgb(COLOR_SUBTLE)))
        } else {
            listOfNotNull(
                "Share code" to rec.worldId.shortCode(),
                "World UUID" to rec.worldId.uuid.toString(),
                "Generation" to rec.token.hostGeneration.toString(),
                "Endpoints" to rec.endpoints.joinToString(", ") { it.format() },
                // Guests that can't reach the endpoints (CGNAT / no UPnP) come in through this relay session.
                rec.relay?.let { "Relay" to "via rendezvous · ${it.sessionId.take(8)}" },
            ).forEachIndexed { i, (label, value) -> rows.child(infoRow(label, value, "row-$i").first) }
            val (statusRow, label) = infoRow("Status", "", "row-status")
            statusRow.margins(Insets.top(6))
            rows.child(statusRow)
            statusLabel = label
            shownStatus = null // a rebuilt label starts blank
            showStatus()
        }

        // Access toggle (F4-D): open/close the world to guests. Reads the per-world flag for its label.
        wireButton(root, "access-button", accessLabel()) { toggleAccess() }
        addButton(root, "buttons", Text.literal("Copy code"), width = 100) {
            record?.let { client?.keyboard?.clipboard = it.worldId.shortCode() }
        }
        addButton(root, "buttons", Text.literal("Refresh"), width = 100) { refresh() }
        addButton(root, "buttons", Text.literal("Done"), width = 100) { client?.setScreen(parent) }

        // Self-heal: every jukz world is auto-hosted on open, but if that hasn't taken (or failed),
        // kick it off now so opening this panel always ends with the world online.
        if (!HostSession.isHosting) {
            client?.server?.let { HostCoordinator.autoHost(it) }
        }
        refresh()
    }

    /** One "label: value" row from the model's `info-row` template; returns the row and its value label. */
    private fun infoRow(label: String, value: String, id: String): Pair<FlowLayout, LabelComponent> {
        val row = themed(FlowLayout::class.java, "info-row", mapOf("label" to label, "id" to id))
        val valueLabel = row.childById(LabelComponent::class.java, id)
        valueLabel.text(Text.literal(value))
        return row to valueLabel
    }


    /** "Access: Open" / "Access: Closed", read from the per-world flag at build time. */
    private fun accessLabel(): Text {
        val server = client?.server
        val closed = server != null && HostCoordinator.isAccessDisabled(server)
        return Text.literal(if (closed) "Access: Closed" else "Access: Open")
    }

    /** Flip access for the loaded world, then rebuild so the label + status refresh. */
    private fun toggleAccess() {
        val server = client?.server ?: return
        if (HostCoordinator.isAccessDisabled(server)) HostCoordinator.enableAccess(server)
        else HostCoordinator.disableAccess(server)
        rebuild()
    }

    /** Re-poll the live status without blocking the render thread. */
    private fun refresh() {
        checking = true
        status = null
        ticksSinceCheck = 0
        Thread {
            status = runCatching { HostSession.currentStatus() }.getOrNull()
            checking = false
        }.apply { isDaemon = true; name = "jukz-host-status" }.start()
    }

    override fun tick() {
        super.tick()
        if (record?.token != builtFor) { // heartbeats replace the record but keep its token
            rebuild() // hosting started/stopped/re-announced since this panel was built
            return
        }
        showStatus()
        ticksSinceCheck++
        if (!checking && status?.live != true && HostSession.isHosting && ticksSinceCheck >= REPOLL_TICKS) refresh()
    }

    /** Push the latest self-check into the status label (only when it changed). */
    private fun showStatus() {
        val label = statusLabel ?: return
        val current = when {
            checking -> "checking…" to COLOR_SUBTLE
            status?.live == true -> "live · heartbeat #${status!!.heartbeatSeq}" to COLOR_LIVE
            else -> "not announced" to ACCENT_ERROR
        }
        if (current == shownStatus) return
        shownStatus = current
        label.text(Text.literal(current.first)).color(Color.ofArgb(current.second))
    }

    override fun shouldCloseOnEsc(): Boolean = true

    companion object {
        private const val REPOLL_TICKS = 60 // 3 s
    }
}
