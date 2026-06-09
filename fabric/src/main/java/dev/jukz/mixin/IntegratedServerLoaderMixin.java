package dev.jukz.mixin;

import dev.jukz.client.WorldOpenInterceptor;
import net.minecraft.server.integrated.IntegratedServerLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Consults jukz discovery before booting a singleplayer world. If that world (by its persisted jukz
 * UUID) is already hosted live elsewhere, the local boot is cancelled and the player is joined to the
 * live host instead — so "the world lives in one place" is the default behaviour of opening a world,
 * with no button involved. When nobody hosts it, the world opens locally as usual.
 *
 * Targets the public {@code start(String levelName, Runnable onCancel)} entry that the world-select
 * screen calls. The interceptor re-enters this same method (guarded) to perform the local boot when
 * it decides not to join.
 */
@Mixin(IntegratedServerLoader.class)
public class IntegratedServerLoaderMixin {

    @Inject(
        method = "start(Ljava/lang/String;Ljava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void jukz$consultDiscovery(String levelName, Runnable onCancel, CallbackInfo ci) {
        if (WorldOpenInterceptor.INSTANCE.shouldIntercept(levelName, onCancel)) {
            ci.cancel();
        }
    }
}
