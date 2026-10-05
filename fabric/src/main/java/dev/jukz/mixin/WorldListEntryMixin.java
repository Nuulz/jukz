package dev.jukz.mixin;

import dev.jukz.client.gui.WorldListLiveBadge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws a jukz "live" badge on each world-list row whose world is currently hosted, and turns a click
 * on that badge into a direct join (F4-C). Targets the inner {@code WorldEntry} rather than the whole
 * widget so the per-row geometry (x / y / width) is handed straight to the badge helper, keeping the
 * ASM minimal — all of the logic lives in {@link WorldListLiveBadge}.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {

    @Shadow @Final private LevelSummary summary;

    // require = 0: the badge is cosmetic, so a mapping/signature drift degrades (no badge) rather than
    // crashing the world list. Validated in-game.
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void jukz$liveBadge(GuiGraphics context, int index, int y, int x, int entryWidth, int entryHeight,
                                int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        WorldListLiveBadge.INSTANCE.render(
            context, Minecraft.getInstance().font, this.summary.getLevelId(), x, y, entryWidth);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void jukz$badgeClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        // Clicking a row selects it; remember it so the world-list "Copy jukz code" button can act on it.
        WorldListLiveBadge.INSTANCE.noteSelected(this.summary.getLevelId());
        Screen current = Minecraft.getInstance().screen;
        if (WorldListLiveBadge.INSTANCE.handleClick(this.summary.getLevelId(), mouseX, mouseY, current)) {
            cir.setReturnValue(true);
        }
    }
}
