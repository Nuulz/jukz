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
 * Refuse a remote login Mojang didn't vouch for (offline UUID) when the host doesn't allow those, or
 * when it takes the host's name or the name of someone already in the world (see {@link GuestAdmission}). Vanilla would instead log the newcomer in and kick the player
 * already there ("You logged in from another location"), handing over their character. {@code
 * checkCanJoin} runs before that duplicate handling, so the player inside stays. Verified guests and the
 * host's own in-process connection ({@link LocalAddress}) are untouched.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListJoinMixin {

    @Shadow @Final private MinecraftServer server;

    @Shadow public abstract List<ServerPlayer> getPlayers();

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    //? if >=1.21.11 {
    /*private void jukz$refuseTakenNames(SocketAddress address, net.minecraft.server.players.NameAndId profile, CallbackInfoReturnable<Component> cir) {
        GameProfile host = server.getSingleplayerProfile();
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : getPlayers()) names.add(player.getGameProfile().name());
        String joining = profile.name();
        String hostName = host != null ? host.name() : null;
    *///?} else {
    private void jukz$refuseTakenNames(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        GameProfile host = server.getSingleplayerProfile();
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : getPlayers()) names.add(player.getGameProfile().getName());
        String joining = profile.getName();
        String hostName = host != null ? host.getName() : null;
    //?}
        boolean verified = !net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(joining).equals(jukz$id(profile));
        String reason = GuestAdmission.INSTANCE.refusal(
            verified,
            GuestAdmission.INSTANCE.getAllowUnverifiedGuests(),
            address instanceof LocalAddress,
            joining,
            hostName,
            names
        );
        if (reason != null) cir.setReturnValue(Component.literal(reason));
        if (GuestAdmission.ACCOUNT_REQUIRED.equals(reason)) GuestAdmission.INSTANCE.refusedUnverified(joining, System.currentTimeMillis());
    }

    //? if >=1.21.11 {
    /*private static java.util.UUID jukz$id(net.minecraft.server.players.NameAndId profile) { return profile.id(); }
    *///?} else {
    private static java.util.UUID jukz$id(GameProfile profile) { return profile.getId(); }
    //?}
}
