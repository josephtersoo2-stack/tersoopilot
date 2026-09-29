package com.multibrowser.antidetect.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object CookieEngine {
    private const val TAG = "CookieEngine"

    /**
     * Locates the active GeckoView cookies.sqlite database file inside files/mozilla/ default directory
     */
    fun getGeckoCookiesDb(context: Context): File? {
        val mozillaDir = File(context.filesDir, "mozilla")
        if (mozillaDir.exists()) {
            val defaultDirs = mozillaDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".default") } ?: emptyList()
            // Prefer directory that already has cookies.sqlite
            val withDb = defaultDirs.firstOrNull { File(it, "cookies.sqlite").exists() }
            if (withDb != null) return File(withDb, "cookies.sqlite")
            if (defaultDirs.isNotEmpty()) return File(defaultDirs.first(), "cookies.sqlite")
        }
        return null
    }

    /**
     * Converts a profile UUID or string identifier into GeckoView's internal hex contextId prefix.
     * GeckoView maps contextId to: "^geckoViewUserContextId=gvctx<hex>"
     */
    fun getOriginAttributesPrefix(id: String): String {
        return "^geckoViewSessionContextId=$id"
    }

    fun getLegacyOriginAttributesPrefix(id: String): String {
        val hex = id.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return "^geckoViewUserContextId=gvctx$hex"
    }

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

        return trimmed
    }

    /**
     * Verifies the cryptographic integrity of a cookie envelope without unpacking.
     */
    fun verifyCookieIntegrity(envelopeOrRaw: String): Boolean {
        val trimmed = envelopeOrRaw.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"encrypted\":true")) {
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
     * Extracts all cookies for a profile from GeckoView's moz_cookies database.
     * Matches on originAttributes using the profileId and optional altId (e.g. cloudSyncId).
     */
    fun exportCookiesToJson(
        context: Context,
        profileId: String,
        altId: String = "",
        encrypted: Boolean = false
    ): String {
        val dbFile = getGeckoCookiesDb(context) ?: File(context.filesDir, "profiles/$profileId/cookies.sqlite")
        if (!dbFile.exists()) return "[]"

        val jsonArray = JSONArray()
        val db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open cookies db for read: ${e.message}")
            return "[]"
        }

        try {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()

            if (profileId.isNotBlank()) {
                clauses.add("originAttributes LIKE ?")
                args.add("%geckoViewSessionContextId=$profileId%")

                clauses.add("originAttributes LIKE ?")
                args.add("%$profileId%")

                clauses.add("originAttributes LIKE ?")
                args.add("%${getLegacyOriginAttributesPrefix(profileId)}%")
            }

            if (altId.isNotBlank() && altId != profileId) {
                clauses.add("originAttributes LIKE ?")
                args.add("%geckoViewSessionContextId=$altId%")

                clauses.add("originAttributes LIKE ?")
                args.add("%$altId%")
            }

            var cursor = if (clauses.isNotEmpty()) {
                val whereClauses = clauses.joinToString(" OR ")
                db.rawQuery(
                    """
                    SELECT host, name, value, path, expiry, isSecure, isHttpOnly, sameSite 
                    FROM moz_cookies
                    WHERE $whereClauses
                    """.trimIndent(),
                    args.toTypedArray()
                )
            } else {
                db.rawQuery(
                    """
                    SELECT host, name, value, path, expiry, isSecure, isHttpOnly, sameSite 
                    FROM moz_cookies
                    LIMIT 2000
                    """.trimIndent(),
                    null
                )
            }

            // Fallback: If no partitioned cookies matched, check for default/unpartitioned cookies
            if (cursor.count == 0) {
                cursor.close()
                cursor = db.rawQuery(
                    """
                    SELECT host, name, value, path, expiry, isSecure, isHttpOnly, sameSite 
                    FROM moz_cookies
                    WHERE originAttributes = '' OR originAttributes IS NULL
                    LIMIT 2000
                    """.trimIndent(),
                    null
                )
            }

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
            Log.i(TAG, "Exported ${jsonArray.length()} cookies from GeckoView for profile $profileId (alt: $altId)")
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting cookies from sqlite", e)
        } finally {
            db.close()
        }

        val rawJson = jsonArray.toString(2)
        return if (encrypted) encryptCookiePayload(rawJson) else rawJson
    }

    /**
     * Imports a JSON or Netscape-style cookie array into GeckoView's moz_cookies database,
     * associating them with the profile's contextId via originAttributes.
     */
    fun importCookiesFromJson(
        context: Context,
        profileId: String,
        rawJsonOrNetscape: String
    ): Int {
        if (rawJsonOrNetscape.isBlank() || rawJsonOrNetscape.trim() == "[]") return 0

        // 1. Decrypt and verify payload if encrypted envelope
        val decryptedPayload = try {
            decryptCookiePayload(rawJsonOrNetscape)
        } catch (e: Exception) {
            Log.e(TAG, "Aborting cookie import due to decryption / integrity failure", e)
            return 0
        }

        // 2. Normalize input: parse either standard JSON array or line-delimited Netscape format
        val cookiesArray = parseToStandardJsonArray(decryptedPayload)
        if (cookiesArray.length() == 0) return 0

        val dbFile = getGeckoCookiesDb(context) ?: run {
            val mozillaDir = File(context.filesDir, "mozilla")
            val defaultDir = mozillaDir.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith(".default") }
                ?: File(mozillaDir, "default.default").apply { mkdirs() }
            File(defaultDir, "cookies.sqlite")
        }

        val db = try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open or create cookies db: ${e.message}", e)
            return 0
        }

        var importedCount = 0
        val prefix = getOriginAttributesPrefix(profileId)

        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS moz_cookies (
                    id INTEGER PRIMARY KEY,
                    baseDomain TEXT,
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

            var hasBaseDomain = false
            try {
                val infoCursor = db.rawQuery("PRAGMA table_info(moz_cookies);", null)
                while (infoCursor.moveToNext()) {
                    val colName = infoCursor.getString(1)
                    if (colName.equals("baseDomain", ignoreCase = true)) {
                        hasBaseDomain = true
                        break
                    }
                }
                infoCursor.close()
            } catch (e: Exception) {}

            db.beginTransaction()
            val nowMicroseconds = System.currentTimeMillis() * 1000
            val currentSeconds = System.currentTimeMillis() / 1000

            for (i in 0 until cookiesArray.length()) {
                val c = cookiesArray.getJSONObject(i)
                val rawDomain = c.optString("domain", c.optString("host", "")).trim()
                val name = c.optString("name", "").trim()
                val value = c.optString("value", "")
                val path = c.optString("path", "/").trim().ifBlank { "/" }

                if (rawDomain.isBlank() || name.isBlank()) continue

                // Normalize domain for Google, YouTube, and major social networks
                val domain = if ((rawDomain.contains("google.") || rawDomain.contains("youtube.") || 
                                  rawDomain.contains("facebook.") || rawDomain.contains("twitter.") || 
                                  rawDomain.contains("x.com") || rawDomain.contains("instagram.") ||
                                  rawDomain.contains("tiktok.")) && 
                                  !rawDomain.startsWith(".") && 
                                  !rawDomain.contains("accounts.") && 
                                  !rawDomain.contains("myaccount.") && 
                                  !rawDomain.contains("apis.")) {
                    ".$rawDomain"
                } else {
                    rawDomain
                }

                // Ensure session cookies get at least 1-year future lifespan so sessions don't die on restart
                val rawExpiry = c.optLong("expiry", 0L)
                val effectiveExpiry = if (rawExpiry <= currentSeconds) {
                    currentSeconds + 31536000L
                } else {
                    rawExpiry
                }

                val isSecure = when {
                    c.has("isSecure") -> if (c.optBoolean("isSecure")) 1 else 0
                    c.has("secure") -> if (c.optBoolean("secure")) 1 else 0
                    name.startsWith("__Secure-") || name.startsWith("__Host-") -> 1
                    else -> 0
                }

                val isHttpOnly = when {
                    c.has("isHttpOnly") -> if (c.optBoolean("isHttpOnly")) 1 else 0
                    c.has("httpOnly") -> if (c.optBoolean("httpOnly")) 1 else 0
                    name.equals("SID", true) || name.equals("HSID", true) || name.equals("SSID", true) ||
                    name.equals("LOGIN_INFO", true) || name.equals("auth_token", true) ||
                    name.equals("xs", true) || name.equals("c_user", true) || 
                    name.equals("sessionid", true) -> 1
                    else -> 0
                }

                val cv = ContentValues().apply {
                    put("originAttributes", prefix)
                    put("host", domain)
                    put("name", name)
                    put("value", value)
                    put("path", path)
                    put("expiry", effectiveExpiry)
                    put("isSecure", isSecure)
                    put("isHttpOnly", isHttpOnly)
                    put("sameSite", c.optInt("sameSite", 0))
                    put("lastAccessed", nowMicroseconds)
                    put("creationTime", nowMicroseconds)
                    if (hasBaseDomain) {
                        val clean = domain.removePrefix(".")
                        val parts = clean.split(".")
                        val base = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else clean
                        put("baseDomain", base)
                    }
                }

                if (db.insertWithOnConflict("moz_cookies", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                    importedCount++
                }
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "Imported $importedCount cookies into GeckoView for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing cookies to sqlite: ${e.message}", e)
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }

        return importedCount
    }

    /**
     * Reads all live cookies from GeckoView's cookies.sqlite for the profile,
     * updates Room database, and pushes the cookies to the Django backend.
     */
    suspend fun syncProfileCookies(context: Context, profile: ProfileEntity): Int = withContext(Dispatchers.IO) {
        try {
            val jsonCookies = exportCookiesToJson(context, profile.id, profile.cloudSyncId)
            val jsonArray = try { JSONArray(jsonCookies) } catch (_: Exception) { JSONArray() }
            val count = jsonArray.length()

            val finalCount = if (count > 0) count else profile.cookieCount
            val finalEncrypted = if (count > 0) encryptCookiePayload(jsonCookies) else profile.cookiesJson

            // 1. Update local Room database with hardware-backed TokenVault AES-GCM
            val db = AppDatabase.getDatabase(context)
            db.dao.updateSessionData(
                id = profile.id,
                cookiesJson = finalEncrypted,
                historyJson = profile.historyJson,
                tabsJson = profile.tabsJson,
                cookieCount = finalCount
            )
            Log.i(TAG, "Updated local profile '${profile.name}' cookie count: $finalCount")

            // 2. Push to backend over authenticated HTTPS
            val targetBackendId = profile.cloudSyncId.ifBlank { profile.id }
            if (targetBackendId.isNotBlank() && count > 0) {
                val syncResult = CookieSyncDispatcher.syncCookiesToBackend(targetBackendId, jsonCookies)
                Log.i(TAG, "Pushed $count cookies to backend for '${profile.name}' (ID: $targetBackendId): ${syncResult.isSuccess}")
            }
            finalCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies for profile '${profile.name}'", e)
            0
        }
    }

    suspend fun syncProfileCookiesById(context: Context, profileId: String): Int = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val profile = db.dao.getProfileById(profileId) ?: run {
                db.dao.getAllProfiles().first().firstOrNull { it.cloudSyncId == profileId }
            }
            if (profile != null) {
                syncProfileCookies(context, profile)
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync profile cookies by ID $profileId", e)
            0
        }
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
