package com.multibrowser.antidetect.engine

import android.util.Log
import com.multibrowser.antidetect.network.TokenVault

/**
 * Hardware-backed credential vault for isolating sensitive credentials (such as proxy
 * usernames and passwords) from database dumps, profile exports, and plain memory objects.
 */
object CredentialVault {
    private const val TAG = "CredentialVault"
    private const val ENCRYPTED_PREFIX = "v1:"

    /**
     * Encrypts a plaintext credential string with Android KeyStore AES-GCM.
     * If the string is already encrypted, returns it unchanged.
     */
    fun encrypt(value: String): String {
        if (value.isBlank() || value.startsWith(ENCRYPTED_PREFIX)) {
            return value
        }
        return try {
            TokenVault.encrypt(value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt credential, returning original: ${e.message}")
            value
        }
    }

    /**
     * Decrypts an encrypted credential string.
     * If the string is plaintext (e.g. from legacy records), returns it as-is.
     */
    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        if (!value.startsWith(ENCRYPTED_PREFIX)) {
            return value
        }
        return try {
            TokenVault.decrypt(value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt credential: ${e.message}")
            ""
        }
    }
}
