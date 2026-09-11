package com.multibrowser.antidetect.sync

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class CookieIntegrityTest {

    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testSha256ConsistentHash() {
        val rawCookies = "[{\"domain\":\".google.com\",\"name\":\"SID\",\"value\":\"abc123xyz\"}]"
        val hash1 = computeSha256(rawCookies)
        val hash2 = computeSha256(rawCookies)

        assertNotNull(hash1)
        assertEquals(64, hash1.length) // 256 bits = 64 hex characters
        assertEquals("SHA-256 must be strictly deterministic", hash1, hash2)
    }

    @Test
    fun testSha256DetectsTampering() {
        val original = "[{\"domain\":\".google.com\",\"name\":\"SID\",\"value\":\"abc123xyz\"}]"
        val tampered = "[{\"domain\":\".google.com\",\"name\":\"SID\",\"value\":\"tampered_val\"}]"

        val originalHash = computeSha256(original)
        val tamperedHash = computeSha256(tampered)

        assertNotEquals("Tampered cookie payload must produce a distinct hash", originalHash, tamperedHash)
    }

    @Test
    fun testEmptyPayloadHandling() {
        assertEquals("[]", CookieEngine.encryptCookiePayload("[]"))
        assertEquals("[]", CookieEngine.encryptCookiePayload(""))
        assertEquals("[]", CookieEngine.decryptCookiePayload("[]"))
        assertEquals("[]", CookieEngine.decryptCookiePayload(""))
    }

    @Test
    fun testLegacyPlainJsonCookiePassThrough() {
        val legacyJson = "[{\"domain\":\".youtube.com\",\"name\":\"LOGIN_INFO\",\"value\":\"session_token_123\"}]"
        // Legacy plain JSON cookie without envelope should pass through decryptCookiePayload untouched
        val result = CookieEngine.decryptCookiePayload(legacyJson)
        assertEquals("Legacy cookies without encryption envelope must pass through transparently", legacyJson, result)
        assertTrue("Legacy cookie strings should pass integrity verification", CookieEngine.verifyCookieIntegrity(legacyJson))
    }
}
