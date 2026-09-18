package info.piepgras.cryptvault.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The claims of `vault.cryptomator`. */
data class VaultConfig(
    val format: Int,
    val cipherCombo: String,
    val shorteningThreshold: Int,
    val jti: String,
)

class VaultConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `vault.cryptomator` is a JWT signed with HMAC-SHA256 over the 64 raw masterkey bytes
 * (encryption key followed by MAC key), header `kid: masterkeyfile:masterkey.cryptomator`.
 * This is what Cryptomator's cryptofs writes and verifies; the token format is documented at
 * https://docs.cryptomator.org/security/architecture/. Written by hand here rather than with a
 * JOSE library because the whole surface is two base64url segments and one MAC.
 */
object VaultConfigToken {

    const val KEY_ID = "masterkeyfile:masterkey.cryptomator"
    private const val ALG = "HS256"
    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun create(config: VaultConfig, rawKey: ByteArray): String {
        val header = buildJsonObject {
            put("kid", KEY_ID)
            put("typ", "JWT")
            put("alg", ALG)
        }
        val payload = buildJsonObject {
            put("jti", config.jti)
            put("format", config.format)
            put("cipherCombo", config.cipherCombo)
            put("shorteningThreshold", config.shorteningThreshold)
        }
        val signingInput = b64(json.encodeToString(JsonObject.serializer(), header)) + "." +
            b64(json.encodeToString(JsonObject.serializer(), payload))
        return signingInput + "." + encoder.encodeToString(hmac(rawKey, signingInput))
    }

    /** The `kid` header of a token, readable without the key; says where the key comes from. */
    fun keyId(token: String): String? = runCatching {
        parse(decodeSegment(token.split('.')[0]))["kid"]?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Verifies the signature with [rawKey] and returns the claims. A wrong key — a recovery key
     * that belongs to another vault, for instance — fails here, before anything is written.
     */
    fun verify(token: String, rawKey: ByteArray): VaultConfig {
        val parts = token.trim().split('.')
        if (parts.size != 3) throw VaultConfigException("vault.cryptomator is not a JWT")
        val header = try {
            parse(decodeSegment(parts[0]))
        } catch (e: Exception) {
            throw VaultConfigException("vault.cryptomator header is unreadable", e)
        }
        val alg = header["alg"]?.jsonPrimitive?.content
        val macAlg = when (alg) {
            "HS256" -> "HmacSHA256"
            "HS384" -> "HmacSHA384"
            "HS512" -> "HmacSHA512"
            else -> throw VaultConfigException("vault.cryptomator uses unsupported algorithm $alg")
        }
        val expected = Mac.getInstance(macAlg).run {
            init(SecretKeySpec(rawKey, macAlg))
            doFinal((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        }
        val actual = try {
            decoder.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw VaultConfigException("vault.cryptomator signature is unreadable", e)
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw VaultConfigException("vault.cryptomator signature does not match this key")
        }
        val claims = try {
            parse(decodeSegment(parts[1]))
        } catch (e: Exception) {
            throw VaultConfigException("vault.cryptomator claims are unreadable", e)
        }
        return try {
            VaultConfig(
                format = claims.getValue("format").jsonPrimitive.int,
                cipherCombo = claims.getValue("cipherCombo").jsonPrimitive.content,
                shorteningThreshold = claims.getValue("shorteningThreshold").jsonPrimitive.int,
                jti = claims["jti"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            throw VaultConfigException("vault.cryptomator claims are incomplete", e)
        }
    }

    private fun hmac(key: ByteArray, input: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(input.toByteArray(Charsets.US_ASCII))
    }

    private fun b64(s: String): String = encoder.encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun decodeSegment(s: String): String = String(decoder.decode(s), Charsets.UTF_8)
    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject
}
