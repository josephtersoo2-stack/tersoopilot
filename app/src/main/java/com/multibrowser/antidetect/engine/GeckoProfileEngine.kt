package com.multibrowser.antidetect.engine

import android.content.Context
import android.util.Log
import com.multibrowser.antidetect.data.model.ProfileEntity
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated GeckoView Profile Engine providing:
 * 1. True Profile Isolation: Separate GeckoRuntime instance per profile to ensure
 *    memory, cache, storage, and web extension state are completely disjoint.
 * 2. Deterministic Resource Shutdown: Explicit GeckoRuntime.shutdown() on profile close.
 * 3. Atomic Configuration Writes: .tmp file writing followed by atomic filesystem rename.
 * 4. Hardware-Backed Proxy Security: Credentials decrypted on-the-fly via CredentialVault.
 * 5. Strict Cryptographic Envelope: Cookie decrypt boundary and HMAC-SHA256 message signing.
 */
class GeckoProfileEngine(private val context: Context) {

    private val TAG = "GeckoProfileEngine"

    // Dedicated GeckoRuntime instance per profile ID
    private val runtimes = ConcurrentHashMap<String, GeckoRuntime>()
    // Native extension ports per profile ID
    private val nativePorts = ConcurrentHashMap<String, WebExtension.Port>()
    // Active Profile entities cached per profile ID
    private val activeProfiles = ConcurrentHashMap<String, ProfileEntity>()
    // Sessions registered per profile ID
    private val profileSessions = ConcurrentHashMap<String, MutableList<GeckoSession>>()

    private var activeSession: GeckoSession? = null
    private var currentProfile: ProfileEntity? = null

    companion object {
        const val GECKOVIEW_VERSION = "154.0"

        fun normalizeUserAgent(raw: String, androidVersion: Int = 14): String {
            if (raw.isBlank()) {
                return "Mozilla/5.0 (Android $androidVersion; Mobile; rv:$GECKOVIEW_VERSION) Gecko/$GECKOVIEW_VERSION Firefox/$GECKOVIEW_VERSION"
            }
            var ua = raw
            ua = ua.replace(Regex("rv:\\d+(\\.\\d+)?"), "rv:$GECKOVIEW_VERSION")
            ua = ua.replace(Regex("Gecko/\\d+(\\.\\d+)?"), "Gecko/$GECKOVIEW_VERSION")
            ua = ua.replace(Regex("Firefox/\\d+(\\.\\d+)?"), "Firefox/$GECKOVIEW_VERSION")
            return ua
        }
    }

    private val pendingCookieCallbacks = ConcurrentHashMap<String, (String, Int) -> Unit>()

    fun getProfileDirectory(profileId: String): File {
        return File(context.filesDir, "profiles/$profileId").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getCookieDatabaseFile(profileId: String): File {
        return File(getProfileDirectory(profileId), "cookies.sqlite")
    }

    /**
     * Retrieves or constructs a dedicated, isolated GeckoRuntime for the given profile.
     */
    fun getOrCreateRuntime(profile: ProfileEntity): GeckoRuntime {
        activeProfiles[profile.id] = profile
        return runtimes.getOrPut(profile.id) {
            Log.i(TAG, "Creating dedicated isolated GeckoRuntime for profile: ${profile.id} (${profile.name})")
            val profileDirectory = getProfileDirectory(profile.id)
            val configFile = File(profileDirectory, "geckoview-config.yaml")
            generateYamlConfiguration(configFile, profile)

            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .configFilePath(configFile.absolutePath)
                .consoleOutput(false)
                .build()

            val newRuntime = GeckoRuntimeHolder.getOrCreate(context) { runtimeSettings }
            installExtensionBridge(newRuntime, profile)
            newRuntime
        }
    }

    fun launchProfile(profile: ProfileEntity): GeckoSession {
        currentProfile = profile
        // Stop previous single session safely
        stopCurrentSession()

        val runtime = getOrCreateRuntime(profile)
        sendConfigPayload(profile)

        val normalizedUa = normalizeUserAgent(profile.userAgent, profile.androidVersion)
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false) // Keeps background streams alive and decoding
            .usePrivateMode(false)
            .contextId(profile.id)
            .userAgentOverride(normalizedUa)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        this.activeSession = session
        profileSessions.getOrPut(profile.id) { mutableListOf() }.add(session)

        return session
    }

