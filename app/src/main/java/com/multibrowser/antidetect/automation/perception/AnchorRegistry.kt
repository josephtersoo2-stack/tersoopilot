package com.multibrowser.antidetect.automation.perception

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.multibrowser.antidetect.network.GhostPilotApiService
import java.io.File

/**
 * Normalized 0..1000 integer spatial anchor representing a fixed UI control.
 */
data class NormalizedPoint(
    val x: Int,
    val y: Int,
    val calibratedAt: Long = System.currentTimeMillis()
)

/**
 * Calibrated natural scroll & discovery settings synced with backend visual model.
 */
data class CalibrationSettings(
    val initialScrollCount: Int = 2,
    val naturalScrollDelayMin: Float = 1.5f,
    val naturalScrollDelayMax: Float = 2.8f,
    val videosPerBatch: Int = 10,
    val maxBatchesPerKeyword: Int = 2,
    val scrollDurationMin: Long = 500L,
    val scrollDurationMax: Long = 750L,
    val overshootScrollEnabled: Boolean = true
)

/**
 * Persistent cached spatial anchor registry for YouTube and core mobile controls.
 * Supports exact JSON file persistence (youtube_spatial_anchors.json) and SharedPreferences
 * with 12-hour Time-To-Live (TTL) for zero-cost, sub-50ms deterministic targeting.
 */
