package dev.jukz.client

/**
 * Who may join a jukz-hosted world, and as whom. Minecraft-free so it is unit-tested.
 *
 * Guests reach the host through jukz (direct or the relay), but the login is vanilla's, so the
 * online-mode check works exactly as on a normal server: the relay only moves bytes. jukz used to put
 * every hosted world in offline mode — only because dev accounts have no session — and offline mode
 * can't tell who anyone is: with the world code, a guest could join under another player's name. If
 * that player was online, vanilla even logged the newcomer in and kicked them ("You logged in from
 * another location"), handing over their character; if not, the impostor simply got their inventory.
 *
 * Now: hybrid. The host always asks Mojang (vanilla's online handshake), so a guest with a real
 * account joins as their real profile — same UUID, inventory and skin whoever hosts. A guest without
 * one fails that check; the integrated server then lets them in under the offline UUID for their name
 * (vanilla's singleplayer fallback, reached because every jukz client carries on past a failed check —
 * see ClientHandshakeMixin). Those unverified guests are only admitted when the host has no real
 * account itself or opted into offline guests, and never under the host's name or the name of anyone
 * already in the world. (An unverified guest can't take a verified player's character: the UUIDs
 * differ.)
 */
object GuestAdmission {

    /**
     * Why a remote login must be refused, or null to let vanilla decide. [isLocal] is the host's own
     * in-process connection, which is never refused; [verified] is whether Mojang vouched for the guest.
     */
    fun refusal(
        verified: Boolean,
        allowUnverified: Boolean,
        isLocal: Boolean,
        name: String,
        hostName: String?,
        namesInWorld: Collection<String>,
    ): String? {
        if (verified || isLocal) return null
        if (!allowUnverified) return ACCOUNT_REQUIRED
        if (hostName != null && name.equals(hostName, ignoreCase = true)) return NAME_TAKEN
        if (namesInWorld.any { it.equals(name, ignoreCase = true) }) return NAME_TAKEN
        return null
    }

    /** Whether guests without a Microsoft account may join: the host has none either, or opted in. */
    fun allowUnverified(hostHasPremiumAccount: Boolean, offlineGuestsOptIn: Boolean): Boolean =
        !hostHasPremiumAccount || offlineGuestsOptIn

    @Volatile var allowUnverifiedGuests: Boolean = true

    /** Told (on the server thread) when a guest without an account was turned away, so the host hears of it. */
    @Volatile var onRefusedUnverified: (name: String) -> Unit = {}
    private val lastNotice = HashMap<String, Long>()

    /** [onRefusedUnverified], at most once a minute per name (a guest retrying shouldn't spam the host). */
    fun refusedUnverified(name: String, now: Long = System.currentTimeMillis()) {
        val key = name.lowercase()
        synchronized(lastNotice) {
            if (now - (lastNotice[key] ?: Long.MIN_VALUE / 2) < 60_000) return
            lastNotice[key] = now
        }
        onRefusedUnverified(name)
    }

    const val NAME_TAKEN = "That name is already in use in this world."
    const val ACCOUNT_REQUIRED = "This world only accepts players signed in with a Microsoft account."
}
