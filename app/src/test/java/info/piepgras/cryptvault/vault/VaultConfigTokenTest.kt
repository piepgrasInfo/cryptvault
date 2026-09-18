package info.piepgras.cryptvault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

class VaultConfigTokenTest {

    private val key = ByteArray(64).also { Random(7).nextBytes(it) }
    private val config = VaultConfig(8, "SIV_GCM", 220, "b1f0d2e4-0000-4000-8000-000000000001")

    @Test
    fun `creates a three-part token with the masterkeyfile key id and verifies it`() {
        val token = VaultConfigToken.create(config, key)
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val header = String(Base64.getUrlDecoder().decode(parts[0]))
        assertTrue(header.contains("\"kid\":\"masterkeyfile:masterkey.cryptomator\""))
        assertTrue(header.contains("\"alg\":\"HS256\""))
        assertTrue(header.contains("\"typ\":\"JWT\""))
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(payload.contains("\"format\":8"))
        assertTrue(payload.contains("\"cipherCombo\":\"SIV_GCM\""))
        assertTrue(payload.contains("\"shorteningThreshold\":220"))
        assertEquals("masterkeyfile:masterkey.cryptomator", VaultConfigToken.keyId(token))
        assertEquals(config, VaultConfigToken.verify(token, key))
    }

    @Test
    fun `rejects a wrong key, a tampered payload and garbage`() {
        val token = VaultConfigToken.create(config, key)
        val other = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(VaultConfigException::class.java) { VaultConfigToken.verify(token, other) }
        val parts = token.split('.')
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"jti":"x","format":9,"cipherCombo":"SIV_GCM","shorteningThreshold":220}""".toByteArray())
        assertThrows(VaultConfigException::class.java) { VaultConfigToken.verify("${parts[0]}.$forged.${parts[2]}", key) }
        assertThrows(VaultConfigException::class.java) { VaultConfigToken.verify("not.a", key) }
        assertThrows(VaultConfigException::class.java) { VaultConfigToken.verify("a.b.c", key) }
        val noneAlg = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        assertThrows(VaultConfigException::class.java) { VaultConfigToken.verify("$noneAlg.${parts[1]}.", key) }
    }
}
