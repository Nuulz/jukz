package dev.jukz.client

/**
 * Holds the live guest join session — the core `JoinController` whose reader watches the control
 * channel for a host handoff/drop — so it can be torn down when the guest leaves the host's world.
 * Without this the controller outlives the visit: a later host-leave would fire `onHostLost` and pop
 * a stale "Host now" prompt at someone who already went back to the menu.
 *
 * [handoffOffered] distinguishes the disconnect that is *part of* a handoff (the host left; we leave
 * the world on purpose to show the takeover prompt) from a normal leave, so the disconnect hook only
 * tears the session down in the latter case.
 */
object GuestSession {

    @Volatile private var controller: AutoCloseable? = null

    @Volatile var handoffOffered: Boolean = false
        private set

    val isActive: Boolean get() = controller != null

    /** Record the controller for a freshly-connected guest. Closes any prior session first. */
    fun install(controller: AutoCloseable) {
        leave()
        this.controller = controller
        handoffOffered = false
    }

    /** The host announced it is leaving: the disconnect that follows is the handoff, not a quit. */
    fun markHandoffOffered() {
        handoffOffered = true
    }

    /** Close and forget the session (the guest left, or the handoff resolved). Idempotent. */
    fun leave() {
        runCatching { controller?.close() }
        controller = null
        handoffOffered = false
    }
}
