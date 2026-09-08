package com.multibrowser.antidetect.engine

import android.content.Context
import com.multibrowser.antidetect.data.model.ProfileEntity
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileWriter

class GeckoProfileEngine(private val context: Context) {

    private var activeRuntime: GeckoRuntime? = null
    private var activeSession: GeckoSession? = null
    private var nativePort: WebExtension.Port? = null
    private var currentProfile: ProfileEntity? = null

    fun launchProfile(profile: ProfileEntity): GeckoSession {
        currentProfile = profile
        // Stop previous session safely
        stopCurrentSession()

        // 1. Filesystem Sandbox per Profile ID
        val profileDirectory = File(context.filesDir, "profiles/${profile.id}").apply {
            if (!exists()) mkdirs()
        }

        // 2. Generate native YAML preferences (C++ ResistFingerprinting + WebRTC controls + Proxy)
        val configFile = File(profileDirectory, "geckoview-config.yaml")
        generateYamlConfiguration(configFile, profile)

        // 3. Build or reuse GeckoRuntime
        val runtime = activeRuntime ?: run {
            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .configFilePath(configFile.absolutePath)
                .consoleOutput(false)
                .build()

            val newRuntime = GeckoRuntime.create(context, runtimeSettings)
            activeRuntime = newRuntime
            installExtensionBridge(newRuntime, profile)
            newRuntime
        }

        // Push latest profile config to extension if port is connected
        sendConfigPayload(profile)

        // 4. Initialize GeckoSession with isolated contextId and User-Agent
        val sessionSettings = GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(false) // Keeps background streams alive and decoding
            .usePrivateMode(false)
            .contextId(profile.id)
            .userAgentOverride(profile.userAgent)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        this.activeSession = session

        return session
    }

    private val profileSessions = mutableMapOf<String, MutableList<GeckoSession>>()

    fun createTabSession(profile: ProfileEntity): GeckoSession {
        currentProfile = profile
        val profileDirectory = File(context.filesDir, "profiles/${profile.id}").apply {
            if (!exists()) mkdirs()
        }
        val configFile = File(profileDirectory, "geckoview-config.yaml")
        generateYamlConfiguration(configFile, profile)

        val runtime = activeRuntime ?: run {
            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .configFilePath(configFile.absolutePath)
                .consoleOutput(false)
                .build()

            val newRuntime = GeckoRuntime.create(context, runtimeSettings)
            activeRuntime = newRuntime
            installExtensionBridge(newRuntime, profile)
            newRuntime
        }

        sendConfigPayload(profile)

        val sessionSettings = GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(false) // Keeps background streams alive and decoding
            .usePrivateMode(false)
            .contextId(profile.id)
            .userAgentOverride(profile.userAgent)
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
            e.printStackTrace()
        }
        profileSessions[profileId]?.remove(session)
    }

    fun closeProfile(profileId: String) {
        profileSessions[profileId]?.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        profileSessions.remove(profileId)
    }

    fun stopAll() {
        profileSessions.values.flatten().forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        profileSessions.clear()
        activeSession?.close()
        activeSession = null
    }

    private fun generateYamlConfiguration(targetFile: File, profile: ProfileEntity) {
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
              - "--pref"
              - "media.peerconnection.ice.no_host=true"
            """.trimIndent()
        }

        val proxyArgs = if (profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank()) {
            val pType = if (profile.proxyType == "SOCKS5") 2 else 1
            """
              - "--pref"
              - "network.proxy.type=$pType"
              - "--pref"
              - "network.proxy.http=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.http_port=${profile.proxyPort}"
              - "--pref"
              - "network.proxy.ssl=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.ssl_port=${profile.proxyPort}"
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
              - "privacy.reduceTimerPrecision=true"
              - "--pref"
              - "privacy.resistFingerprinting.reduceTimerPrecision.microseconds=20000"
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

    private val pendingCookieCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String, Int) -> Unit>()

    private fun installExtensionBridge(runtime: GeckoRuntime, profile: ProfileEntity) {
        val extensionUri = "resource://android/assets/extensions/antidetect/"
        runtime.webExtensionController.installBuiltIn(extensionUri)
            .accept(
                { extension ->
                    extension?.let { ext ->
                        ext.setMessageDelegate(
                            object : WebExtension.MessageDelegate {
                                override fun onConnect(port: WebExtension.Port) {
                                    nativePort = port
                                    port.setDelegate(object : WebExtension.PortDelegate {
                                        override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                            if (message is JSONObject) {
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
                                            if (nativePort == source) {
                                                nativePort = null
                                            }
                                        }
                                    })
                                    currentProfile?.let {
                                        sendConfigPayload(it)
                                        if (it.cookiesJson.isNotBlank() && it.cookiesJson != "[]") {
                                            restoreCookies(it.cookiesJson)
                                        }
                                    }
                                }
                            },
                            "antidetect_bridge"
                        )
                    }
                },
                { error -> error?.printStackTrace() }
            )
    }

    fun requestCookies(callback: (String, Int) -> Unit) {
        val port = nativePort
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
        port.postMessage(payload)
    }

    fun restoreCookies(cookiesJson: String) {
        val port = nativePort ?: return
        try {
            if (cookiesJson.isBlank() || cookiesJson == "[]") return
            val jsonArray = org.json.JSONArray(cookiesJson)
            val payload = JSONObject().apply {
                put("action", "SET_COOKIES")
                put("cookies", jsonArray)
            }
            port.postMessage(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendConfigPayload(profile: ProfileEntity) {
        val payload = JSONObject().apply {
            put("action", "APPLY_PROFILE")
            put("cores", profile.cpuCores)
            put("vendor", profile.webGlVendor)
            put("renderer", profile.webGlRenderer)
            put("screenWidth", profile.screenWidth)
            put("screenHeight", profile.screenHeight)
            put("dpr", profile.dpr)
            put("fakeVideo", profile.selectedCameraVideoPath ?: "")
            put("payload", JSONObject().apply {
                put("hardwareConcurrency", profile.cpuCores)
                put("deviceMemory", profile.ramGb)
                put("screenWidth", profile.screenWidth)
                put("screenHeight", profile.screenHeight)
                put("devicePixelRatio", profile.dpr)
                put("webGlVendor", profile.webGlVendor)
                put("webGlRenderer", profile.webGlRenderer)
                put("fakeVideo", profile.selectedCameraVideoPath ?: "")
            })
        }
        nativePort?.postMessage(payload)
    }

    fun stopCurrentSession() {
        activeSession?.close()
        activeSession = null
    }
}
