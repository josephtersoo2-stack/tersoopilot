package com.multibrowser.antidetect.engine

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Process-level singleton holder for GeckoRuntime.
 *
 * Mozilla GeckoView allows only ONE GeckoRuntime instance per Linux process.
 * Profile isolation and storage partitioning are achieved via GeckoSessionSettings.contextId
 * rather than instantiating multiple native engines.
 */
object GeckoRuntimeHolder {
    private const val TAG = "GeckoRuntimeHolder"

    @Volatile
    private var instance: GeckoRuntime? = null

    @Synchronized
    fun getOrCreate(context: Context, settingsProvider: () -> GeckoRuntimeSettings? = { null }): GeckoRuntime {
        instance?.let { return it }

        val appCtx = context.applicationContext
        val newRuntime = try {
            val settings = settingsProvider()
            if (settings != null) {
                try {
                    GeckoRuntime.create(appCtx, settings)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "GeckoRuntime already initialized in process, acquiring default: ${e.message}")
                    GeckoRuntime.getDefault(appCtx)
                }
            } else {
                GeckoRuntime.getDefault(appCtx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining GeckoRuntime: ${e.message}", e)
            GeckoRuntime.getDefault(appCtx)
        }

        instance = newRuntime
        return newRuntime
    }

    fun get(): GeckoRuntime? = instance

    fun set(runtime: GeckoRuntime) {
        instance = runtime
    }
}
