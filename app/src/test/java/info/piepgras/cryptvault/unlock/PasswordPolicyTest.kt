package info.piepgras.cryptvault.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun `rejects short, common and predictable passwords`() {
        assertFalse(PasswordPolicy.check("").ok)
        assertFalse(PasswordPolicy.check("password").ok)
        assertFalse(PasswordPolicy.check("password123").ok)
        assertFalse(PasswordPolicy.check("qwertyuiop").ok)
        val short = PasswordPolicy.check("Xk9#pQ2m")
        assertTrue(short.tooShort)
        assertFalse(short.ok)
    }

    @Test
    fun `accepts a passphrase and a long random string`() {
        val r = PasswordPolicy.check("correct horse battery staple")
        assertTrue(r.ok)
        assertTrue(r.score >= 3)
        assertTrue(PasswordPolicy.check("Vb7!kQp2#sLm9zWx").ok)
        assertTrue(PasswordPolicy.check(PasswordPolicy.suggest()).ok)
    }

    @Test
    fun `suggestions are six list words`() {
        val s = PasswordPolicy.suggest()
        assertEquals(6, s.split(" ").size)
        assertTrue(Passphrase.isFromList(s))
    }
}
