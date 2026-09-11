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
import java.io.File
import java.io.FileWriter

data class ExecutionLease(
    val profileId: String,
    val deviceId: String,
    val leaseToken: String = java.util.UUID.randomUUID().toString(),
    val acquiredAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30_000L
)

class SessionPoolManager(private val context: Context) {

    private val runtimePool = mutableMapOf<String, GeckoRuntime>()
    val activeSessions = mutableMapOf<String, GeckoSession>()
    private val activeLeases = mutableMapOf<String, ExecutionLease>()
    var maxAllowedConcurrency = 5
    var currentActiveProfileId: String? = null
        private set

    /**
     * Attempts to acquire a mutual-exclusion execution lease for this profile.
     * Prevents multiple devices or runner instances from executing the same profile concurrently.
     */
    fun acquireLease(profileId: String, deviceId: String, durationMs: Long = 30_000L): ExecutionLease? {
        val existing = activeLeases[profileId]
        val now = System.currentTimeMillis()
        if (existing != null && existing.expiresAt > now && existing.deviceId != deviceId) {
            return null // Actively leased by another device
        }
        val lease = ExecutionLease(
            profileId = profileId,
            deviceId = deviceId,
            acquiredAt = now,
            expiresAt = now + durationMs
        )
        activeLeases[profileId] = lease
        return lease
    }

    /**
     * Heartbeat to renew an existing execution lease.
     */
    fun renewLease(profileId: String, leaseToken: String, durationMs: Long = 30_000L): Boolean {
        val existing = activeLeases[profileId] ?: return false
        if (existing.leaseToken != leaseToken) return false
        val now = System.currentTimeMillis()
        activeLeases[profileId] = existing.copy(expiresAt = now + durationMs)
        return true
    }

    /**
     * Releases an execution lease when automation completes or aborts.
     */
    fun releaseLease(profileId: String, leaseToken: String): Boolean {
        val existing = activeLeases[profileId] ?: return false
        if (existing.leaseToken != leaseToken) return false
        activeLeases.remove(profileId)
        return true
    }

    /**
     * Returns true if a lease is currently valid for this profile and optional deviceId.
     */
    fun isLeaseValid(profileId: String, deviceId: String? = null): Boolean {
        val existing = activeLeases[profileId] ?: return false
        val now = System.currentTimeMillis()
        if (existing.expiresAt <= now) {
            activeLeases.remove(profileId)
            return false
        }
        if (deviceId != null && existing.deviceId != deviceId) {
            return false
        }
        return true
    }


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
                el.muted = $muted;
                el.volume = ${if (muted) "0.0" else "1.0"};
            });
            var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (p) {
                if ($muted) {
                    if (typeof p.mute === 'function') p.mute();
                    if (typeof p.setVolume === 'function') p.setVolume(0);
                } else {
                    if (typeof p.unMute === 'function') p.unMute();
                    if (typeof p.setVolume === 'function') p.setVolume(100);
                }
            }
        """.trimIndent()

        session.loadUri("javascript:(function(){ $jsCommand })();")
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

        // 3. Create Sandboxed Runtime
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .configFilePath(configFile.absolutePath)
            .consoleOutput(false)
            .build()
        val runtime = GeckoRuntime.create(context, runtimeSettings)
        runtimePool[profile.id] = runtime

        // 4. Install Background Extension (Mute + 240p Enforcer + Hardware Spoofing)
        installExtensionBridge(runtime, profile)

        // 5. Configure Session to NOT suspend media when inactive
        val sessionSettings = GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(false) // Keeps background streams alive and decoding
            .userAgentOverride(profile.userAgent)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .usePrivateMode(false)
            .build()

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
        try {
            activeSessions[profileId]?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        activeSessions.remove(profileId)

        try {
            runtimePool[profileId]?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val webRtcArgs = when (profile.webRtcMode) {
            "Disabled" -> """
              - "--pref"
              - "media.peerconnection.enabled=false"
            """.trimIndent()
            "Direct" -> """
              - "--pref"
              - "media.peerconnection.ice.default_address_only=false"
            """.trimIndent()
            else -> """
              - "--pref"
              - "media.peerconnection.ice.default_address_only=true"
            """.trimIndent()
        }

        val proxyArgs = if (profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank() && profile.proxyPort > 0) {
            val proxyTypeInt = when (profile.proxyType.uppercase()) {
                "HTTP" -> 1
                "SOCKS5" -> 2
                "SOCKS4" -> 3
                else -> 1
            }
            """
              - "--pref"
              - "network.proxy.type=1"
              - "--pref"
              - "network.proxy.http=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.http_port=${profile.proxyPort}"
              - "--pref"
              - "network.proxy.ssl=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.ssl_port=${profile.proxyPort}"
              - "--pref"
              - "network.proxy.socks=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.socks_port=${profile.proxyPort}"
              - "--pref"
              - "network.proxy.socks_version=${if (proxyTypeInt == 2) 5 else 4}"
              - "--pref"
              - "network.proxy.socks_remote_dns=true"
            """.trimIndent()
        } else ""

        val yaml = """
            env:
              MOZ_REMOTE_SETTINGS_DEV: "1"
            args:
              - "--pref"
              - "privacy.resistFingerprinting=true"
              - "--pref"
              - "privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts=true"
              - "--pref"
              - "media.suspend-bkgnd-video.enabled=false"
              - "--pref"
              - "media.pause-bkgnd-video.enabled=false"
              - "--pref"
              - "media.autoplay.default=0"
              $webRtcArgs
              $proxyArgs
        """.trimIndent()

        FileWriter(targetFile, false).use { it.write(yaml) }
    }
}
