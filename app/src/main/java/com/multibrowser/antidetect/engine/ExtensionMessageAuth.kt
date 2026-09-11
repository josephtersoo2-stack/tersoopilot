package com.multibrowser.antidetect.engine

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Provides cryptographic message authentication for WebExtension native messaging.
 * Signs outgoing payloads and validates incoming responses to prevent injection or forgery.
 */
object ExtensionMessageAuth {
    // Isolated local shared secret for extension native messaging bridge
    private const val BRIDGE_SECRET = "tersoo_antidetect_ext_bridge_secret_v1"
    private const val REPLAY_TOLERANCE_MS = 60_000L

    /**
     * Signs an outgoing payload by attaching message_id, timestamp, and HMAC-SHA256 signature.
     */
    fun signMessage(payload: JSONObject): JSONObject {
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val action = payload.optString("action", "UNKNOWN")

        payload.put("message_id", messageId)
        payload.put("timestamp", timestamp)
        val dataToSign = "$messageId:$timestamp:$action"
        payload.put("signature", computeHmac(dataToSign, BRIDGE_SECRET))
        return payload
    }

    /**
     * Verifies that an incoming message from the extension contains a valid timestamp
     * within the replay window and a valid HMAC-SHA256 signature.
     */
    fun verifyMessage(message: JSONObject): Boolean {
        val messageId = message.optString("message_id")
        val timestamp = message.optLong("timestamp", 0L)
        val signature = message.optString("signature")
        val action = message.optString("action")

        if (messageId.isBlank() || timestamp == 0L || signature.isBlank()) {
            return false
        }

        // Enforce replay window
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) > REPLAY_TOLERANCE_MS) {
            return false
        }

        val expectedData = "$messageId:$timestamp:$action"
        val expectedSig = computeHmac(expectedData, BRIDGE_SECRET)
        return expectedSig == signature
    }

    fun computeHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
