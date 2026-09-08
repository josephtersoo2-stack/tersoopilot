package com.multibrowser.antidetect.sync

import com.multibrowser.antidetect.network.CookieApiService
import com.multibrowser.antidetect.network.CookieSyncPayload
import com.multibrowser.antidetect.network.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object CookieSyncDispatcher {

    private val api: CookieApiService
        get() = RetrofitInstance.retrofit.create(CookieApiService::class.java)

    /**
     * Parses raw JSON or Netscape cookies, formats them into a standard dictionary list,
     * and synchronizes them with the Django backend.
     */
    suspend fun syncCookiesToBackend(profileId: String, rawCookies: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (rawCookies.isBlank()) return@withContext Result.success(0)

            val jsonArray = if (rawCookies.trim().startsWith("[")) {
                JSONArray(rawCookies.trim())
            } else {
                // Parse Netscape line-by-line fallback
                val array = JSONArray()
                rawCookies.lines().forEach { line ->
                    val l = line.trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) {
                        val parts = l.split("\t")
                        if (parts.size >= 7) {
                            val obj = org.json.JSONObject().apply {
                                put("domain", parts[0])
                                put("path", parts[2])
                                put("isSecure", parts[3].equals("TRUE", ignoreCase = true))
                                put("expiry", parts[4].toLongOrNull() ?: ((System.currentTimeMillis() / 1000) + 31536000))
                                put("name", parts[5])
                                put("value", parts[6])
                            }
                            array.put(obj)
                        }
                    }
                }
                array
            }

            val cookieList = mutableListOf<Map<String, Any>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.get(key)
                }
                cookieList.add(map)
            }

            val response = api.pushCookiesToBackend(profileId, CookieSyncPayload(cookies = cookieList))
            Result.success(response.importedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