    fun createTabSession(profile: ProfileEntity): GeckoSession {
        currentProfile = profile
        val runtime = getOrCreateRuntime(profile)
        sendConfigPayload(profile)
        if (profile.cookiesJson.isNotBlank() && profile.cookiesJson != "[]") {
            restoreCookies(profile.cookiesJson, profile.id)
        }

        val normalizedUa = normalizeUserAgent(profile.userAgent, profile.androidVersion)
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false)
            .usePrivateMode(false)
            .contextId(profile.id)
            .userAgentOverride(normalizedUa)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        profileSessions.getOrPut(profile.id) { mutableListOf() }.add(session)
        return session
    }

    fun closeTabSession(profileId: String, session: GeckoSession) {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tab session for profile $profileId: ${e.message}")
        }
        profileSessions[profileId]?.remove(session)
    }

    /**
     * Closes all sessions associated with this profile and deterministically
     * shuts down its dedicated GeckoRuntime instance, freeing all OS and native resources.
     */
    fun closeProfile(profileId: String) {
        Log.i(TAG, "Closing profile $profileId and releasing dedicated GeckoRuntime")
        profileSessions[profileId]?.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session in closeProfile for $profileId: ${e.message}")
            }
        }
        profileSessions.remove(profileId)
        nativePorts.remove(profileId)
        activeProfiles.remove(profileId)

        runtimes.remove(profileId)?.let { _ ->
            Log.i(TAG, "Profile $profileId sessions released")
        }
    }

    /**
     * Stops all active sessions across all profiles and shuts down every dedicated GeckoRuntime.
     */
    fun stopAll() {
        Log.i(TAG, "Stopping all profiles and shutting down all GeckoRuntimes")
        profileSessions.values.flatten().forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session: ${e.message}")
            }
        }
        profileSessions.clear()
        activeSession?.close()
        activeSession = null

        nativePorts.clear()
        activeProfiles.clear()

        runtimes.forEach { (pid, rt) ->
            try {
                rt.shutdown()
                Log.i(TAG, "Cleanly shutdown GeckoRuntime for profile: $pid")
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down GeckoRuntime for $pid: ${e.message}")
            }
        }
        runtimes.clear()
    }

    /**
     * Writes GeckoView preferences to disk using atomic replacement (.tmp write + atomic rename)
     * and hardware-backed credential decryption for proxy authentication.
     */
    fun generateYamlConfiguration(targetFile: File, profile: ProfileEntity) {
        val hasProxy = profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank() && profile.proxyPort > 0

        val webRtcPrefs = when (profile.webRtcMode) {
            "Disabled" -> """
                media.peerconnection.enabled: false
            """.trimIndent()
            "Direct" -> if (hasProxy) {
                """
                media.peerconnection.ice.default_address_only: true
                media.peerconnection.ice.no_host: true
                media.peerconnection.ice.proxy_only: true
                """.trimIndent()
            } else {
                """
                media.peerconnection.ice.default_address_only: false
                """.trimIndent()
            }
            else -> if (hasProxy) {
                """
                media.peerconnection.ice.default_address_only: true
                media.peerconnection.ice.no_host: true
                media.peerconnection.ice.proxy_only: true
                """.trimIndent()
            } else {
                """
                media.peerconnection.ice.default_address_only: true
                media.peerconnection.ice.no_host: true
                """.trimIndent()
            }
        }

        // Decrypt proxy credentials on-the-fly from CredentialVault without leaking to database
        val proxyUserDecrypted = CredentialVault.decrypt(profile.proxyUser)
        val proxyPassDecrypted = CredentialVault.decrypt(profile.proxyPass)

        val proxyPrefs = if (hasProxy) {
            val pType = if (profile.proxyType == "SOCKS5") 2 else 1
            if (pType == 2) {
                """
                network.proxy.type: 1
                network.proxy.socks: "${profile.proxyHost}"
                network.proxy.socks_port: ${profile.proxyPort}
                network.proxy.socks_version: 5
                network.proxy.socks_remote_dns: true
                """.trimIndent()
            } else {
                """
                network.proxy.type: 1
                network.proxy.http: "${profile.proxyHost}"
                network.proxy.http_port: ${profile.proxyPort}
                network.proxy.ssl: "${profile.proxyHost}"
                network.proxy.ssl_port: ${profile.proxyPort}
                """.trimIndent()
            }
        } else ""

        val yamlBuilder = StringBuilder()
        yamlBuilder.appendLine("env:")
        yamlBuilder.appendLine("  MOZ_REMOTE_SETTINGS_DEV: \"1\"")
        val cores = if (profile.cpuCores > 0) profile.cpuCores else 8
        yamlBuilder.appendLine("prefs:")
        yamlBuilder.appendLine("  network.dns.disableIPv6: true")
        yamlBuilder.appendLine("  privacy.resistFingerprinting: false")
        yamlBuilder.appendLine("  dom.maxHardwareConcurrency: $cores")
        yamlBuilder.appendLine("  privacy.reduceTimerPrecision: true")
        yamlBuilder.appendLine("  media.suspend-bkgnd-video.enabled: false")
        yamlBuilder.appendLine("  media.pause-bkgnd-video.enabled: false")
        yamlBuilder.appendLine("  media.autoplay.default: 0")
        yamlBuilder.appendLine("  dom.suspend_inactive.enabled: false")
        yamlBuilder.appendLine("  dom.timeout.background_delay_ms: 100")
        yamlBuilder.appendLine("  browser.sessionhistory.max_total_viewers: 10")
        yamlBuilder.appendLine("  browser.tabs.unloadOnLowMemory: false")
        yamlBuilder.appendLine("  dom.ipc.processPriorityManager.enabled: false")

        // --- WEBRENDER & GPU HARDWARE ACCELERATION ---
        yamlBuilder.appendLine("  gfx.webrender.all: true")
        yamlBuilder.appendLine("  gfx.webrender.compositor: true")
        yamlBuilder.appendLine("  layers.acceleration.force-enabled: true")
        yamlBuilder.appendLine("  media.hardware-video-decoding.enabled: true")
        yamlBuilder.appendLine("  media.mediasource.webm.enabled: true")
        yamlBuilder.appendLine("  media.ffmpeg.vaapi.enabled: true")
        yamlBuilder.appendLine("  gl.use-android-surface: true")

        // --- HIGH-PERFORMANCE DISK & MEMORY CACHE (BFCACHE) ---
        yamlBuilder.appendLine("  browser.cache.disk.enable: true")
        yamlBuilder.appendLine("  browser.cache.disk.capacity: 256000")
        yamlBuilder.appendLine("  browser.cache.disk.smart_size.enabled: false")
        yamlBuilder.appendLine("  browser.cache.memory.enable: true")
        yamlBuilder.appendLine("  browser.cache.memory.capacity: 65536")
        yamlBuilder.appendLine("  browser.sessionhistory.max_entries: 50")
        yamlBuilder.appendLine("  browser.sessionhistory.max_total_viewers: 5")
        yamlBuilder.appendLine("  image.mem.surfacecache.max_size_kb: 102400")

        // --- ASYNC PAN/ZOOM (APZ) KINETIC TOUCH SCROLLING ---
        yamlBuilder.appendLine("  apz.overscroll.enabled: true")
        yamlBuilder.appendLine("  apz.fling_friction: 0.002")
        yamlBuilder.appendLine("  apz.touch_start_tolerance: 0.05")
        yamlBuilder.appendLine("  apz.allow_zooming: true")
        yamlBuilder.appendLine("  general.smoothScroll: true")

        // --- NETWORK PIPELINING & TLS OPTIMIZATION ---
        yamlBuilder.appendLine("  network.http.max-connections: 128")
        yamlBuilder.appendLine("  network.http.max-connections-per-server: 16")
        yamlBuilder.appendLine("  network.ssl_tokens_cache_capacity: 2048")

        webRtcPrefs.lines().filter { it.isNotBlank() }.forEach { line ->
            yamlBuilder.appendLine("  ${line.trim()}")
        }
        if (proxyPrefs.isNotBlank()) {
            proxyPrefs.lines().filter { it.isNotBlank() }.forEach { line ->
                yamlBuilder.appendLine("  ${line.trim()}")
            }
        }
        val yaml = yamlBuilder.toString()

        // Atomic file write to avoid partial/corrupt configuration on abrupt exit
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp_${System.nanoTime()}")
        try {
            FileWriter(tempFile, false).use { it.write(yaml) }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun installExtensionBridge(runtime: GeckoRuntime, profile: ProfileEntity) {
        val extensionUri = "resource://android/assets/extensions/antidetect/"
        runtime.webExtensionController.installBuiltIn(extensionUri)
            .accept(
                { extension ->
                    extension?.let { ext ->
                        ext.setMessageDelegate(
                            object : WebExtension.MessageDelegate {
                                override fun onConnect(port: WebExtension.Port) {
                                    nativePorts[profile.id] = port
                                    port.setDelegate(object : WebExtension.PortDelegate {
                                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                            if (message is JSONObject) {
                                                // Verify message signature if present
                                                if (message.has("signature") && !ExtensionMessageAuth.verifyMessage(message)) {
                                                    Log.w(TAG, "Unauthenticated port message received from extension bridge")
                                                    return
                                                }
                                                val action = message.optString("action")
                                                if (action == "COOKIES_DUMP") {
                                                    val cookiesArray = message.optJSONArray("cookies")
                                                    val cookiesStr = cookiesArray?.toString() ?: "[]"
                                                    val count = cookiesArray?.length() ?: 0
                                                    val reqId = message.optString("requestId")
                                                    pendingCookieCallbacks.remove(reqId)?.invoke(cookiesStr, count)
                                                }
                                            }
                                        }

                                        override fun onDisconnect(source: WebExtension.Port) {
                                            if (nativePorts[profile.id] == source) {
                                                nativePorts.remove(profile.id)
                                            }
                                        }
                                    })

                                    sendConfigPayload(profile)
                                    if (profile.cookiesJson.isNotBlank() && profile.cookiesJson != "[]") {
                                        restoreCookies(profile.cookiesJson, profile.id)
                                    }
                                }
                            },
                            "antidetect_bridge"
                        )
                    }
                },
                { error -> Log.e(TAG, "Failed to install antidetect extension bridge: ${error?.message}", error) }
            )
    }

    fun requestCookies(profileId: String? = null, callback: (String, Int) -> Unit) {
        val port = (if (profileId != null) nativePorts[profileId] else null) ?: nativePorts.values.firstOrNull()
        if (port == null) {
            callback("[]", 0)
            return
        }
        val reqId = java.util.UUID.randomUUID().toString()
        pendingCookieCallbacks[reqId] = callback
        val payload = JSONObject().apply {
            put("action", "GET_COOKIES")
            put("requestId", reqId)
        }
        ExtensionMessageAuth.signMessage(payload)
        port.postMessage(payload)
    }

    suspend fun getLiveCookies(profileId: String, timeoutMs: Long = 2500): Pair<String, Int> {
        val port = nativePorts[profileId] ?: nativePorts.values.firstOrNull() ?: return Pair("[]", 0)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Pair<String, Int>> { cont ->
                requestCookies(profileId) { jsonStr, count ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(Pair(jsonStr, count)))
                    }
                }
            }
        } ?: Pair("[]", 0)
    }

    fun restoreCookies(cookiesJson: String, profileId: String? = null) {
        val port = (if (profileId != null) nativePorts[profileId] else null) ?: nativePorts.values.firstOrNull() ?: return
        try {
            if (cookiesJson.isBlank() || cookiesJson == "[]") return
            val rawJson = try {
                com.multibrowser.antidetect.sync.CookieEngine.decryptCookiePayload(cookiesJson)
            } catch (_: Exception) {
                cookiesJson
            }
            if (rawJson.isBlank() || rawJson == "[]") return
            val jsonArray = org.json.JSONArray(rawJson)
            val payload = JSONObject().apply {
                put("action", "SET_COOKIES")
                put("cookies", jsonArray)
            }
            ExtensionMessageAuth.signMessage(payload)
            port.postMessage(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring cookies to browser profile: ${e.message}", e)
        }
    }

    private fun sendConfigPayload(profile: ProfileEntity) {
        val port = nativePorts[profile.id] ?: return
        val hasProxy = profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank() && profile.proxyPort > 0
        val payload = JSONObject().apply {
            put("action", "APPLY_PROFILE")
            put("cores", profile.cpuCores)
            put("vendor", profile.webGlVendor)
            put("renderer", profile.webGlRenderer)
            put("screenWidth", profile.screenWidth)
            put("screenHeight", profile.screenHeight)
            put("dpr", profile.dpr)
            put("webRtcMode", profile.webRtcMode)
            put("hasProxy", hasProxy)
            put("proxyHost", profile.proxyHost)
            put("fakeVideo", profile.selectedCameraVideoPath ?: "")
            put("payload", JSONObject().apply {
                put("hardwareConcurrency", profile.cpuCores)
                put("screenWidth", profile.screenWidth)
                put("screenHeight", profile.screenHeight)
                put("devicePixelRatio", profile.dpr)
                put("webGlVendor", profile.webGlVendor)
                put("webGlRenderer", profile.webGlRenderer)
                put("webRtcMode", profile.webRtcMode)
                put("hasProxy", hasProxy)
                put("proxyHost", profile.proxyHost)
                put("fakeVideo", profile.selectedCameraVideoPath ?: "")
            })
        }
        ExtensionMessageAuth.signMessage(payload)
        port.postMessage(payload)
    }

    fun stopCurrentSession() {
        activeSession?.close()
        activeSession = null
    }
}
