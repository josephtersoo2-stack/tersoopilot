package com.multibrowser.antidetect.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CookieEngine {

    /**
     * Extracts all cookies from a profile's isolated cookies.sqlite database into clean JSON.
     */
    fun exportCookiesToJson(context: Context, profileId: String): String {
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
            e.printStackTrace()
        } finally {
            db.close()
        }
        return jsonArray.toString(2)
    }

    /**
     * Imports a JSON or Netscape-style cookie array into an existing or uninitialized profile directory.
     */
    fun importCookiesFromJson(context: Context, profileId: String, rawJsonOrNetscape: String): Int {
        val profileDir = File(context.filesDir, "profiles/$profileId").apply {
            if (!exists()) mkdirs()
        }

        // 1. Flush any orphan WAL files that would cause SQLite locks
        try {
            File(profileDir, "cookies.sqlite-wal").delete()
            File(profileDir, "cookies.sqlite-shm").delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Normalize input: parse either standard JSON array or line-delimited Netscape format
        val cookiesArray = parseToStandardJsonArray(rawJsonOrNetscape)

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

                // Expiry calculation: default to 1 year in seconds if missing
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

                db.insertWithOnConflict("moz_cookies", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                importedCount++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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
        trimmed.lines().forEach { line ->
            val l = line.trim()
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
                        put("isHttpOnly", false)
                        put("sameSite", 0)
                    }
                    result.put(obj)
                }
            }
        }
        return result
    }
}
