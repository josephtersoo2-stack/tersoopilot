package com.multibrowser.antidetect.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts tokens and sensitive blobs with a non-exportable Android Keystore key. */
object TokenVault {
    private const val TAG = "TokenVault"
    private const val ALIAS = "tersoopilot_auth_v1"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        } catch (e: Exception) {
            when (e) {
                is KeyPermanentlyInvalidatedException,
                is UnrecoverableKeyException -> {
                    Log.w(TAG, "Keystore key invalidated; regenerating entry.", e)
                    store.deleteEntry(ALIAS)
                }
                else -> throw e
            }
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(value: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            "v1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Key permanently invalidated during encrypt; re-keying and retrying once.")
            resetKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            "v1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
    }

    fun decrypt(value: String): String {
        val bytes = Base64.decode(value.removePrefix("v1:"), Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid encrypted token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized
    fun resetKey() {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) {
                store.deleteEntry(ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore entry", e)
        }
    }
}
