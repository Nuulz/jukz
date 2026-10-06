package dev.jukz.skins

import com.mojang.blaze3d.platform.NativeImage
import dev.jukz.JukzMod
import dev.jukz.compat.Identifier
import dev.jukz.net.SkinPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
//? if >=1.21.11 {
/*import net.minecraft.core.ClientAsset
import net.minecraft.world.entity.player.PlayerModelType
import net.minecraft.world.entity.player.PlayerSkin
*///?} else {
import net.minecraft.client.resources.PlayerSkin
//?}

/**
 * Skins that don't come from Mojang: yours, when you chose one in the hub (saved on this PC in
 * `config/jukz/skin.png`), and the ones friends sent over the game connection while you play together
 * (see [SharedSkins]; kept in memory only). The game asks [override] for every player's skin, so a
 * registered one replaces the Mojang/default skin everywhere — world, tab list, the hub's preview.
 */
object LocalSkins {
    class Skin(val png: ByteArray, val slim: Boolean)

    private class Loaded(val texture: Identifier, val slim: Boolean)

    private val dir = FabricLoader.getInstance().configDir.resolve("jukz")
    private val file = dir.resolve("skin.png")
    private val modelFile = dir.resolve("skin-model.txt")

    private val loaded = ConcurrentHashMap<UUID, Loaded>()
    private var serial = 0

    /** Your saved skin, sent to the worlds you join through jukz. Null = none chosen (Mojang/default). */
    @Volatile var saved: Skin? = null
        private set

    /** Load your saved skin at startup and wear it. */
    fun init() {
        saved = runCatching {
            if (!Files.exists(file)) return@runCatching null
            Skin(Files.readAllBytes(file), Files.exists(modelFile) && Files.readString(modelFile).trim() == "slim")
        }.getOrNull()?.takeIf { SkinPayload.isSkinPng(it.png) }
        saved?.let { s -> Minecraft.getInstance().execute { wearSelf(s) } }
    }

    /** Keep [skin] as yours: saved on this PC, worn now, and sent to the world you're in (if it has jukz). */
    fun save(skin: Skin) {
        runCatching {
            Files.createDirectories(dir)
            Files.write(file, skin.png)
            Files.writeString(modelFile, if (skin.slim) "slim" else "classic")
        }.onFailure { JukzMod.logger.warn("jukz: couldn't save your skin: {}", it.message) }
        saved = skin
        wearSelf(skin)
        share()
    }

    /** Back to the Mojang/default skin: forget the saved one. Friends see the change on their next join. */
    fun clearSaved() {
        runCatching { Files.deleteIfExists(file); Files.deleteIfExists(modelFile) }
        saved = null
        selfId()?.let(::remove)
    }

    /** Wear [skin] on this PC only, not saved nor shared (right after a Mojang upload, until a restart). */
    fun wearSelf(skin: Skin) {
        selfId()?.let { apply(it, skin.png, skin.slim) }
    }

    /** Until when (ms) to keep trying to send your skin after joining: the host's channels arrive late. */
    @Volatile private var shareUntil = 0L

    /** Joined a world: send your skin as soon as its host says it takes jukz skins. */
    fun shareSoon() {
        shareUntil = System.currentTimeMillis() + 15_000
    }

    /** Every client tick: finish a pending [shareSoon]. */
    fun tick() {
        if (shareUntil == 0L) return
        if (System.currentTimeMillis() > shareUntil) { shareUntil = 0L; return }
        if (share()) shareUntil = 0L
    }

    /** Send your saved skin to the world you're connected to, if its host runs jukz; true if sent. */
    fun share(): Boolean {
        val skin = saved ?: return true
        val me = selfId() ?: return false
        if (!ClientPlayNetworking.canSend(SkinPayload.ID)) return false
        ClientPlayNetworking.send(SkinPayload(me, skin.slim, skin.png))
        return true
    }

    /** A friend's skin arrived over the game connection. */
    fun receive(payload: SkinPayload) {
        if (payload.owner == selfId() || !SkinPayload.isSkinPng(payload.png)) return
        apply(payload.owner, payload.png, payload.slim)
    }

