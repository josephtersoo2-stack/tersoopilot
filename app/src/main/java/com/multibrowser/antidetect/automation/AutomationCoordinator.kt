package com.multibrowser.antidetect.automation

import android.content.Context
import android.view.View
import androidx.compose.runtime.mutableStateMapOf
import com.multibrowser.antidetect.automation.input.NativeInputAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mozilla.geckoview.GeckoSession

/**
 * Coordinates GhostPilotRunner instances across multiple browser profiles,
 * ensuring clean view attachment, lifecycle termination, and state propagation.
 */
class AutomationCoordinator(
    private val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val runners = mutableStateMapOf<String, GhostPilotRunner>()

    fun getOrCreateRunner(
        context: Context,
        profileId: String,
        profileName: String,
        cloudSyncId: String,
        session: GeckoSession,
        targetView: View,
        getCurrentUrl: () -> String,
        getCurrentTitle: () -> String,
        onStateChanged: ((Boolean, String?) -> Unit)? = null
    ): GhostPilotRunner {
        val existing = runners[profileId]
        val injector = NativeGestureInjector(targetView)
        val inputController = NativeInputAdapter(targetView, injector)

        return if (existing != null) {
            existing.updateSessionAndView(session, targetView, inputController)
            existing.profileName = profileName
            if (cloudSyncId.isNotBlank()) existing.cloudSyncId = cloudSyncId
            existing.getCurrentUrl = getCurrentUrl
            existing.getCurrentTitle = getCurrentTitle
            existing.onStateChanged = onStateChanged
            existing
        } else {
            val created = GhostPilotRunner(
                context = context,
                profileId = profileId,
                session = session,
                targetView = targetView,
                inputController = inputController,
                profileName = profileName,
                cloudSyncId = cloudSyncId,
                runnerScope = lifecycleScope
            ).apply {
                this.getCurrentUrl = getCurrentUrl
                this.getCurrentTitle = getCurrentTitle
                this.onStateChanged = onStateChanged
            }
            runners[profileId] = created
            created
        }
    }

    fun startAutomation(profileId: String) {
        runners[profileId]?.start()
    }

    fun stopAutomation(profileId: String) {
        runners[profileId]?.stop()
        runners.remove(profileId)
    }

    fun stopAll() {
        runners.values.forEach { it.stop() }
        runners.clear()
    }
}
