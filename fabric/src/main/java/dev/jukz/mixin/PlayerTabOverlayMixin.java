package dev.jukz.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.jukz.cosmetics.TabBadge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * jukz badges in the tab list, left of the name. The name gets a marked 8 px gap in front ({@link TabBadge#decorate}), so vanilla
 * sizes the columns with the badge included; right after vanilla draws the name, the badge is painted
 * into that gap. Players without a badge are untouched.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void jukz$reserveBadge(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(TabBadge.decorate(cir.getReturnValue(), entry.getProfile().getId()));
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"
        )
    )
    private int jukz$drawBadge(GuiGraphics context, Font renderer, Component text, int x, int y, int color, Operation<Integer> original) {
        int width = original.call(context, renderer, text, x, y, color);
        TabBadge.draw(context, renderer, text, x, y);
        return width;
    }
}
