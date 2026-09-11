package com.multibrowser.antidetect.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.multibrowser.antidetect.network.TokenVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object CookieEngine {
    private const val TAG = "CookieEngine"

    /**
     * Computes the SHA-256 hex string of the given UTF-8 text.
     */
    fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts a raw cookie JSON array into a tamper-evident envelope using Android Keystore AES-GCM.
     */
    fun encryptCookiePayload(rawJson: String): String {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return "[]"

        val count = try {
            JSONArray(trimmed).length()
        } catch (_: Exception) {
            0
        }

        val checksum = computeSha256(trimmed)
        val encryptedData = TokenVault.encrypt(trimmed)

        val envelope = JSONObject().apply {
            put("encrypted", true)
            put("v", 1)
            put("checksum", checksum)
            put("payload", encryptedData)
            put("count", count)
            put("createdAt", System.currentTimeMillis())
        }
        return envelope.toString()
    }

    /**
     * Decrypts an encrypted cookie envelope and validates its SHA-256 checksum.
     * If the input is legacy unencrypted JSON/Netscape string, returns it directly.
     */
    fun decryptCookiePayload(envelopeOrRaw: String): String {
        val trimmed = envelopeOrRaw.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return "[]"

        // Check if input is an encrypted envelope
        if (trimmed.startsWith("{") && trimmed.contains("\"encrypted\":true")) {
            try {
                val envelope = JSONObject(trimmed)
                val encryptedPayload = envelope.getString("payload")
                val expectedChecksum = envelope.getString("checksum")

                val decryptedJson = TokenVault.decrypt(encryptedPayload)
                val actualChecksum = computeSha256(decryptedJson)

                if (!expectedChecksum.equals(actualChecksum, ignoreCase = true)) {
                    Log.e(TAG, "Cookie integrity verification failed! Expected: $expectedChecksum, Actual: $actualChecksum")
                    throw SecurityException("Cookie payload has been tampered with or corrupted (checksum mismatch)")
                }

                return decryptedJson
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt cookie envelope: ${e.message}", e)
                throw e
            }
        }

        // Legacy unencrypted JSON or Netscape format
        return trimmed
    }

    /**
     * Verifies the cryptographic integrity of a cookie envelope without unpacking.
     */
    fun verifyCookieIntegrity(envelopeOrRaw: String): Boolean {
        val trimmed = envelopeOrRaw.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"encrypted\":true")) {
            // Unencrypted legacy format is considered valid for backwards compatibility
            return true
        }

        return try {
            val envelope = JSONObject(trimmed)
            val encryptedPayload = envelope.getString("payload")
            val expectedChecksum = envelope.getString("checksum")
            val decryptedJson = TokenVault.decrypt(encryptedPayload)
            val actualChecksum = computeSha256(decryptedJson)
            expectedChecksum.equals(actualChecksum, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts all cookies from a profile's isolated cookies.sqlite database into clean JSON.
     * @param encrypted When true, encrypts the output into a tamper-evident envelope.
     */
    fun exportCookiesToJson(context: Context, profileId: String, encrypted: Boolean = false): String {
        val profileDir = File(context.filesDir, "profiles/$profileId")
        val dbFile = File(profileDir, "cookies.sqlite")

        if (!dbFile.exists()) return "[]"

        val jsonArray = JSONArray()
        val db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return "[]"
        }

        try {
            val cursor = db.rawQuery(
                """
                SELECT host, name, value, path, expiry, isSecure, isHttpOnly, sameSite 
                FROM moz_cookies
                """.trimIndent(),
                null
            )
            while (cursor.moveToNext()) {
                val cookie = JSONObject().apply {
                    put("domain", cursor.getString(0))
                    put("host", cursor.getString(0))
                    put("name", cursor.getString(1))
                    put("value", cursor.getString(2))
                    put("path", cursor.getString(3))
                    put("expiry", cursor.getLong(4))
                    put("isSecure", cursor.getInt(5) == 1)
                    put("isHttpOnly", cursor.getInt(6) == 1)
                    put("sameSite", cursor.getInt(7))
                }
                jsonArray.put(cookie)
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting cookies from sqlite", e)
        } finally {
            db.close()
        }

        val rawJson = jsonArray.toString(2)
        return if (encrypted) encryptCookiePayload(rawJson) else rawJson
    }

    /**
     * Imports a JSON or Netscape-style cookie array into an existing or uninitialized profile directory.
     * Automatically handles encrypted envelopes and verifies integrity before insertion.
     */
    fun importCookiesFromJson(context: Context, profileId: String, rawJsonOrNetscape: String): Int {
        val profileDir = File(context.filesDir, "profiles/$profileId").apply {
            if (!exists()) mkdirs()
        }

        // 1. Decrypt and verify payload if encrypted envelope
        val decryptedPayload = try {
            decryptCookiePayload(rawJsonOrNetscape)
        } catch (e: Exception) {
            Log.e(TAG, "Aborting cookie import due to decryption / integrity failure", e)
            return 0
        }

        // 2. Normalize input: parse either standard JSON array or line-delimited Netscape format
        val cookiesArray = parseToStandardJsonArray(decryptedPayload)

        val dbFile = File(profileDir, "cookies.sqlite")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        var importedCount = 0

        try {
            // Guarantee GeckoView moz_cookies table exists
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS moz_cookies (
                    id INTEGER PRIMARY KEY,
                    originAttributes TEXT NOT NULL DEFAULT '',
                    name TEXT,
                    value TEXT,
                    host TEXT,
                    path TEXT,
                    expiry INTEGER,
                    lastAccessed INTEGER,
                    creationTime INTEGER,
                    isSecure INTEGER,
                    isHttpOnly INTEGER,
                    inBrowserElement INTEGER DEFAULT 0,
                    sameSite INTEGER DEFAULT 0,
                    rawSameSite INTEGER DEFAULT 0,
                    schemeMap INTEGER DEFAULT 0
                );
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS moz_basedomain ON moz_cookies (host, name, path, originAttributes);")

            db.beginTransaction()
            val nowMicroseconds = System.currentTimeMillis() * 1000

            for (i in 0 until cookiesArray.length()) {
                val c = cookiesArray.getJSONObject(i)
                val domain = c.optString("domain", c.optString("host", ""))
                val name = c.optString("name", "")
                val value = c.optString("value", "")
                val path = c.optString("path", "/")

                if (domain.isBlank() || name.isBlank()) continue

                val expiry = c.optLong("expiry", (System.currentTimeMillis() / 1000) + 31536000)

                val cv = ContentValues().apply {
                    put("host", domain)
                    put("name", name)
                    put("value", value)
                    put("path", path)
                    put("expiry", expiry)
                    put("isSecure", if (c.optBoolean("isSecure", false)) 1 else 0)
                    put("isHttpOnly", if (c.optBoolean("isHttpOnly", false)) 1 else 0)
                    put("sameSite", c.optInt("sameSite", 0))
                    put("lastAccessed", nowMicroseconds)
                    put("creationTime", nowMicroseconds)
                }

                if (db.insertWithOnConflict("moz_cookies", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                    importedCount++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }

        return importedCount
    }

    private fun parseToStandardJsonArray(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return try {
                JSONArray(trimmed)
            } catch (e: Exception) {
                JSONArray()
            }
        }

        // Netscape format fallback: domain \t flag \t path \t secure \t expiry \t name \t value
        val result = JSONArray()
        raw.lines().forEach { line ->
            val httpOnly = line.startsWith("#HttpOnly_")
            val l = line.removePrefix("#HttpOnly_").trimEnd('\r')
            if (l.isNotEmpty() && !l.startsWith("#")) {
                val parts = l.split("\t")
                if (parts.size >= 7) {
                    val obj = JSONObject().apply {
                        put("domain", parts[0])
                        put("path", parts[2])
                        put("isSecure", parts[3].equals("TRUE", ignoreCase = true))
                        put("expiry", parts[4].toLongOrNull() ?: ((System.currentTimeMillis() / 1000) + 31536000))
                        put("name", parts[5])
                        put("value", parts[6])
                        put("isHttpOnly", httpOnly)
                        put("sameSite", 0)
                    }
                    result.put(obj)
                }
            }
        }
        return result
    }
}
