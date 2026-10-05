package dev.jukz.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import dev.jukz.compat.Identifier
import net.minecraft.core.UUIDUtil
import java.util.UUID

/**
 * Host → player, over the game connection, when a player joins a hosted world (and again whenever the
 * host re-announces): the world's ownership key (`jukz.key` text) and the gate that unlocks this
 * session's handoff snapshot. Sent in-game on purpose — only players the server let in (Mojang-verified
 * in online mode) receive it, unlike the control channel, which anyone with the share code can open.
 */
data class WorldAccessPayload(val worldId: UUID, val key: String, val gate: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<WorldAccessPayload> = ID

    companion object {
        val ID: CustomPacketPayload.Type<WorldAccessPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("jukz", "world_access"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WorldAccessPayload> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, WorldAccessPayload::worldId,
            ByteBufCodecs.STRING_UTF8, WorldAccessPayload::key,
            ByteBufCodecs.STRING_UTF8, WorldAccessPayload::gate,
            ::WorldAccessPayload,
        )
    }
}
