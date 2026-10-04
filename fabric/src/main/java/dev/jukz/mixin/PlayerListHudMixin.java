package dev.jukz.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.jukz.cosmetics.TabBadge;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * jukz badges in the tab list, left of the name. The name gets a marked 8 px gap in front ({@link TabBadge#decorate}), so vanilla
 * sizes the columns with the badge included; right after vanilla draws the name, the badge is painted
 * into that gap. Players without a badge are untouched.
 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void jukz$reserveBadge(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        cir.setReturnValue(TabBadge.decorate(cir.getReturnValue(), entry.getProfile().getId()));
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"
        )
    )
    private int jukz$drawBadge(DrawContext context, TextRenderer renderer, Text text, int x, int y, int color, Operation<Integer> original) {
        int width = original.call(context, renderer, text, x, y, color);
        TabBadge.draw(context, renderer, text, x, y);
        return width;
    }
}
