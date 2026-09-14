package com.multibrowser.antidetect.automation

import android.content.Context
import android.util.Log
import android.view.View
import com.multibrowser.antidetect.MainActivity
import com.multibrowser.antidetect.automation.input.NativeInputAdapter
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.engine.SessionPoolManager
import kotlinx.coroutines.Dispatchers
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
    val isExternalView: Boolean
)

/**
 * Bridges server-selected profile_id to local Room ProfileEntity, sandboxed GeckoSession,
 * attached GeckoView, NativeInputAdapter, and GhostPilotRunner.
 *
 * Implements Phase 2 of the Remediation Implementation Plan.
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
        // 1. Resolve local ProfileEntity from Room database
        val profile = resolveProfile(profileId, profileName)
            ?: throw IllegalStateException("Profile '$profileId' ($profileName) could not be resolved locally.")

        Log.i(TAG, "Preparing worker execution environment for profile: ${profile.name} (ID: ${profile.id})")

        // 2. Acquire or create sandboxed GeckoSession
        val session = sessionPoolManager.getOrLaunchSession(profile, forceMute = true)
            ?: throw IllegalStateException("Failed to launch GeckoSession for profile ${profile.id}")

        // 3. Obtain or instantiate GeckoView
        val activeWindowView = MainActivity.activeGeckoViewInstance
        val (geckoView, isExternal) = if (activeWindowView != null && activeWindowView.isAttachedToWindow) {
            Log.i(TAG, "Reusing active foreground GeckoView from MainActivity.")
            Pair(activeWindowView, true)
        } else {
            Log.i(TAG, "Creating dedicated worker GeckoView on main thread.")
            val view = GeckoView(context)
            val metrics = context.resources.displayMetrics
            val width = if (profile.screenWidth > 0) profile.screenWidth else metrics.widthPixels
            val height = if (profile.screenHeight > 0) profile.screenHeight else metrics.heightPixels
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            Pair(view, false)
        }

        sessionPoolManager.attachToView(geckoView, profile.id)

        // 4. Instantiate gesture injector and native input adapter
        val injector = NativeGestureInjector(geckoView)
        val inputAdapter = NativeInputAdapter(geckoView, injector)

        // 5. Construct GhostPilotRunner
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

    private suspend fun resolveProfile(profileId: String, profileName: String): ProfileEntity? {
        // Direct ID match
        val byId = db.dao.getProfileById(profileId)
        if (byId != null) return byId

        // Search in all profiles snapshot by ID, cloudSyncId, or name
        val allProfiles = db.dao.getAllProfiles().firstOrNull() ?: emptyList()
        return allProfiles.firstOrNull { it.id == profileId }
            ?: allProfiles.firstOrNull { !it.cloudSyncId.isNullOrBlank() && it.cloudSyncId == profileId }
            ?: (if (profileName.isNotBlank()) allProfiles.firstOrNull { it.name.equals(profileName, ignoreCase = true) } else null)
            ?: allProfiles.firstOrNull()
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
