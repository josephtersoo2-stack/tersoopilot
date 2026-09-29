package com.multibrowser.antidetect.engine

import android.content.Context
import com.multibrowser.antidetect.data.model.ProfileEntity
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import com.multibrowser.antidetect.sync.CookieEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import java.io.FileWriter

class SessionPoolManager(private val context: Context) {

    private val runtimePool = mutableMapOf<String, GeckoRuntime>()
    val activeSessions = mutableMapOf<String, GeckoSession>()
    var maxAllowedConcurrency = 5
    var currentActiveProfileId: String? = null
        private set


    // Observable UI state: profileId -> isMuted (true by default)
    val sessionMuteStates = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    // ID of the profile currently allowed to emit sound (null = all muted)
    var currentAudioOwnerId: String? = null
        private set

    /**
     * Toggles mute state with mutual exclusion:
     * Unmuting a profile silences any other profile that is currently audible.
     */
    suspend fun toggleProfileAudio(profileId: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val currentlyMuted = sessionMuteStates[profileId] ?: true

        if (currentlyMuted) {
            // User wants to UNMUTE this profile

            // 1. Mute previous audio owner if different
            currentAudioOwnerId?.let { prevId ->
                if (prevId != profileId && activeSessions.containsKey(prevId)) {
                    sendMuteCommandToSession(prevId, muted = true)
                    sessionMuteStates[prevId] = true
                }
            }

            // 2. Unmute the target profile
            sendMuteCommandToSession(profileId, muted = false)
            sessionMuteStates[profileId] = false
            currentAudioOwnerId = profileId
        } else {
            // User wants to MUTE this profile
            sendMuteCommandToSession(profileId, muted = true)
            sessionMuteStates[profileId] = true
            if (currentAudioOwnerId == profileId) {
                currentAudioOwnerId = null
            }
        }
    }

    /**
     * Dispatches native JSON message to GeckoView content script
     */
    fun sendMuteCommandToSession(profileId: String, muted: Boolean) {
        val session = activeSessions[profileId] ?: return

        // Native JS evaluation fallback guarantees delivery even before port binding
        val jsCommand = """
            window.postMessage({ type: 'SET_MUTE_STATE', muted: $muted, fromNative: true }, '*');
            document.querySelectorAll('video, audio').forEach(function(el) {
                try {
                    el.muted = $muted;
                    el.volume = ${if (muted) "0.0" else "1.0"};
                    if (!${muted} && el.paused) {
                        el.play().catch(function(){});
                    }
                } catch(e) {}
            });
            try {
                var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (p) {
                    if ($muted) {
                        if (typeof p.mute === 'function') p.mute();
                        if (typeof p.setVolume === 'function') p.setVolume(0);
                    } else {
                        if (typeof p.unMute === 'function') p.unMute();
                        if (typeof p.setVolume === 'function') p.setVolume(100);
                        if (typeof p.getPlayerState === 'function' && p.getPlayerState() === 2) {
                            p.playVideo();
                        }
                    }
                }
            } catch(e) {}
        """.trimIndent().replace("\n", " ")

        session.loadUri("javascript:(function(){ $jsCommand })();")
    }

    fun sendResolutionCommandToSession(profileId: String, resolution: String) {
        val session = activeSessions[profileId] ?: return
        val jsCommand = "window.postMessage({ type: 'SET_VIDEO_RESOLUTION', resolution: '$resolution' }, '*');"
        session.loadUri("javascript:(function(){ $jsCommand })();")
    }

    fun broadcastResolutionCommand(resolution: String) {
        activeSessions.keys.forEach { profileId ->
            sendResolutionCommandToSession(profileId, resolution)
        }
    }

    fun registerSession(profileId: String, session: GeckoSession) {
        activeSessions[profileId] = session
        sessionMuteStates[profileId] = true // Guaranteed muted on initial launch
    }

    fun getOrLaunchSession(
        profile: ProfileEntity,
        forceMute: Boolean = true,
        onLimitExceeded: () -> Unit = {}
    ): GeckoSession? {
        // Return existing active session if already running
        if (activeSessions.containsKey(profile.id)) {
            currentActiveProfileId = profile.id
            return activeSessions[profile.id]
        }

        // Check concurrency cap
        if (activeSessions.size >= maxAllowedConcurrency) {
            onLimitExceeded()
            return null
        }

        // 1. Isolate Storage Path
        val profileDir = File(context.filesDir, "profiles/${profile.id}").apply {
            if (!exists()) mkdirs()
        }

        // 2. Generate Engine Config (With Native Global Mute Scale & Concurrency Keep-Alive)
        val configFile = File(profileDir, "geckoview-config.yaml")
        generateConfig(configFile, forceMute, profile)

        // 3. Obtain or Create Sandboxed Runtime
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .configFilePath(configFile.absolutePath)
            .consoleOutput(false)
            .build()
        val runtime = GeckoRuntimeHolder.getOrCreate(context) { runtimeSettings }
        runtimePool[profile.id] = runtime

        // 4. Install Background Extension (Mute + 240p Enforcer + Hardware Spoofing)
        installExtensionBridge(runtime, profile)

        // 5. Configure Session to NOT suspend media when inactive
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false) // Keeps background streams alive and decoding
            .userAgentOverride(profile.userAgent)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .usePrivateMode(false)
            .contextId(profile.id)
            .build()

