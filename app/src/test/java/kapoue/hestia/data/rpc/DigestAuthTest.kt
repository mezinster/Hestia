package kapoue.hestia.data.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAuthTest {

    @Test
    fun `parses a quoted Shelly challenge`() =
        assertEquals(
            Challenge("shellyplugmg3-aabbccddeeff", "6543a1b2"),
            DigestAuth.parseChallenge(
                """Digest qop="auth", realm="shellyplugmg3-aabbccddeeff", nonce="6543a1b2", algorithm=SHA-256"""
            )
        )

    @Test
    fun `parses unquoted values`() =
        assertEquals(Challenge("r", "n"), DigestAuth.parseChallenge("Digest realm=r, nonce=n, qop=auth"))

    @Test
    fun `scheme name is case-insensitive`() =
        assertEquals(Challenge("r", "n"), DigestAuth.parseChallenge("digest realm=\"r\", nonce=\"n\""))

    @Test
    fun `rejects a non-digest or incomplete challenge`() {
        assertNull(DigestAuth.parseChallenge("Basic realm=\"x\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"x\""))
        assertNull(DigestAuth.parseChallenge(null))
    }

    @Test
    fun `rejects a challenge with control characters`() {
        // Un realm/nonce fourni par l'appareil finit dans l'en-tête Authorization : CR/LF,
        // guillemets, antislash ou non-ASCII le rendraient invalide (ou injecteraient un en-tête).
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\r\nX-Evil: 1\", nonce=\"n\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"n\tx\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\u00e9\", nonce=\"n\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"n\u00e9\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\\\", nonce=\"n\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=r, nonce=n\\x"))
        // Le nonce n'admet pas d'espace ; le realm, si.
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"n x\""))
        assertEquals(Challenge("my realm", "n"), DigestAuth.parseChallenge("Digest realm=\"my realm\", nonce=\"n\""))
    }

    @Test
    fun `rejects an empty nonce`() {
        assertNull(DigestAuth.parseChallenge("Digest realm=\"r\", nonce=\"\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=\"\", nonce=\"n\""))
        assertNull(DigestAuth.parseChallenge("Digest realm=r, nonce="))
    }

    // Vecteurs calculés indépendamment (Python hashlib, 2026-10-29).
    @Test
    fun `response matches an independent vector`() = assertTrue(
        DigestAuth.authorizationHeader("shellyplugmg3-aabbccddeeff", "6543a1b2", 1, "0a4f113b", "secret")
            .contains("""response="ef1ba08c6b581c0058a6816df02a8dea7e1daa6d7e1ca6b93f29b73d55a4a965"""")
    )

    @Test
    fun `non-ASCII password is hashed as UTF-8`() = assertTrue(
        DigestAuth.authorizationHeader("shellyplugmg3-aabbccddeeff", "6543a1b2", 1, "0a4f113b", "pässwörd")
            .contains("""response="79239bf766d5524af65b77dbad1613dcd0fefa2338793e5c0f95eddb054fc788"""")
    )

    @Test
    fun `header carries nc as 8 hex digits and never the password`() {
        val h = DigestAuth.authorizationHeader("r", "n", 26, "c", "secret")
        assertTrue(h.contains("nc=0000001a"))
        assertFalse(h.contains("secret"))
    }

    @Test
    fun `session counter strictly increases`() {
        val s = DigestSession("r", "n")
        assertEquals(listOf(1, 2, 3), List(3) { s.nextNc() })
    }
}
