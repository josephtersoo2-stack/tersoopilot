package com.multibrowser.antidetect.automation.recovery

import com.multibrowser.antidetect.automation.input.InputController
import com.multibrowser.antidetect.automation.perception.*
import com.multibrowser.antidetect.data.db.AppDatabase
import kotlinx.coroutines.delay

sealed class RecoveryResult {
    data class Resolved(val target: ResolvedTarget, val freshSnapshot: DomSnapshot) : RecoveryResult()
    data class RetryState(val reason: String) : RecoveryResult()
    data class EscalateTier2(val reason: String, val lastSnapshot: DomSnapshot?) : RecoveryResult()
    data class Abort(val reason: String) : RecoveryResult()
}

class RecoveryEngine(
    private val inputController: InputController,
    private val targetResolver: TargetResolver,
    private val db: AppDatabase? = null
) {
    var maxAllowedRecoveryAttempts = 3

    /**
     * Handles missing or non-viewport elements by attempting bounded scroll-into-view sweeps.
     * Persistently tracks attempts per (jobId, stepId) and aborts if max attempts are exceeded.
     */
    suspend fun recoverMissingTarget(
        spec: TargetSpec,
        maxScrollAttempts: Int = 4,
        jobId: String? = null,
        stepId: String? = null,
        getSnapshot: suspend () -> DomSnapshot?
    ): RecoveryResult {
        // Enforce max recovery limit across process lifecycles
        if (jobId != null && stepId != null && db != null) {
            val existingAttempts = db.dao.getRecoveryAttemptCount(jobId, stepId)
            if (existingAttempts >= maxAllowedRecoveryAttempts) {
                return RecoveryResult.Abort(
                    "Max recovery attempts ($maxAllowedRecoveryAttempts) exceeded for step '$stepId' in job '$jobId'"
                )
            }
            db.dao.recordRecoveryAttempt(jobId, stepId, "Attempting scroll recovery for $spec")
        }

        var currentSnapshot = getSnapshot()
            ?: return RecoveryResult.EscalateTier2("Unable to capture initial DOM snapshot", null)

        // 1. Check if an unexpected blocking modal or consent wall exists
        if (currentSnapshot.pageState == "CONSENT_WALL") {
            return RecoveryResult.RetryState("CONSENT_WALL_DETECTED")
        }

        // 2. Evaluate if element is already present
        var resolved = targetResolver.resolve(spec, currentSnapshot)
        if (resolved != null && resolved.isInsideViewport) {
            return RecoveryResult.Resolved(resolved, currentSnapshot)
        }

        // 3. Perform bounded scroll-into-view loop
        for (attempt in 1..maxScrollAttempts) {
            val viewport = currentSnapshot.viewport
            val screenWidth = viewport.width
            val screenHeight = viewport.height

            // Calculate humanized vertical scroll points centered on device screen
            val startX = screenWidth * 0.5f
            val startY = screenHeight * 0.75f
            val endY = screenHeight * 0.30f

            // Flick down to bring deeper content into the viewport
            inputController.swipe(
                start = ScreenPoint(startX, startY),
                end = ScreenPoint(startX, endY),
                durationMs = 550L
            )

            // Allow layout and rendering pipeline to settle
            delay(900L)

            val nextSnapshot = getSnapshot()
            if (nextSnapshot != null) {
                currentSnapshot = nextSnapshot
                resolved = targetResolver.resolve(spec, currentSnapshot)

                if (resolved != null && resolved.isInsideViewport) {
                    return RecoveryResult.Resolved(resolved, currentSnapshot)
                }
            }
        }

        // Target remains unfound after scrolling threshold; escalate to Tier-2 AI fallback
        return RecoveryResult.EscalateTier2(
            reason = "Target matching spec $spec could not be resolved after $maxScrollAttempts scroll sweeps",
            lastSnapshot = currentSnapshot
        )
    }

    /**
     * Automatically attempts to dismiss known interstitial popups, upsell sheets, or cookie walls.
     */
    suspend fun dismissBlockingOverlay(
        coordinateMapper: CoordinateMapper,
        getSnapshot: suspend () -> DomSnapshot?
    ): Boolean {
        val snapshot = getSnapshot() ?: return false

        // Common dismiss button specs
        val dismissSpecs = listOf(
            TargetSpec(ariaLabel = "Reject all"),
            TargetSpec(textSnippet = "Reject all"),
            TargetSpec(ariaLabel = "Dismiss"),
            TargetSpec(textSnippet = "Dismiss"),
            TargetSpec(ariaLabel = "No thanks"),
            TargetSpec(textSnippet = "No thanks"),
            TargetSpec(selector = "#dismiss-button")
        )

        for (spec in dismissSpecs) {
            val resolved = targetResolver.resolve(spec, snapshot)
            if (resolved != null && resolved.isInsideViewport) {
                val screenRect = coordinateMapper.mapDomToScreen(
                    rect = resolved.element.rect,
                    dpr = snapshot.viewport.dpr
                )
                val tapPoint = coordinateMapper.getOrganicTapPoint(screenRect)
                inputController.tap(tapPoint)
                delay(1200L)
                return true
            }
        }

        return false
    }
}
