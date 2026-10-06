package dev.jukz.mixin;

import dev.jukz.skins.LocalSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.11 {
/*import net.minecraft.world.entity.player.PlayerSkin;
*///?} else {
import net.minecraft.client.resources.PlayerSkin;
//?}

/**
 * Same as {@link PlayerInfoSkinMixin}, for a player drawn without a tab-list entry (vanilla then falls
 * back to the default skin).
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSkinMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void jukz$localSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = LocalSkins.INSTANCE.override(((Entity) (Object) this).getUUID(), cir.getReturnValue());
        if (skin != null) cir.setReturnValue(skin);
    }
}
