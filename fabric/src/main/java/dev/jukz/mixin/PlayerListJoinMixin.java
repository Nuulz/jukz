package dev.jukz.mixin;

import com.mojang.authlib.GameProfile;
import dev.jukz.client.GuestAdmission;
import io.netty.channel.local.LocalAddress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * In offline mode, refuse a remote login that takes the host's name or the name of someone already in
 * the world (see {@link GuestAdmission}). Vanilla would instead log the newcomer in and kick the player
 * already there ("You logged in from another location"), handing over their character. {@code
 * checkCanJoin} runs before that duplicate handling, so the player inside stays. Online mode and the
 * host's own in-process connection ({@link LocalAddress}) are untouched.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListJoinMixin {

    @Shadow @Final private MinecraftServer server;

    @Shadow public abstract List<ServerPlayer> getPlayers();

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void jukz$refuseTakenNames(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        GameProfile host = server.getSingleplayerProfile();
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : getPlayers()) names.add(player.getGameProfile().getName());
        String reason = GuestAdmission.INSTANCE.refusal(
            server.usesAuthentication(),
            address instanceof LocalAddress,
            profile.getName(),
            host != null ? host.getName() : null,
            names
        );
        if (reason != null) cir.setReturnValue(Component.literal(reason));
    }
}
