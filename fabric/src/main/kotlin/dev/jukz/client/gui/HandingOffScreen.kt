package dev.jukz.client.gui

import net.minecraft.network.chat.Component

/**
 * Shown to a host with guests while it closes the world: the SERVER_STOPPING hook is arming and serving
 * the snapshot (blocking briefly), and vanilla's "Saving world" would hide that the world is being
 * handed to the next host. Installed by `MinecraftDisconnectMixin`.
 */
class HandingOffScreen : JukzStatusScreen(
    Component.literal("Handing the world to the next host…"),
    Component.literal("A guest is taking over so the world stays online."),
    accentColor = ACCENT_INFO,
)
