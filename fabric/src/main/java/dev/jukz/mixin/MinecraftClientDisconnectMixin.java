package dev.jukz.mixin;

import dev.jukz.client.GuestSession;
import dev.jukz.client.gui.HostLeavingScreen;
import dev.jukz.client.gui.UploadingWorldScreen;
import dev.jukz.runtime.GhostUpload;
import dev.jukz.runtime.HostSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps the screen {@code MinecraftClient.disconnect} is about to show, at the source, for the two jukz
 * handoff cases — so the misleading vanilla screen never even renders one frame. We hook the two-arg
 * {@code disconnect(Screen, boolean)} because it is the single choke point: the host's voluntary quit
 * reaches it via the one-arg overload, and a guest's lost connection calls it directly from
 * {@code ClientCommonNetworkHandler.onDisconnected} (passing a {@link DisconnectedScreen}).
 *
 * <ul>
 *   <li><b>Host with guests closing the world:</b> the {@code SERVER_STOPPING} hook hands the world off
 *       (arming + serving the snapshot, blocking briefly). The vanilla "Saving world" wait screen is
 *       misleading — show that the world is being handed to the next host instead.</li>
 *   <li><b>Guest whose host dropped:</b> vanilla is about to show its red "Connection lost" screen.
 *       Replace it with the jukz wait so the takeover prompt can take over without that screen flashing.
 *       Gated to a genuine connection loss (a {@link DisconnectedScreen}) so a voluntary leave — which
 *       passes a title/progress screen — keeps the vanilla behaviour.</li>
 * </ul>
 *
 * <p>require = 0: purely cosmetic, so a yarn-mapping drift on {@code disconnect} degrades (vanilla
 * screen) rather than crashing the quit. The guest path additionally has the reactive swap in
 * {@code JukzClient} as a belt-and-suspenders fallback if this injection ever fails to apply.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientDisconnectMixin {

    @ModifyVariable(
        method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private Screen jukz$handoffScreen(Screen original) {
        if (GhostUpload.INSTANCE.isArmed()) {
            return new UploadingWorldScreen();
        }
        if (HostSession.INSTANCE.isHosting() && HostSession.INSTANCE.connectedGuestCount() > 0) {
            return new MessageScreen(Text.literal("Handing the world to the next host…"));
        }
        if (original instanceof DisconnectedScreen && GuestSession.INSTANCE.recentlyEngaged()) {
            return new HostLeavingScreen();
        }
        return original;
    }
}
