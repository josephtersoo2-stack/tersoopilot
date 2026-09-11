package com.multibrowser.antidetect.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionMessageAuthTest {

    @Test
    fun testSignAndVerifyValidMessage() {
        val payload = JSONObject().apply {
            put("action", "GET_COOKIES")
            put("requestId", "req-12345")
        }

        ExtensionMessageAuth.signMessage(payload)

        assertTrue("Payload must contain message_id", payload.has("message_id"))
        assertTrue("Payload must contain timestamp", payload.has("timestamp"))
        assertTrue("Payload must contain signature", payload.has("signature"))

        val isValid = ExtensionMessageAuth.verifyMessage(payload)
        assertTrue("Fresh signed message must be valid", isValid)
    }

    @Test
    fun testRejectExpiredMessage() {
        val payload = JSONObject().apply {
            put("action", "COOKIES_DUMP")
            put("message_id", "msg-expired")
            // 70 seconds old (outside 60s tolerance)
            put("timestamp", System.currentTimeMillis() - 70_000L)
        }

        val dataToSign = "${payload.getString("message_id")}:${payload.getLong("timestamp")}:${payload.getString("action")}"
        payload.put("signature", ExtensionMessageAuth.computeHmac(dataToSign, "tersoo_antidetect_ext_bridge_secret_v1"))

        val isValid = ExtensionMessageAuth.verifyMessage(payload)
        assertFalse("Message outside the 60-second replay window must be rejected", isValid)
    }

    @Test
    fun testRejectTamperedMessage() {
        val payload = JSONObject().apply {
            put("action", "GET_COOKIES")
            put("requestId", "req-abc")
        }

        ExtensionMessageAuth.signMessage(payload)

        // Tamper with action after signing
        payload.put("action", "MALICIOUS_ACTION")

        val isValid = ExtensionMessageAuth.verifyMessage(payload)
        assertFalse("Tampered message signature must fail verification", isValid)
    }

    @Test
    fun testRejectMissingFields() {
        val emptyMessage = JSONObject()
        assertFalse(ExtensionMessageAuth.verifyMessage(emptyMessage))

        val missingSig = JSONObject().apply {
            put("message_id", "id")
            put("timestamp", System.currentTimeMillis())
        }
        assertFalse(ExtensionMessageAuth.verifyMessage(missingSig))
    }
}
