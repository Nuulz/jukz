package dev.jukz.core.host

/**
 * The frontier between the jukz host flow and the host game. Opening the world to the network is a
 * Minecraft concern (`IntegratedServer.openToLan`), so [HostController] only asks for a bound port
 * through this seam and stays free of Minecraft types. The real adapter lives in `fabric`; tests
 * supply a fake that returns a fixed port.
 */
fun interface LanOpener {
    /** Open the local world to the network and return the bound port, or null if it failed. */
    fun open(): Int?
}
