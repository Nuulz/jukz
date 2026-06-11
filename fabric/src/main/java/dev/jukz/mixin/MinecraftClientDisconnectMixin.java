package dev.jukz.mixin;

import dev.jukz.runtime.HostSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When a jukz host with connected guests closes its world, jukz hands the world off to a guest over
 * the live connection (the {@code SERVER_STOPPING} hook arms + serves the snapshot, blocking briefly).
 * The vanilla screen shown during that wait says "Saving world", which is misleading — swap it for a
 * message that says the world is being handed to the next host so the short pause makes sense.
 *
 * <p>require = 0: purely cosmetic, so a yarn-mapping drift on {@code disconnect} degrades (vanilla
 * text) rather than crashing the quit. Gated to the host-with-guests case so a normal disconnect (a
 * guest leaving, a solo singleplayer quit) keeps the vanilla screen.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientDisconnectMixin {

    @ModifyVariable(
        method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private Screen jukz$handoffScreen(Screen original) {
        if (HostSession.INSTANCE.isHosting() && HostSession.INSTANCE.connectedGuestCount() > 0) {
            return new MessageScreen(Text.literal("Handing the world to the next host…"));
        }
        return original;
    }
}
