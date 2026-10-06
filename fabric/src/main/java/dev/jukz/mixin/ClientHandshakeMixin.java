package dev.jukz.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Without a Microsoft account there is no session to prove to Mojang, so vanilla gives up at the
 * server's verification request ("Invalid session"). jukz hosts always send that request (hybrid
 * login, see {@link dev.jukz.client.GuestAdmission}) and let unverified players in under their offline
 * name when allowed — so skip the check and carry on. A normal online server still refuses, as before.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "authenticateServer", at = @At("HEAD"), cancellable = true)
    private void jukz$skipWithoutAccount(String digest, CallbackInfoReturnable<Component> cir) {
        if (!dev.jukz.compat.GameKt.getHasRealAccount(minecraft)) cir.setReturnValue(null);
    }
}
