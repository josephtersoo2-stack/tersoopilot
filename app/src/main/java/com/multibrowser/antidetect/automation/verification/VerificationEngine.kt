package com.multibrowser.antidetect.automation.verification

import com.multibrowser.antidetect.automation.perception.DomSnapshot
import com.multibrowser.antidetect.automation.perception.TargetResolver
import com.multibrowser.antidetect.automation.perception.TargetSpec
import kotlinx.coroutines.delay

sealed class VerificationResult {
    data object Verified : VerificationResult()
    data class Timeout(val message: String, val lastObservedState: String?) : VerificationResult()
}

class VerificationEngine {

    /**
     * Polls the DOM snapshot until the predicate evaluates to true or timeoutMs is exceeded.
     */
    suspend fun verifyCondition(
        timeoutMs: Long = 8000L,
        pollIntervalMs: Long = 400L,
        getSnapshot: suspend () -> DomSnapshot?,
        predicate: (DomSnapshot) -> Boolean
    ): VerificationResult {
        val startTime = System.currentTimeMillis()
        var lastState: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val snapshot = getSnapshot()
            if (snapshot != null) {
                lastState = snapshot.pageState
                if (predicate(snapshot)) {
                    return VerificationResult.Verified
                }
            }
            delay(pollIntervalMs)
        }

        return VerificationResult.Timeout(
            message = "Verification timed out after ${timeoutMs}ms",
            lastObservedState = lastState
        )
    }

    /**
     * Asserts that the page transitions to a specific target PageState (e.g., SEARCH_RESULTS, VIDEO_PLAYBACK).
     */
    suspend fun verifyPageState(
        expectedState: String,
        timeoutMs: Long = 8000L,
        getSnapshot: suspend () -> DomSnapshot?
    ): VerificationResult {
        return verifyCondition(timeoutMs = timeoutMs, getSnapshot = getSnapshot) { snapshot ->
            snapshot.pageState.equals(expectedState, ignoreCase = true)
        }
    }

    /**
     * Asserts that an element matching the TargetSpec appears and is visible in the viewport.
     */
    suspend fun verifyElementVisible(
        spec: TargetSpec,
        resolver: TargetResolver,
        timeoutMs: Long = 8000L,
        getSnapshot: suspend () -> DomSnapshot?
    ): VerificationResult {
        return verifyCondition(timeoutMs = timeoutMs, getSnapshot = getSnapshot) { snapshot ->
            val target = resolver.resolve(spec, snapshot)
            target != null && target.isInsideViewport
        }
    }

    /**
     * Asserts that an element has been dismissed or removed from the DOM (e.g., closed modal or banner).
     */
    suspend fun verifyElementDismissed(
        spec: TargetSpec,
        resolver: TargetResolver,
        timeoutMs: Long = 5000L,
        getSnapshot: suspend () -> DomSnapshot?
    ): VerificationResult {
        return verifyCondition(timeoutMs = timeoutMs, getSnapshot = getSnapshot) { snapshot ->
            resolver.resolve(spec, snapshot) == null
        }
    }

    /**
     * Asserts that HTML5 video playback has successfully initiated.
     */
    suspend fun verifyVideoPlaying(
        timeoutMs: Long = 10000L,
        getSnapshot: suspend () -> DomSnapshot?
    ): VerificationResult {
        return verifyCondition(timeoutMs = timeoutMs, getSnapshot = getSnapshot) { snapshot ->
            val v = snapshot.videoState
            v != null && !v.paused && v.currentTime > 0
        }
    }
}