    /** Left the world: drop the friends' skins (yours stays). */
    fun forgetFriends() {
        val me = selfId()
        loaded.keys.filter { it != me && it != PREVIEW }.forEach(::remove)
    }

    /** Key for a skin being looked at in the hub before it's applied (no player has this id). */
    val PREVIEW: UUID = UUID(0L, 0x6A756B7AL)

    /** Show [skin] on the hub's preview only. */
    fun preview(skin: Skin) = apply(PREVIEW, skin.png, skin.slim)

    fun clearPreview() = remove(PREVIEW)

    /** The texture and arm model to draw [player] with instead of their usual skin, if any. */
    fun textureFor(player: UUID): Pair<Identifier, Boolean>? = loaded[player]?.let { it.texture to it.slim }

    /** The skin to use for [player] in place of [base] (Mojang's or the default), or null to keep it. */
    fun override(player: UUID, base: PlayerSkin): PlayerSkin? {
        val skin = loaded[player] ?: return null
        //? if >=1.21.11 {
        /*val body = ClientAsset.ResourceTexture(skin.texture, skin.texture)
        return PlayerSkin(body, base.cape(), base.elytra(), if (skin.slim) PlayerModelType.SLIM else PlayerModelType.WIDE, false)
        *///?} else {
        return PlayerSkin(skin.texture, null, base.capeTexture(), base.elytraTexture(),
            if (skin.slim) PlayerSkin.Model.SLIM else PlayerSkin.Model.WIDE, false)
        //?}
    }

    /** Decode [png] into a texture for [player] (render thread). */
    private fun apply(player: UUID, png: ByteArray, slim: Boolean) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return client.execute { apply(player, png, slim) }
        val image = runCatching { NativeImage.read(png) }.getOrElse {
            JukzMod.logger.warn("jukz: unreadable skin for {}: {}", player, it.message)
            return
        }
        val id = Identifier.fromNamespaceAndPath("jukz", "skins/${player.toString().replace("-", "")}_${serial++}")
        //? if >=1.21.11 {
        /*val texture = DynamicTexture({ id.toString() }, legacyToModern(image))
        *///?} else {
        val texture = DynamicTexture(legacyToModern(image))
        //?}
        client.textureManager.register(id, texture)
        loaded.put(player, Loaded(id, slim))?.let { client.textureManager.release(it.texture) }
    }

    private fun remove(player: UUID) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return client.execute { remove(player) }
        loaded.remove(player)?.let { client.textureManager.release(it.texture) }
    }

    /**
     * An old 64×32 skin made 64×64: the top half as is, the left arm and leg copied from the right
     * ones, as vanilla does for legacy skins.
     */
    private fun legacyToModern(image: NativeImage): NativeImage {
        if (image.height != 32) return image
        val full = NativeImage(64, 64, true)
        full.copyFrom(image)
        image.close()
        // Same copies as vanilla's legacy-skin conversion: right leg → left leg, right arm → left arm.
        full.copyRect(4, 16, 16, 32, 4, 4, true, false)
        full.copyRect(8, 16, 16, 32, 4, 4, true, false)
        full.copyRect(0, 20, 24, 32, 4, 12, true, false)
        full.copyRect(4, 20, 16, 32, 4, 12, true, false)
        full.copyRect(8, 20, 8, 32, 4, 12, true, false)
        full.copyRect(12, 20, 16, 32, 4, 12, true, false)
        full.copyRect(44, 16, -8, 32, 4, 4, true, false)
        full.copyRect(48, 16, -8, 32, 4, 4, true, false)
        full.copyRect(40, 20, 0, 32, 4, 12, true, false)
        full.copyRect(44, 20, -8, 32, 4, 12, true, false)
        full.copyRect(48, 20, -16, 32, 4, 12, true, false)
        full.copyRect(52, 20, -8, 32, 4, 12, true, false)
        return full
    }

    private fun selfId(): UUID? = Minecraft.getInstance().gameProfile?.id
}