        // Pre-restore session cookies if present
        if (profile.cookiesJson.isNotBlank() && profile.cookiesJson != "[]") {
            try {
                val restored = CookieEngine.importCookiesFromJson(context, profile.id, profile.cookiesJson)
                Log.i("SessionPoolManager", "Pre-restored $restored session cookies for profile '${profile.name}'")
            } catch (e: Exception) {
                Log.w("SessionPoolManager", "Error restoring cookies for profile '${profile.name}': ${e.message}")
            }
        }

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        activeSessions[profile.id] = session
        currentActiveProfileId = profile.id

        return session
    }

    fun attachToView(geckoView: GeckoView, profileId: String) {
        val session = activeSessions[profileId] ?: return
        if (geckoView.session != session) {
            geckoView.releaseSession()
            geckoView.setSession(session)
            currentActiveProfileId = profileId
        }
    }

    fun closeSession(profileId: String, geckoView: GeckoView? = null) {
        if (geckoView?.session == activeSessions[profileId]) {
            geckoView?.releaseSession()
        }

        // Trigger asynchronous cookie sync to save session
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                CookieEngine.syncProfileCookiesById(context, profileId)
            } catch (e: Exception) {
                Log.w("SessionPoolManager", "Error syncing cookies on close: ${e.message}")
            }
        }

        try {
            activeSessions[profileId]?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        activeSessions.remove(profileId)

        runtimePool.remove(profileId)

        if (currentActiveProfileId == profileId) {
            currentActiveProfileId = activeSessions.keys.firstOrNull()
        }
    }

    fun closeAll(geckoView: GeckoView? = null) {
        geckoView?.releaseSession()
        activeSessions.keys.toList().forEach { id ->
            closeSession(id, geckoView)
        }
    }

    private fun installExtensionBridge(runtime: GeckoRuntime, profile: ProfileEntity) {
        try {
            val extensionUri = "resource://android/assets/extensions/antidetect/"
            runtime.webExtensionController.installBuiltIn(extensionUri).accept(
                { extension ->
                    extension?.setMessageDelegate(object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            val payload = JSONObject().apply {
                                put("action", "APPLY_PROFILE")
                                put("cores", profile.cpuCores)
                                put("vendor", profile.webGlVendor)
                                put("renderer", profile.webGlRenderer)
                                put("screenWidth", profile.screenWidth)
                                put("screenHeight", profile.screenHeight)
                                put("dpr", profile.dpr)
                                put("payload", JSONObject().apply {
                                    put("hardwareConcurrency", profile.cpuCores)
                                    put("deviceMemory", profile.ramGb)
                                    put("screenWidth", profile.screenWidth)
                                    put("screenHeight", profile.screenHeight)
                                    put("devicePixelRatio", profile.dpr)
                                    put("webGlVendor", profile.webGlVendor)
                                    put("webGlRenderer", profile.webGlRenderer)
                                })
                            }
                            port.postMessage(payload)
                        }
                    }, "antidetect_bridge")
                },
                { error ->
                    error?.printStackTrace()
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun generateConfig(targetFile: File, forceMute: Boolean, profile: ProfileEntity) {
        val hasProxy = profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank() && profile.proxyPort > 0
        val webRtcPrefs = when (profile.webRtcMode) {
            "Disabled" -> "media.peerconnection.enabled: false"
            "Direct" -> if (hasProxy) {
                """
                media.peerconnection.ice.default_address_only: true
                media.peerconnection.ice.no_host: true
                media.peerconnection.ice.proxy_only: true
                """.trimIndent()
            } else {
                "media.peerconnection.ice.default_address_only: false"
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

        val proxyPrefs = if (hasProxy) {
            val proxyTypeInt = when (profile.proxyType.uppercase()) {
                "HTTP" -> 1
                "SOCKS5" -> 2
                "SOCKS4" -> 3
                else -> 1
            }
            if (proxyTypeInt == 2 || proxyTypeInt == 3) {
                """
                network.proxy.type: 1
                network.proxy.socks: "${profile.proxyHost}"
                network.proxy.socks_port: ${profile.proxyPort}
                network.proxy.socks_version: ${if (proxyTypeInt == 2) 5 else 4}
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

        FileWriter(targetFile, false).use { it.write(yamlBuilder.toString()) }
    }
}
