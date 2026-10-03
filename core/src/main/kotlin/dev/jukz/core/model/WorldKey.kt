package dev.jukz.core.model

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A world's Ed25519 ownership key. The share code lets anyone *find and join* a world; changing what
 * the rendezvous holds for it (announce, heartbeat, withdraw, cloud backup) needs a signature from this
 * key. The rendezvous binds the public half on first use, so from then on a leaked share code can no
 * longer take over the live record or overwrite the backup.
 *
 * The key lives in the world's save folder (`jukz.key`, see [encode]) and travels with the world: a live
 * handoff or a cloud takeover hands it to the next host together with the files. Plain JDK crypto
 * (Ed25519 is built in since Java 15).
 */
class WorldKey private constructor(private val private: PrivateKey, val publicKey: ByteArray) {

    /** Base64url (no padding) of the 32-byte public key, as sent to the rendezvous. */
    fun publicKeyText(): String = B64.encodeToString(publicKey)

    fun sign(op: String, worldId: WorldId, timestampMillis: Long, body: String): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(private)
            update(payload(op, worldId, timestampMillis, body))
            sign()
        }

    /** The headers a signed rendezvous request carries. */
    fun headers(op: String, worldId: WorldId, body: String, timestampMillis: Long): Map<String, String> = mapOf(
        HEADER_KEY to publicKeyText(),
        HEADER_TS to timestampMillis.toString(),
        HEADER_SIG to B64.encodeToString(sign(op, worldId, timestampMillis, body)),
    )

    /**
     * The `jukz.key` file: a version line, then the private key (PKCS#8) and the public key, base64.
     * Both halves are stored because the JDK can't derive an Ed25519 public key from the private one.
     */
    fun encode(): String =
        "$FILE_HEADER\n${Base64.getEncoder().encodeToString(private.encoded)}\n${Base64.getEncoder().encodeToString(publicKey)}\n"

    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val FILE_HEADER = "jukz-world-key-v1"
        private val B64 = Base64.getUrlEncoder().withoutPadding()

        // X.509 SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 key (RFC 8410).
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        const val HEADER_KEY = "X-Jukz-Key"
        const val HEADER_TS = "X-Jukz-Ts"
        const val HEADER_SIG = "X-Jukz-Sig"

        /** Operations a signature can cover; the rendezvous checks the one matching its route. */
        const val OP_ANNOUNCE = "announce"
        const val OP_HEARTBEAT = "heartbeat"
        const val OP_WITHDRAW = "withdraw"
        const val OP_SNAPSHOT_UPLOAD = "snapshot-upload"
        const val OP_SNAPSHOT_DOWNLOAD = "snapshot-download"

        fun generate(): WorldKey {
            val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            val public = pair.public.encoded
            return WorldKey(pair.private, public.copyOfRange(X509_PREFIX.size, public.size))
        }

        /** Parse a `jukz.key` file written by [encode]; throws on anything else. */
        fun decode(text: String): WorldKey {
            val lines = text.trim().lines()
            require(lines.size == 3 && lines[0] == FILE_HEADER) { "not a jukz world key" }
            val private = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[1])))
            val public = Base64.getDecoder().decode(lines[2])
            require(public.size == 32) { "bad public key" }
            val key = WorldKey(private, public)
            // A file whose halves don't belong together would sign requests the server then rejects.
            val probe = WorldId.of(java.util.UUID(0, 0))
            require(verify(public, "probe", probe, 0, "", key.sign("probe", probe, 0, ""))) { "key halves don't match" }
            return key
        }

        /**
         * The exact bytes that get signed. Binding the operation and the world id means a signature for
         * one call can't be replayed as another; the timestamp limits replay to the server's window.
         */
        fun payload(op: String, worldId: WorldId, timestampMillis: Long, body: String): ByteArray =
            "jukz-v1\n$op\n${worldId.uuid}\n$timestampMillis\n$body".toByteArray(Charsets.UTF_8)

        fun verify(publicKey: ByteArray, op: String, worldId: WorldId, timestampMillis: Long, body: String, signature: ByteArray): Boolean =
            runCatching {
                val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
                Signature.getInstance(ALGORITHM).run {
                    initVerify(key)
                    update(payload(op, worldId, timestampMillis, body))
                    verify(signature)
                }
            }.getOrDefault(false)
    }
}
