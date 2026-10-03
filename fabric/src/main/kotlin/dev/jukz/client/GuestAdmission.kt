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
 * Now: online mode whenever the host has a real (Microsoft/Mojang) account, unless the player opted
 * into offline guests. In offline mode two names are refused at login: the host's, and anyone already
 * in the world. (Taking the name of someone who is offline can't be detected — that's what online
 * mode is for.)
 */
object GuestAdmission {

    /** Whether the integrated server should verify guests with Mojang. */
    fun onlineMode(hostHasPremiumAccount: Boolean, offlineGuestsOptIn: Boolean): Boolean =
        hostHasPremiumAccount && !offlineGuestsOptIn

    /**
     * Why a remote login must be refused, or null to let vanilla decide. [isLocal] is the host's own
     * in-process connection, which is never refused.
     */
    fun refusal(
        onlineMode: Boolean,
        isLocal: Boolean,
        name: String,
        hostName: String?,
        namesInWorld: Collection<String>,
    ): String? {
        if (onlineMode || isLocal) return null
        if (hostName != null && name.equals(hostName, ignoreCase = true)) return NAME_TAKEN
        if (namesInWorld.any { it.equals(name, ignoreCase = true) }) return NAME_TAKEN
        return null
    }

    const val NAME_TAKEN = "That name is already in use in this world."
}
