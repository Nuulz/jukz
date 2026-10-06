package dev.jukz.mixin;

import com.mojang.authlib.GameProfile;
import dev.jukz.skins.LocalSkins;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.11 {
/*import net.minecraft.world.entity.player.PlayerSkin;
*///?} else {
import net.minecraft.client.resources.PlayerSkin;
//?}

/**
 * A player's skin as the tab list and the world see it: a skin chosen in the jukz hub (yours, or a
 * friend's that came over the game connection) replaces the Mojang/default one. See {@link LocalSkins}.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {

    @Shadow public abstract GameProfile getProfile();

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void jukz$localSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        //? if >=1.21.11 {
        /*PlayerSkin skin = LocalSkins.INSTANCE.override(getProfile().id(), cir.getReturnValue());
        *///?} else {
        PlayerSkin skin = LocalSkins.INSTANCE.override(getProfile().getId(), cir.getReturnValue());
        //?}
        if (skin != null) cir.setReturnValue(skin);
    }
}
