package dev.jukz.net

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.minecraft.util.Uuids
import java.util.UUID

/**
 * Host → player, over the game connection, when a player joins a hosted world (and again whenever the
 * host re-announces): the world's ownership key (`jukz.key` text) and the gate that unlocks this
 * session's handoff snapshot. Sent in-game on purpose — only players the server let in (Mojang-verified
 * in online mode) receive it, unlike the control channel, which anyone with the share code can open.
 */
data class WorldAccessPayload(val worldId: UUID, val key: String, val gate: String) : CustomPayload {
    override fun getId(): CustomPayload.Id<WorldAccessPayload> = ID

    companion object {
        val ID: CustomPayload.Id<WorldAccessPayload> = CustomPayload.Id(Identifier.of("jukz", "world_access"))
        val CODEC: PacketCodec<RegistryByteBuf, WorldAccessPayload> = PacketCodec.tuple(
            Uuids.PACKET_CODEC, WorldAccessPayload::worldId,
            PacketCodecs.STRING, WorldAccessPayload::key,
            PacketCodecs.STRING, WorldAccessPayload::gate,
            ::WorldAccessPayload,
        )
    }
}
