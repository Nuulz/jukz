package dev.jukz.net

import dev.jukz.compat.Identifier
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/**
 * What a player without a Mojang account wears (slot → item id), carried over the game connection only,
 * like [SkinPayload]: player → host when they join or change it, host → everyone else in the world.
 * Everyone sends where they moved their pieces (`~hat` = "up,out", see CosmeticFit); only players without
 * a Mojang account send their picks too, marked with `!` (premium picks come from the jukz server).
 * [owner] is ignored player → host (the host uses whoever sent it).
 */
class LoadoutPayload(val owner: UUID, val loadout: Map<String, String>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<LoadoutPayload> = ID

    companion object {
        private const val MAX_CHARS = 512

        val ID: CustomPacketPayload.Type<LoadoutPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("jukz", "loadout"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LoadoutPayload> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, LoadoutPayload::owner,
            ByteBufCodecs.stringUtf8(MAX_CHARS), { encode(it.loadout) },
            { owner, text -> LoadoutPayload(owner, decode(text)) },
        )

        /** "hat=party_hat;face=sunglasses" — short and safe to bound; slots and ids never hold ';' or '='. */
        fun encode(loadout: Map<String, String>): String =
            loadout.entries.joinToString(";") { "${it.key}=${it.value}" }.take(MAX_CHARS)

        fun decode(text: String): Map<String, String> = text.split(';').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0 || i == pair.lastIndex) null else pair.substring(0, i) to pair.substring(i + 1)
        }.take(16).toMap()
    }
}
