package dev.jukz.mixin;

import dev.jukz.client.gui.WorldListLiveBadge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
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
@Mixin(WorldListWidget.WorldEntry.class)
public abstract class WorldEntryMixin {

    @Shadow @Final private LevelSummary level;

    // require = 0: the badge is cosmetic, so a mapping/signature drift degrades (no badge) rather than
    // crashing the world list. Validated in-game.
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void jukz$liveBadge(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                                int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        WorldListLiveBadge.INSTANCE.render(
            context, MinecraftClient.getInstance().textRenderer, this.level.getName(), x, y, entryWidth);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void jukz$badgeClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        // Clicking a row selects it; remember it so the world-list "Copy jukz code" button can act on it.
        WorldListLiveBadge.INSTANCE.noteSelected(this.level.getName());
        Screen current = MinecraftClient.getInstance().currentScreen;
        if (WorldListLiveBadge.INSTANCE.handleClick(this.level.getName(), mouseX, mouseY, current)) {
            cir.setReturnValue(true);
        }
    }
}
