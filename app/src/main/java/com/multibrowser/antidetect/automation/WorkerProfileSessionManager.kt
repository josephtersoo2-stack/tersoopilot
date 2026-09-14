package com.multibrowser.antidetect.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import com.multibrowser.antidetect.MainActivity
import com.multibrowser.antidetect.automation.input.NativeInputAdapter
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.engine.SessionPoolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Execution context bundle holding resources required by GhostPilotRunner during worker execution.
 */
data class WorkerExecutionContext(
    val profile: ProfileEntity,
    val geckoSession: GeckoSession,
    val targetView: GeckoView,
    val inputController: NativeInputAdapter,
    val runner: GhostPilotRunner,
    val isExternalView: Boolean = false
)

/**
 * Bridges server-selected profile_id to local Room ProfileEntity, sandboxed GeckoSession,
 * attached GeckoView, NativeInputAdapter, and GhostPilotRunner.
 *
 * Supports both:
 * 1. Live Foreground Mode: If MainActivity is running, switches UI to the profile and attaches
 *    to the visible on-screen GeckoView so the user can watch the automation live.
 * 2. Autonomous Background Worker Mode: If MainActivity is absent or unattached, prepares an
 *    isolated offscreen GeckoView and executes autonomously.
 */
class WorkerProfileSessionManager(
    private val context: Context,
    private val sessionPoolManager: SessionPoolManager = SessionPoolManager(context),
    private val db: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val TAG = "WorkerProfileSession"

    suspend fun prepare(
        profileId: String,
        profileName: String = "",
        deviceId: String = ""
    ): WorkerExecutionContext = withContext(Dispatchers.Main) {
        // 1. Resolve local ProfileEntity from Room database (strict, no random profile fallback)
        val profile = resolveProfile(profileId, profileName)
            ?: throw IllegalStateException("Profile '$profileId' ($profileName) could not be resolved locally.")

        Log.i(TAG, "Preparing worker execution environment for profile: ${profile.name} (ID: ${profile.id})")

        // 2. Check if MainActivity is active on screen or can be brought to foreground
        val activity = MainActivity.activeInstance
        val isActivityUsable = activity != null && !activity.isFinishing && !activity.isDestroyed

        val (session, geckoView, isExternal) = if (isActivityUsable) {
            Log.i(TAG, "MainActivity is active; bringing profile '${profile.name}' to foreground in UI.")
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not bring MainActivity to front: ${e.message}")
            }

            activity!!.launchProfileInForeground(profile)

            // Wait briefly for Compose AndroidView to measure, attach and bind session
            var readyView: GeckoView? = null
            var readySession: GeckoSession? = null
            for (i in 0 until 30) {
                readyView = MainActivity.activeGeckoViewInstance
                readySession = activity.browserCoordinator.currentSession
                if (readyView != null && readySession != null && readyView.isAttachedToWindow) {
                    Log.i(TAG, "Active foreground GeckoView ready and attached to window after ${(i + 1) * 100}ms.")
                    break
                }
                delay(100L)
            }

            if (readyView != null && readySession != null) {
                Triple(readySession, readyView, true)
            } else {
                Log.w(TAG, "Foreground view timeout; falling back to dedicated worker GeckoView.")
                val fallbackSession = sessionPoolManager.getOrLaunchSession(profile, forceMute = false)
                    ?: throw IllegalStateException("Failed to launch GeckoSession for profile ${profile.id}")
                val fallbackView = createDedicatedWorkerGeckoView(profile)
                sessionPoolManager.attachToView(fallbackView, profile.id)
                Triple(fallbackSession, fallbackView, false)
            }
        } else {
            Log.i(TAG, "MainActivity not active; attempting to launch activity for live execution.")
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("AUTORUN_PROFILE_ID", profile.id)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch MainActivity: ${e.message}")
            }

            // Also prepare headless worker view in case activity start is delayed or blocked
            val headlessSession = sessionPoolManager.getOrLaunchSession(profile, forceMute = false)
                ?: throw IllegalStateException("Failed to launch GeckoSession for profile ${profile.id}")
            val headlessView = createDedicatedWorkerGeckoView(profile)
            sessionPoolManager.attachToView(headlessView, profile.id)
            Triple(headlessSession, headlessView, false)
        }

        // 3. Instantiate gesture injector and native input adapter
        val injector = NativeGestureInjector(geckoView)
        val inputAdapter = NativeInputAdapter(geckoView, injector)

        // 4. Construct GhostPilotRunner
        val runner = GhostPilotRunner(
            context = context,
            profileId = profile.id,
            session = session,
            targetView = geckoView,
            inputController = inputAdapter,
            profileName = profile.name,
            cloudSyncId = profile.cloudSyncId ?: "",
            deviceId = deviceId
        )

        WorkerExecutionContext(
            profile = profile,
            geckoSession = session,
            targetView = geckoView,
            inputController = inputAdapter,
            runner = runner,
            isExternalView = isExternal
        )
    }

    private fun createDedicatedWorkerGeckoView(profile: ProfileEntity): GeckoView {
        val geckoView = GeckoView(context)
        val metrics = context.resources.displayMetrics
        val width = if (profile.screenWidth > 0) profile.screenWidth else metrics.widthPixels
        val height = if (profile.screenHeight > 0) profile.screenHeight else metrics.heightPixels
        geckoView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        geckoView.layout(0, 0, width, height)
        return geckoView
    }

    internal suspend fun resolveProfile(profileId: String, profileName: String = ""): ProfileEntity? {
        // 1. Direct ID match
        val byId = db.dao.getProfileById(profileId)
        if (byId != null) return byId

        // 2. Search in all profiles snapshot by ID, cloudSyncId, or name
        val allProfiles = db.dao.getAllProfiles().firstOrNull() ?: emptyList()
        return allProfiles.firstOrNull { it.id == profileId }
            ?: allProfiles.firstOrNull {
                !it.cloudSyncId.isNullOrBlank() && it.cloudSyncId == profileId
            } ?: if (profileName.isNotBlank()) {
                allProfiles.firstOrNull {
                    it.name.equals(profileName, ignoreCase = true)
                }
            } else {
                null
            }
    }

    suspend fun cleanup(context: WorkerExecutionContext) = withContext(Dispatchers.Main) {
        try {
            context.runner.stop()
            if (!context.isExternalView) {
                sessionPoolManager.closeSession(context.profile.id, context.targetView)
            }
            Log.i(TAG, "Successfully cleaned up worker session for profile ${context.profile.id}")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up worker session for ${context.profile.id}: ${e.message}")
        }
    }
}