class AnchorRegistry(private val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()
    private val anchorFile: File by lazy {
        File(context.applicationContext.filesDir, ANCHOR_FILE_NAME)
    }

    var currentSettings: CalibrationSettings = loadCachedSettings()
        private set

    init {
        // Load anchors & settings from file if present, or write default canonical file
        if (anchorFile.exists()) {
            loadFromFile()
        } else {
            saveToFile()
        }
    }

    private fun loadCachedSettings(): CalibrationSettings {
        return try {
            val json = prefs.getString("calibration_settings", null)
            if (!json.isNullOrBlank()) {
                gson.fromJson(json, CalibrationSettings::class.java) ?: CalibrationSettings()
            } else {
                CalibrationSettings()
            }
        } catch (e: Exception) {
            CalibrationSettings()
        }
    }

    companion object {
        private const val TAG = "AnchorRegistry"
        private const val PREFS_NAME = "ghostpilot_spatial_anchors"
        const val ANCHOR_FILE_NAME = "youtube_spatial_anchors.json"
        const val DEFAULT_TTL_MS = 12 * 3600 * 1000L // 12 Hours

        @Volatile
        private var instance: AnchorRegistry? = null

        fun init(context: Context): AnchorRegistry {
            return instance ?: synchronized(this) {
                instance ?: AnchorRegistry(context.applicationContext).also { instance = it }
            }
        }

        fun get(): AnchorRegistry? = instance

        fun getSettings(): CalibrationSettings {
            return instance?.currentSettings ?: CalibrationSettings()
        }

        fun updateSettings(settings: CalibrationSettings) {
            instance?.updateSettings(settings)
        }

        fun getAnchor(key: String, allowExpired: Boolean = true): NormalizedPoint {
            val reg = instance
            if (reg != null) {
                val found = reg.getAnchor(key, allowExpired = true)
                if (found != null) return found
            }
            val canonical = getCanonicalDefault(key)
            return NormalizedPoint(canonical.first, canonical.second, calibratedAt = 0L)
        }

        fun getScreenPoint(
            key: String,
            viewWidth: Float,
            viewHeight: Float,
            randomizeJitter: Boolean = true
        ): ScreenPoint {
            val anchor = getAnchor(key)
            val actualW = if (viewWidth > 10f) viewWidth else 1080f
            val actualH = if (viewHeight > 10f) viewHeight else 2400f
            val px = (anchor.x / 1000f) * actualW
            val py = (anchor.y / 1000f) * actualH
            return if (randomizeJitter) {
                val jitterX = (Math.random().toFloat() * 8f - 4f)
                val jitterY = (Math.random().toFloat() * 8f - 4f)
                ScreenPoint((px + jitterX).coerceIn(0f, actualW), (py + jitterY).coerceIn(0f, actualH))
            } else {
                ScreenPoint(px, py)
            }
        }

        fun saveCalibration(anchors: Map<String, Pair<Int, Int>>?) {
            if (anchors.isNullOrEmpty()) return
            instance?.saveAnchors(anchors)
        }

        // Canonical 20 Anchor Element Keys
        const val SEARCH_BUTTON_HOME = "SEARCH_BUTTON_HOME"
        const val SEARCH_BUTTON_WATCH = "SEARCH_BUTTON_WATCH"
        const val SEARCH_BUTTON_RESULTS = "SEARCH_BUTTON_RESULTS"
        const val SEARCH_INPUT = "SEARCH_INPUT"
        const val SEARCH_CLEAR = "SEARCH_CLEAR"
        const val SEARCH_SUBMIT = "SEARCH_SUBMIT"
        const val VIDEO_MENU_DOTS = "VIDEO_MENU_DOTS"
        const val COMMENTS_SECTION = "COMMENTS_SECTION"
        const val DESCRIPTION_EXPAND = "DESCRIPTION_EXPAND"
        const val LIKE_BUTTON = "LIKE_BUTTON"
        const val DISLIKE_BUTTON = "DISLIKE_BUTTON"
        const val SHARE_BUTTON = "SHARE_BUTTON"
        const val SUBSCRIBE_BUTTON = "SUBSCRIBE_BUTTON"
        const val CHANNEL_AVATAR = "CHANNEL_AVATAR"
        const val NAV_HOME = "NAV_HOME"
        const val NAV_SHORTS = "NAV_SHORTS"
        const val NAV_SUBSCRIPTIONS = "NAV_SUBSCRIPTIONS"
        const val NAV_PROFILE = "NAV_PROFILE"
        const val PLAYER_PLAY_PAUSE = "PLAYER_PLAY_PAUSE"
        const val PLAYER_QUALITY_MENU = "PLAYER_QUALITY_MENU"

        // Canonical Normalized Fallbacks (0..1000 scale)
        private val CANONICAL_DEFAULTS = mapOf(
            SEARCH_BUTTON_HOME to Pair(930, 28),
            SEARCH_BUTTON_WATCH to Pair(930, 28),
            SEARCH_BUTTON_RESULTS to Pair(935, 30),
            SEARCH_INPUT to Pair(480, 28),
            SEARCH_CLEAR to Pair(860, 28),
            SEARCH_SUBMIT to Pair(935, 28),
            VIDEO_MENU_DOTS to Pair(960, 320),
            COMMENTS_SECTION to Pair(500, 520),
            DESCRIPTION_EXPAND to Pair(900, 420),
            LIKE_BUTTON to Pair(220, 460),
            DISLIKE_BUTTON to Pair(380, 460),
            SHARE_BUTTON to Pair(540, 460),
            SUBSCRIBE_BUTTON to Pair(870, 380),
            CHANNEL_AVATAR to Pair(60, 380),
            NAV_HOME to Pair(125, 965),
            NAV_SHORTS to Pair(375, 965),
            NAV_SUBSCRIPTIONS to Pair(625, 965),
            NAV_PROFILE to Pair(875, 965),
            PLAYER_PLAY_PAUSE to Pair(500, 140),
            PLAYER_QUALITY_MENU to Pair(940, 60)
        )

        /**
         * Maps aliases, UI element action names, and legacy keys to canonical anchor keys.
         */
        fun resolveKey(key: String): String {
            val clean = key.trim().uppercase()
            return when (clean) {
                "SEARCH", "SEARCH_BUTTON", "SEARCH_ICON", "SEARCH_BUTTON_HOME" -> SEARCH_BUTTON_HOME
                "SEARCH_WATCH", "SEARCH_PLAYER", "SEARCH_BUTTON_WATCH", "SEARCH_WATCH_BUTTON", "SEARCH_PLAYING_VIDEO" -> SEARCH_BUTTON_WATCH
                "SEARCH_RESULTS_BUTTON", "SEARCH_BUTTON_RESULTS" -> SEARCH_BUTTON_RESULTS
                "SEARCH_INPUT_BOX", "SEARCH_BOX", "SEARCH_BAR" -> SEARCH_INPUT
                "SEARCH_CLEAR", "CLEAR", "CLEAR_SEARCH" -> SEARCH_CLEAR
                "SEARCH_SUBMIT", "SUBMIT_SEARCH", "SUBMIT", "SUBMIT_BUTTON", "COMMENT_SUBMIT" -> SEARCH_SUBMIT
                "DESCRIPTION", "EXPAND_DESCRIPTION", "YT_EXPAND_DESCRIPTION", "DESCRIPTION_BUTTON", "DESCRIPTION_EXPANDER" -> DESCRIPTION_EXPAND
                "COMMENTS", "COMMENT", "COMMENTS_SECTION", "COMMENTS_BUTTON", "COMMENTS_ENTRY", "COMMENT_INPUT", "COMMENT_INPUT_BOX" -> COMMENTS_SECTION
                "LIKE", "LIKE_VIDEO", "YT_LIKE_VIDEO" -> LIKE_BUTTON
                "DISLIKE", "DISLIKE_VIDEO", "YT_DISLIKE_VIDEO" -> DISLIKE_BUTTON
                "SHARE", "SHARE_VIDEO", "YT_SHARE_VIDEO" -> SHARE_BUTTON
                "SUBSCRIBE", "SUBSCRIBE_CHANNEL", "YT_SUBSCRIBE_CHANNEL" -> SUBSCRIBE_BUTTON
                "CHANNEL", "CHANNEL_AVATAR", "AVATAR" -> CHANNEL_AVATAR
                "HOME", "NAV_HOME", "TAB_HOME" -> NAV_HOME
                "SHORTS", "NAV_SHORTS", "TAB_SHORTS", "SHORTS_TAB" -> NAV_SHORTS
                "SUBSCRIPTIONS", "NAV_SUBSCRIPTIONS", "TAB_SUBSCRIPTIONS" -> NAV_SUBSCRIPTIONS
                "YOU", "PROFILE", "NAV_PROFILE", "TAB_YOU", "PROFILE_TAB" -> NAV_PROFILE
                "PLAY", "PAUSE", "PLAY_PAUSE", "PLAYER_PLAY_PAUSE" -> PLAYER_PLAY_PAUSE
                "SETTINGS", "GEAR", "QUALITY", "PLAYER_QUALITY", "PLAYER_QUALITY_MENU" -> PLAYER_QUALITY_MENU
                "MENU", "DOTS", "3DOTS", "THREE_DOTS", "VIDEO_MENU", "VIDEO_MENU_DOTS" -> VIDEO_MENU_DOTS
                else -> clean
            }
        }

        fun getCanonicalDefault(key: String): Pair<Int, Int> {
            val clean = resolveKey(key)
            return CANONICAL_DEFAULTS[clean] ?: CANONICAL_DEFAULTS[key.trim().uppercase()] ?: Pair(500, 500)
        }

        /**
         * Returns true only if the key (or its resolved alias) maps to a known canonical anchor.
         * Use this before calling getScreenPoint with an unvalidated string to prevent
         * XPath expressions / CSS selectors from silently falling back to center-of-screen.
         */
        fun isKnownAnchor(key: String): Boolean {
            if (key.isBlank()) return false
            val resolved = resolveKey(key)
            return CANONICAL_DEFAULTS.containsKey(resolved)
        }
    }

    @Volatile
    private var lastFileModifiedTime: Long = 0L

    fun checkReloadIfFileModified() {
        try {
            if (anchorFile.exists()) {
                val modTime = anchorFile.lastModified()
                if (modTime > lastFileModifiedTime) {
                    lastFileModifiedTime = modTime
                    loadFromFile()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Retrieves the stored normalized anchor point for the given key.
     * Calibrated values are permanently preserved; uncalibrated keys fall back to canonical defaults.
     */
    fun getAnchor(key: String, allowExpired: Boolean = true): NormalizedPoint? {
        checkReloadIfFileModified()
        val resolved = resolveKey(key)
        val rawX = prefs.getInt("${resolved}_x", -1)
        val rawY = prefs.getInt("${resolved}_y", -1)
        val timestamp = prefs.getLong("${resolved}_calibrated_at", 0L)

        if (rawX in 0..1000 && rawY in 0..1000) {
            return NormalizedPoint(rawX, rawY, timestamp)
        }

        // Return canonical default if uncalibrated
        val defaultCoord = CANONICAL_DEFAULTS[resolved] ?: CANONICAL_DEFAULTS[key.trim().uppercase()]
        return if (defaultCoord != null) {
            NormalizedPoint(defaultCoord.first, defaultCoord.second, calibratedAt = 0L)
        } else null
    }

    /**
     * Updates and saves calibration settings.
     */
    fun updateSettings(newSettings: CalibrationSettings) {
        currentSettings = newSettings
        prefs.edit().putString("calibration_settings", gson.toJson(newSettings)).apply()
        saveToFile()
        Log.i(TAG, "Updated calibrated natural scroll settings: $newSettings")
    }

    /**
     * Stores a single normalized anchor point and syncs to JSON file.
     */
    fun saveAnchor(key: String, x: Int, y: Int) {
        val resolved = resolveKey(key)
        val clampedX = x.coerceIn(0, 1000)
        val clampedY = y.coerceIn(0, 1000)
        prefs.edit()
            .putInt("${resolved}_x", clampedX)
            .putInt("${resolved}_y", clampedY)
            .putLong("${resolved}_calibrated_at", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Saved spatial anchor: $resolved -> ($clampedX, $clampedY)")
        saveToFile()
    }

    /**
     * Batch updates anchors from a calibration map and updates the JSON file.
     */
    fun saveAnchors(anchors: Map<String, Pair<Int, Int>>) {
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        for ((k, point) in anchors) {
            val resolved = resolveKey(k)
            val clampedX = point.first.coerceIn(0, 1000)
            val clampedY = point.second.coerceIn(0, 1000)
            editor.putInt("${resolved}_x", clampedX)
            editor.putInt("${resolved}_y", clampedY)
            editor.putLong("${resolved}_calibrated_at", now)
        }
        editor.apply()
        Log.i(TAG, "Batch updated ${anchors.size} spatial anchors at timestamp $now")
        saveToFile()
    }

    /**
     * Loads spatial anchors and settings from the persistent JSON file on device storage.
     */
    fun loadFromFile(): Boolean {
        return try {
            if (!anchorFile.exists()) return false
            val jsonText = anchorFile.readText()
            val jsonObject = gson.fromJson(jsonText, JsonObject::class.java)
            lastFileModifiedTime = anchorFile.lastModified()
            syncFromJsonObject(jsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load spatial anchors from file: ${e.message}")
            false
        }
    }

    /**
     * Synchronizes registry state from a JsonObject containing anchors and settings.
     * Fully safe against nulls and non-primitive values.
     */
    fun syncFromJsonObject(root: JsonObject): Boolean {
        return try {
            val editor = prefs.edit()
            val now = System.currentTimeMillis()
            var count = 0

            val anchorsObj = if (root.has("anchors") && !root.get("anchors").isJsonNull && root.get("anchors").isJsonObject) {
                root.getAsJsonObject("anchors")
            } else null

            if (anchorsObj != null) {
                for (entry in anchorsObj.entrySet()) {
                    val resolved = resolveKey(entry.key)
                    if (entry.value == null || entry.value.isJsonNull || !entry.value.isJsonObject) {
                        editor.remove("${resolved}_x")
                        editor.remove("${resolved}_y")
                        continue
                    }
                    val spotObj = entry.value.asJsonObject
                    val xElem = spotObj.get("x")
                    val yElem = spotObj.get("y")

                    val x = if (xElem != null && !xElem.isJsonNull && xElem.isJsonPrimitive) {
                        try { xElem.asInt } catch (_: Exception) { null }
                    } else null

                    val y = if (yElem != null && !yElem.isJsonNull && yElem.isJsonPrimitive) {
                        try { yElem.asInt } catch (_: Exception) { null }
                    } else null

                    if (x != null && y != null) {
                        editor.putInt("${resolved}_x", x.coerceIn(0, 1000))
                        editor.putInt("${resolved}_y", y.coerceIn(0, 1000))
                        editor.putLong("${resolved}_calibrated_at", now)
                        count++
                    } else {
                        editor.remove("${resolved}_x")
                        editor.remove("${resolved}_y")
                    }
                }
            }

            val settingsObj = if (root.has("settings") && !root.get("settings").isJsonNull && root.get("settings").isJsonObject) {
                root.getAsJsonObject("settings")
            } else null

            if (settingsObj != null) {
                fun getIntSafe(k: String, def: Int): Int {
                    val e = settingsObj.get(k)
                    return if (e != null && !e.isJsonNull && e.isJsonPrimitive) {
                        try { e.asInt } catch (_: Exception) { def }
                    } else def
                }
                fun getFloatSafe(k: String, def: Float): Float {
                    val e = settingsObj.get(k)
                    return if (e != null && !e.isJsonNull && e.isJsonPrimitive) {
                        try { e.asFloat } catch (_: Exception) { def }
                    } else def
                }
                fun getLongSafe(k: String, def: Long): Long {
                    val e = settingsObj.get(k)
                    return if (e != null && !e.isJsonNull && e.isJsonPrimitive) {
                        try { e.asLong } catch (_: Exception) { def }
                    } else def
                }
                fun getBoolSafe(k: String, def: Boolean): Boolean {
                    val e = settingsObj.get(k)
                    return if (e != null && !e.isJsonNull && e.isJsonPrimitive) {
                        try { e.asBoolean } catch (_: Exception) { def }
                    } else def
                }

                currentSettings = CalibrationSettings(
                    initialScrollCount = getIntSafe("initial_scroll_count", currentSettings.initialScrollCount),
                    naturalScrollDelayMin = getFloatSafe("natural_scroll_delay_min", currentSettings.naturalScrollDelayMin),
                    naturalScrollDelayMax = getFloatSafe("natural_scroll_delay_max", currentSettings.naturalScrollDelayMax),
                    videosPerBatch = getIntSafe("videos_per_batch", currentSettings.videosPerBatch),
                    maxBatchesPerKeyword = getIntSafe("max_batches_per_keyword", currentSettings.maxBatchesPerKeyword),
                    scrollDurationMin = getLongSafe("scroll_duration_min", currentSettings.scrollDurationMin),
                    scrollDurationMax = getLongSafe("scroll_duration_max", currentSettings.scrollDurationMax),
                    overshootScrollEnabled = getBoolSafe("overshoot_scroll_enabled", currentSettings.overshootScrollEnabled)
                )
                editor.putString("calibration_settings", gson.toJson(currentSettings))
            }

            editor.apply()
            Log.i(TAG, "Successfully synced $count spatial anchors and calibrated settings from JSON model.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing calibration JSON: ${e.message}", e)
            false
        }
    }

    /**
     * Synchronizes spatial anchors and settings dynamically from the backend control plane.
     */
    suspend fun syncWithBackend(api: GhostPilotApiService): Boolean {
        return try {
            val response = api.getCalibration("YOUTUBE")
            if (response.has("anchors")) {
                syncFromJsonObject(response)
                saveToFile()
                Log.i(TAG, "Successfully synced spatial anchors & settings from backend calibration model.")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Backend calibration sync notice (using cached file): ${e.message}")
            false
        }
    }

    /**
     * Uploads the device's currently saved anchors and settings to the backend.
     */
    suspend fun uploadToBackend(api: GhostPilotApiService): Boolean {
        return try {
            val anchorsMap = mutableMapOf<String, Any>()
            val allKeys = mutableSetOf<String>()
            allKeys.addAll(CANONICAL_DEFAULTS.keys)
            for (prefKey in prefs.all.keys) {
                if (prefKey.endsWith("_x")) {
                    allKeys.add(prefKey.removeSuffix("_x"))
                }
            }
            for (key in allKeys) {
                val point = getAnchor(key, allowExpired = true)
                if (point != null) {
                    anchorsMap[key] = mapOf(
                        "x" to point.x,
                        "y" to point.y,
                        "label" to key
                    )
                }
            }

            val payload = mapOf(
                "platform" to "YOUTUBE",
                "anchors" to anchorsMap,
                "settings" to mapOf(
                    "initial_scroll_count" to currentSettings.initialScrollCount,
                    "natural_scroll_delay_min" to currentSettings.naturalScrollDelayMin,
                    "natural_scroll_delay_max" to currentSettings.naturalScrollDelayMax,
                    "videos_per_batch" to currentSettings.videosPerBatch,
                    "max_batches_per_keyword" to currentSettings.maxBatchesPerKeyword,
                    "scroll_duration_min" to currentSettings.scrollDurationMin,
                    "scroll_duration_max" to currentSettings.scrollDurationMax,
                    "overshoot_scroll_enabled" to currentSettings.overshootScrollEnabled
                )
            )

            val resp = api.postCalibration(payload)
            Log.i(TAG, "Successfully uploaded spatial anchors to backend.")
            resp.has("status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload spatial anchors to backend: ${e.message}")
            false
        }
    }

    /**
     * Writes current spatial anchors and settings to `youtube_spatial_anchors.json` on device storage.
     * Persists all registered keys (canonical + custom).
     */
    fun saveToFile() {
        try {
            val root = JsonObject()
            root.addProperty("version", 2)
            root.addProperty("platform", "YOUTUBE")
            root.addProperty("updated_at", System.currentTimeMillis())

            // 1. Settings
            val settingsObj = JsonObject()
            settingsObj.addProperty("initial_scroll_count", currentSettings.initialScrollCount)
            settingsObj.addProperty("natural_scroll_delay_min", currentSettings.naturalScrollDelayMin)
            settingsObj.addProperty("natural_scroll_delay_max", currentSettings.naturalScrollDelayMax)
            settingsObj.addProperty("videos_per_batch", currentSettings.videosPerBatch)
            settingsObj.addProperty("max_batches_per_keyword", currentSettings.maxBatchesPerKeyword)
            settingsObj.addProperty("scroll_duration_min", currentSettings.scrollDurationMin)
            settingsObj.addProperty("scroll_duration_max", currentSettings.scrollDurationMax)
            settingsObj.addProperty("overshoot_scroll_enabled", currentSettings.overshootScrollEnabled)
            root.add("settings", settingsObj)

            // 2. Anchors - all registered keys
            val anchorsObj = JsonObject()
            val allKeys = mutableSetOf<String>()
            allKeys.addAll(CANONICAL_DEFAULTS.keys)
            for (prefKey in prefs.all.keys) {
                if (prefKey.endsWith("_x")) {
                    allKeys.add(prefKey.removeSuffix("_x"))
                }
            }
            for (key in allKeys) {
                val point = getAnchor(key, allowExpired = true)
                if (point != null) {
                    val spot = JsonObject()
                    spot.addProperty("x", point.x)
                    spot.addProperty("y", point.y)
                    spot.addProperty("label", key)
                    anchorsObj.add(key, spot)
                }
            }
            root.add("anchors", anchorsObj)

            anchorFile.writeText(gson.toJson(root))
            lastFileModifiedTime = anchorFile.lastModified()
            Log.d(TAG, "Persisted spatial anchors & settings to ${anchorFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save spatial anchors to file: ${e.message}")
        }
    }

    /**
     * Converts a normalized 0..1000 point to physical screen coordinates.
     */
    fun toScreenPoint(point: NormalizedPoint, viewWidth: Float, viewHeight: Float): ScreenPoint {
        val actualW = if (viewWidth > 10f) viewWidth else 1080f
        val actualH = if (viewHeight > 10f) viewHeight else 2400f
        val px = (point.x / 1000f) * actualW
        val py = (point.y / 1000f) * actualH
        return ScreenPoint(px, py)
    }

    /**
     * Clears all cached calibrations.
     */
    fun clear() {
        prefs.edit().clear().apply()
        if (anchorFile.exists()) {
            anchorFile.delete()
        }
        Log.i(TAG, "Cleared spatial anchor registry cache and file")
    }
}
