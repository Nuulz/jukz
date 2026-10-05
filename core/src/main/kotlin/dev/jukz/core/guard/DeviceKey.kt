package dev.jukz.core.guard

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * This install's Ed25519 device key — the anti-abuse identity, like a passkey: made on first launch, it
 * never leaves the PC and signs the rendezvous calls that open or join a world, so limits follow the
 * device instead of the network (ten players behind one router each get their own). No account, no
 * sign-in. The rendezvous only counts a key once it is registered (see [ProofOfWork]).
 */
class DeviceKey private constructor(private val private: PrivateKey, val publicKey: ByteArray) {

    /** Base64url (no padding) of the 32-byte public key, as sent to the rendezvous. */
    fun publicKeyText(): String = B64.encodeToString(publicKey)

    /** The headers a signed call carries; [path] excludes the query string, [body] is the exact text sent. */
    fun headers(method: String, path: String, body: String, timestampMillis: Long): Map<String, String> = mapOf(
        HEADER_KEY to publicKeyText(),
        HEADER_TS to timestampMillis.toString(),
        HEADER_SIG to B64.encodeToString(sign(method, path, timestampMillis, body)),
    )

    fun sign(method: String, path: String, timestampMillis: Long, body: String): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(private)
            update(payload(method, path, timestampMillis, body))
            sign()
        }

    /** The key file: a version line, then the private key (PKCS#8) and the public key, base64. */
    fun encode(): String =
        "$FILE_HEADER\n${Base64.getEncoder().encodeToString(private.encoded)}\n${Base64.getEncoder().encodeToString(publicKey)}\n"

    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val FILE_HEADER = "jukz-device-key-v1"
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        const val HEADER_KEY = "X-Jukz-Device"
        const val HEADER_TS = "X-Jukz-Device-Ts"
        const val HEADER_SIG = "X-Jukz-Device-Sig"

        /** On a world lookup: this one is to join (counted), not a menu badge refreshing. */
        const val HEADER_INTENT = "X-Jukz-Intent"

        fun generate(): DeviceKey {
            val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            val public = pair.public.encoded
            return DeviceKey(pair.private, public.copyOfRange(X509_PREFIX.size, public.size))
        }

        fun decode(text: String): DeviceKey {
            val lines = text.trim().lines()
            require(lines.size == 3 && lines[0] == FILE_HEADER) { "not a jukz device key" }
            val private = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[1])))
            val public = Base64.getDecoder().decode(lines[2])
            require(public.size == 32) { "bad public key" }
            val key = DeviceKey(private, public)
            require(verify(public, "GET", "/probe", 0, "", key.sign("GET", "/probe", 0, ""))) { "key halves don't match" }
            return key
        }

        /** Mirrors devicePayload in rendezvous-worker/src/guard-logic.ts. */
        fun payload(method: String, path: String, timestampMillis: Long, body: String): ByteArray =
            "jukz-device-v1\n${method.uppercase()} $path\n$timestampMillis\n$body".toByteArray(Charsets.UTF_8)

        fun verify(publicKey: ByteArray, method: String, path: String, timestampMillis: Long, body: String, signature: ByteArray): Boolean =
            runCatching {
                val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey))
                Signature.getInstance(ALGORITHM).run {
                    initVerify(key)
                    update(payload(method, path, timestampMillis, body))
                    verify(signature)
                }
            }.getOrDefault(false)
    }
}
