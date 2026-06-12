package dev.jukz.client

/**
 * Holds the live guest join session — the core `JoinController` whose reader watches the control
 * channel for a host handoff/drop — and tracks whether the player is still meaningfully engaged with
 * it.
 *
 * Crucially it does NOT close the controller when the game connection drops: when a host leaves, the
 * guest's DATA connection breaks at roughly the same time the host pushes its `HostLeaving` over the
 * (separate) control channel, and tearing the controller down on that drop would also close the
 * control channel — which the host counts to decide whether to hand off, so the handoff would never
 * fire. Instead the drop is just timestamped; [recentlyEngaged] then lets the handoff logic tell a
 * genuine in-visit host-leave from a stale watcher left over from a world the player left a while ago.
 */
object GuestSession {

    /** A host-leave within this long of the game connection dropping is treated as part of the visit. */
    private const val ENGAGED_WINDOW_MS = 60_000L

    @Volatile private var controller: AutoCloseable? = null
    @Volatile private var disconnectedAt: Long = 0L // 0 = still connected

    val isActive: Boolean get() = controller != null

    /** Record the controller for a freshly-connected guest. Closes any prior session first. */
    fun install(controller: AutoCloseable) {
        leave()
        this.controller = controller
        disconnectedAt = 0L
    }

    /** The game connection dropped. Timestamp it; do NOT close the controller (see the class doc). */
    fun markDisconnected() {
        if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
    }

    /**
     * True while the guest is still connected, or dropped only recently — i.e. a host-leave right now
     * belongs to THIS visit, not a stale watcher from a world the player left long ago.
     */
    fun recentlyEngaged(): Boolean =
        isActive && (disconnectedAt == 0L || System.currentTimeMillis() - disconnectedAt <= ENGAGED_WINDOW_MS)

    /** Close and forget the session (the guest moved on, or the handoff resolved). Idempotent. */
    fun leave() {
        runCatching { controller?.close() }
        controller = null
        disconnectedAt = 0L
    }
}
