package com.multibrowser.antidetect.engine

import android.content.Context
import com.multibrowser.antidetect.models.BrowserProfile
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileWriter

class GeckoProfileManager(private val context: Context) {

    private var runtime: GeckoRuntime? = null
    private var nativePort: WebExtension.Port? = null
    private var activeProfile: BrowserProfile? = null

    fun initializeProfile(profile: BrowserProfile): Pair<GeckoRuntime, GeckoSession> {
        activeProfile = profile
        val profileDir = File(context.filesDir, "profiles/${profile.id}").apply {
            if (!exists()) mkdirs()
        }

        // 1. Generate geckoview-config.yaml for Native RFP & Security Settings
        val configFile = File(profileDir, "geckoview-config.yaml")
        writeProfileConfig(configFile)

        // 2. Build or reuse GeckoRuntime
        val activeRuntime = runtime ?: run {
            val runtimeSettings = GeckoRuntimeSettings.Builder()
                .configFilePath(configFile.absolutePath)
                .consoleOutput(false)
                .build()

            val newRuntime = GeckoRuntime.create(context, runtimeSettings)
            runtime = newRuntime
            registerExtensionBridge(newRuntime, profile)
            newRuntime
        }

        // Push current profile if port already connected
        pushProfileConfig(profile)

        // 3. Create Sandboxed GeckoSession with matching UA and contextId for storage partitioning
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .contextId(profile.id)
            .userAgentOverride(profile.userAgent)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(activeRuntime)

        return Pair(activeRuntime, session)
    }

    private fun writeProfileConfig(targetFile: File) {
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
              - "media.peerconnection.ice.default_address_only=true"
              - "--pref"
              - "media.peerconnection.ice.no_host=true"
              - "--pref"
              - "dom.security.https_only_mode=false"
        """.trimIndent()

        FileWriter(targetFile, false).use { it.write(yaml) }
    }

    private fun registerExtensionBridge(runtime: GeckoRuntime, profile: BrowserProfile) {
        val extensionUri = "resource://android/assets/extensions/antidetect/"

        runtime.webExtensionController.installBuiltIn(extensionUri)
            .accept(
                { extension ->
                    extension?.let { ext ->
                        ext.setMessageDelegate(
                            object : WebExtension.MessageDelegate {
                                override fun onConnect(port: WebExtension.Port) {
                                    nativePort = port
                                    activeProfile?.let { pushProfileConfig(it) }
                                }
                            },
                            "antidetect_bridge"
                        )
                    }
                },
                { throwable ->
                    throwable?.printStackTrace()
                }
            )
    }

    fun pushProfileConfig(profile: BrowserProfile) {
        val payload = JSONObject().apply {
            put("action", "APPLY_PROFILE")
            put("payload", JSONObject().apply {
                put("hardwareConcurrency", profile.hardwareConcurrency)
                put("deviceMemory", profile.deviceMemory)
                put("screenWidth", profile.screenWidth)
                put("screenHeight", profile.screenHeight)
                put("devicePixelRatio", profile.devicePixelRatio)
                put("webGlVendor", profile.webGlVendor)
                put("webGlRenderer", profile.webGlRenderer)
            })
        }
        nativePort?.postMessage(payload)
    }
}
