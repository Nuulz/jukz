package dev.jukz.net

import dev.jukz.compat.Identifier
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

/**
 * A player's own skin (a PNG chosen in the hub), carried over the game connection only: player → host
 * when they join or change it, host → everyone else in the world. It never goes through a jukz server;
 * this is how players without a Mojang account show their skin to the friends they play with. [owner]
 * is ignored player → host (the host uses whoever sent it).
 */
class SkinPayload(val owner: UUID, val slim: Boolean, val png: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SkinPayload> = ID

    companion object {
        /** Plenty for a 64×64 PNG (usually 1–4 KB); keeps a hostile client from flooding the host. */
        const val MAX_BYTES = 32 * 1024

        val ID: CustomPacketPayload.Type<SkinPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("jukz", "skin"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SkinPayload> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SkinPayload::owner,
            ByteBufCodecs.BOOL, SkinPayload::slim,
            ByteBufCodecs.byteArray(MAX_BYTES), SkinPayload::png,
            ::SkinPayload,
        )

        /** A PNG of skin size (64×64, or the old 64×32), read from its header without decoding it. */
        fun isSkinPng(png: ByteArray): Boolean {
            if (png.size < 24 || png.size > MAX_BYTES) return false
            val magic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            if (!png.copyOfRange(0, 4).contentEquals(magic)) return false
            fun int(at: Int) = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
                ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
            val w = int(16); val h = int(20)
            return w == 64 && (h == 64 || h == 32)
        }
    }
}
