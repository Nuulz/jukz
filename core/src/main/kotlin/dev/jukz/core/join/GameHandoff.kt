package dev.jukz.core.join

/**
 * The frontier between the jukz join flow and the host game. Once the controller has a local relay
 * port that fronts the real host, it asks the game to connect there as if to a normal server.
 * Kept free of Minecraft types so the controller stays in `core`; the real adapter lives in `fabric`.
 */
interface GameHandoff {
    /** Connect the vanilla client to [host]:[port] (the loopback relay). */
    fun connect(host: String, port: Int)
}
