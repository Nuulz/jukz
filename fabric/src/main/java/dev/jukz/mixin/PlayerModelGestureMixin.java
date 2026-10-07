package dev.jukz.mixin;

import dev.jukz.cosmetics.Gestures;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.11 {
/*import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
*///?} else {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
//?}

/** Emotes that move the player (the angel's coin toss): the right arm, after vanilla posed it. See {@link Gestures}. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelGestureMixin {

    //? if >=1.21.11 {
    /*@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void jukz$gesture(AvatarRenderState state, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(state.id);
        if (entity == null) return;
        PlayerModel self = (PlayerModel) (Object) this;
        Gestures.INSTANCE.poseArm(entity.getUUID(), self.rightArm);
    }
    *///?} else {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void jukz$gesture(LivingEntity entity, float a, float b, float c, float d, float e, CallbackInfo ci) {
        PlayerModel<?> self = (PlayerModel<?>) (Object) this;
        Gestures.INSTANCE.poseArm(entity.getUUID(), self.rightArm);
        self.rightSleeve.copyFrom(self.rightArm);
    }
    //?}
}
