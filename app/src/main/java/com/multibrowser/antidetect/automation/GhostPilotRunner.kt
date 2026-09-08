package com.multibrowser.antidetect.automation

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.multibrowser.antidetect.automation.input.InputController
import com.multibrowser.antidetect.automation.perception.*
import com.multibrowser.antidetect.automation.recovery.RecoveryEngine
import com.multibrowser.antidetect.automation.recovery.RecoveryResult
import com.multibrowser.antidetect.automation.verification.VerificationEngine
import com.multibrowser.antidetect.automation.verification.VerificationResult
import com.multibrowser.antidetect.network.GhostPilotApiService
import com.multibrowser.antidetect.network.RetrofitInstance
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession

class GhostPilotRunner(
    private val context: Context,
    val profileId: String,
    var session: GeckoSession,
    var targetView: View,
    var inputController: InputController,
    var profileName: String = "",
    var cloudSyncId: String = ""
) {
    private val TAG = "GhostPilotRunner"
    private val api = RetrofitInstance.retrofit.create(GhostPilotApiService::class.java)
    private val gson = Gson()

    private var snapshotBridge = SnapshotBridge(session)
    private val targetResolver = TargetResolver()
    private var coordinateMapper = CoordinateMapper(targetView)
    private val verificationEngine = VerificationEngine()
    private var recoveryEngine = RecoveryEngine(inputController, targetResolver)

    private var runnerScope: CoroutineScope? = null
    var isRunning by mutableStateOf(false)
        private set

    private var currentJobId: String? = null
    private var currentDag: JsonObject? = null
    var currentStateId by mutableStateOf<String?>(null)
        private set

    var recordedWatchSeconds: Int = 0

    // Callbacks to preserve UI & toolbar integration
    var getCurrentUrl: (() -> String)? = null
        set(value) {
            field = value
            snapshotBridge.getCurrentUrl = value
        }

    var getCurrentTitle: (() -> String)? = null
        set(value) {
            field = value
            snapshotBridge.getCurrentTitle = value
        }

    var onStateChanged: ((Boolean, String?) -> Unit)? = null

    fun updateSessionAndView(newSession: GeckoSession, newTargetView: View, newInputController: InputController) {
        this.session = newSession
        this.targetView = newTargetView
        this.inputController = newInputController
        this.snapshotBridge = SnapshotBridge(newSession, getCurrentUrl, getCurrentTitle)
        this.coordinateMapper = CoordinateMapper(newTargetView)
        this.recoveryEngine = RecoveryEngine(newInputController, targetResolver)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        onStateChanged?.invoke(true, currentStateId)
        runnerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runnerScope?.launch {
            Log.i(TAG, "GhostPilot runner started for profile: $profileId (name=$profileName, cloudSyncId=$cloudSyncId)")
            executionLoop()
        }
    }

    fun stop() {
        isRunning = false
        runnerScope?.cancel()
        runnerScope = null
        currentJobId = null
        currentDag = null
        currentStateId = null
        recordedWatchSeconds = 0
        onStateChanged?.invoke(false, null)
        Log.i(TAG, "GhostPilot runner stopped for profile: $profileId")
    }

    private suspend fun executionLoop() {
        while (isRunning) {
            try {
                // 1. Poll for pending job if idle
                if (currentJobId == null) {
                    val pollResp = api.pollJob(profileId, profileName.ifBlank { null }, cloudSyncId.ifBlank { null })
                    if (pollResp.has("work_available") && pollResp.get("work_available").asBoolean) {
                        currentJobId = pollResp.get("job_id").asString
                        currentStateId = pollResp.get("entry_state").asString
                        currentDag = pollResp.getAsJsonObject("dag")
                        Log.i(TAG, "Claimed Job: $currentJobId. Entry State: $currentStateId")
                        onStateChanged?.invoke(true, currentStateId)

                        launchHeartbeat(currentJobId!!)
                    } else {
                        delay(5000L)
                        continue
                    }
                }

                // 2. Fetch current DAG state definition
                val states = currentDag?.getAsJsonObject("states")
                val currentNode = states?.getAsJsonObject(currentStateId)

                if (currentNode == null) {
                    Log.e(TAG, "Node $currentStateId not found in DAG. Marking failure.")
                    handleTransition("FAILURE", error = "Node $currentStateId missing.")
                    currentJobId = null
                    continue
                }

                val command = currentNode.get("command").asString
                val params = currentNode.getAsJsonObject("params") ?: JsonObject()
                Log.i(TAG, "Executing closed-loop step: [$currentStateId] -> Command: $command")

                // 3. Dispatch closed-loop atomic command
                val outcome = executeClosedLoopCommand(command, params)

                // 4. Report transition to backend
                handleTransition(outcome)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Execution loop cycle error: ${e.message}", e)
                delay(3000L)
            }
        }
    }

    private suspend fun executeClosedLoopCommand(command: String, params: JsonObject): String {
        return when (command) {
            "NAVIGATE" -> {
                val url = params.get("url")?.asString ?: "about:blank"
                Log.i(TAG, "Navigating to: $url")
                withContext(Dispatchers.Main) { session.loadUri(url) }
                // Allow page load to initiate and initial DOM to settle
                delay(3500L)
                "SUCCESS"
            }

            "WAIT" -> {
                val seconds = params.get("seconds")?.asInt ?: 3
                delay(seconds * 1000L)
                "SUCCESS"
            }

            "GROUNDED_CLICK", "CLICK" -> {
                val spec = extractTargetSpec(params)
                executeGroundedClick(spec, params)
            }

            // Semantic mappings for high-level YouTube commands
            "YT_TAP_SEARCH_BAR" -> {
                val spec = TargetSpec(
                    role = "button",
                    textSnippet = "Search",
                    ariaLabel = "Search YouTube",
                    selector = "form#search-form"
                )
                val res = executeGroundedClick(spec, params, expectedState = "SEARCH_INPUT_ACTIVE")
                if (res != "SUCCESS") {
                    // Fallback to standard top-right search icon area
                    Log.i(TAG, "Grounded search icon fallback to organic top-right search icon")
                    val width = targetView.width.toFloat().coerceAtLeast(1080f)
                    inputController.tap(ScreenPoint(width * 0.90f, 140f))
                    delay(1200L)
                    "SUCCESS"
                } else {
                    "SUCCESS"
                }
            }

            "YT_SUBMIT_SEARCH" -> {
                inputController.type("\n")
                delay(3000L)
                "SUCCESS"
            }

            "YT_CLICK_VIDEO_CARD", "YT_ORGANIC_TARGET_SEARCH" -> {
                val targetTitle = params.get("target_title")?.asString
                val targetChannel = params.get("target_channel")?.asString
                val targetVideoId = params.get("target_video_id")?.asString
                val targetVideoUrl = params.get("target_video_url")?.asString
                val spec = TargetSpec(
                    textSnippet = if (!targetTitle.isNullOrBlank()) targetTitle else null,
                    ariaLabel = if (!targetChannel.isNullOrBlank()) targetChannel else null,
                    selector = if (!targetVideoId.isNullOrBlank()) "a[href*=\"$targetVideoId\"]" else null,
                    role = "link"
                )
                val clickOutcome = executeGroundedClick(spec, params, expectedState = "VIDEO_PLAYBACK")
                if (clickOutcome == "SUCCESS") {
                    "SUCCESS"
                } else {
                    // Fallback: If a target URL or ID is known, navigate directly!
                    if (!targetVideoUrl.isNullOrBlank() || !targetVideoId.isNullOrBlank()) {
                        val directUrl = if (!targetVideoId.isNullOrBlank()) "https://m.youtube.com/watch?v=$targetVideoId" else targetVideoUrl!!
                        Log.i(TAG, "Target video not clicked organically, using direct URL fallback: $directUrl")
                        withContext(Dispatchers.Main) { session.loadUri(directUrl) }
                        delay(3500L)
                        "SUCCESS"
                    } else {
                        // Click top video on results page
                        Log.i(TAG, "Clicking top video on results page")
                        val width = targetView.width.toFloat().coerceAtLeast(1080f)
                        val height = targetView.height.toFloat().coerceAtLeast(2400f)
                        inputController.tap(ScreenPoint(width * 0.5f, height * 0.32f))
                        delay(2500L)
                        "SUCCESS"
                    }
                }
            }

            "YT_LIKE_VIDEO" -> {
                val spec = TargetSpec(ariaLabel = "Like", textSnippet = "Like")
                executeGroundedClick(spec, params)
            }

            "YT_SUBSCRIBE_CHANNEL" -> {
                val spec = TargetSpec(ariaLabel = "Subscribe", textSnippet = "Subscribe")
                executeGroundedClick(spec, params)
            }

            "YT_DISMISS_PRE_ROLL_AD", "DISMISS_POPUP" -> {
                val dismissed = recoveryEngine.dismissBlockingOverlay(
                    coordinateMapper = coordinateMapper,
                    getSnapshot = { snapshotBridge.captureSnapshot() }
                )
                if (dismissed) "SUCCESS" else "SKIP"
            }

            "TYPE_TEXT" -> {
                val text = params.get("text")?.asString ?: ""
                val wpm = params.get("wpm")?.asInt ?: 65
                val typo = params.get("typo_probability")?.asDouble ?: 0.03
                inputController.type(text, wpm, typo)
                delay(400L)
                "SUCCESS"
            }

            "BÉZIER_SWIPE" -> {
                val duration = params.get("duration_ms")?.asLong ?: 650L
                val direction = params.get("direction")?.asString ?: "DOWN"

                val width = targetView.width.toFloat().coerceAtLeast(720f)
                val height = targetView.height.toFloat().coerceAtLeast(1280f)

                if (direction == "DOWN") {
                    inputController.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.75f),
                        end = ScreenPoint(width * 0.5f, height * 0.35f),
                        durationMs = duration
                    )
                } else {
                    inputController.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.35f),
                        end = ScreenPoint(width * 0.5f, height * 0.75f),
                        durationMs = duration
                    )
                }
                delay(600L)
                "SUCCESS"
            }

            "YT_SHORTS_SWIPE" -> {
                val width = targetView.width.toFloat().coerceAtLeast(720f)
                val height = targetView.height.toFloat().coerceAtLeast(1280f)
                inputController.swipe(
                    start = ScreenPoint(width * 0.5f, height * 0.80f),
                    end = ScreenPoint(width * 0.5f, height * 0.20f),
                    durationMs = 450L
                )
                delay(800L)
                "SUCCESS"
            }

            "WAIT_PLAYBACK" -> {
                val targetDuration = params.get("duration_seconds")?.asInt ?: 60
                val checkInterval = 2000L
                var elapsed = 0

                // Kickstart video playback on mobile YouTube watch page
                delay(2000L)
                val width = targetView.width.toFloat().coerceAtLeast(1080f)
                val height = targetView.height.toFloat().coerceAtLeast(2400f)
                inputController.tap(ScreenPoint(width * 0.5f, height * 0.25f))

                while (elapsed < targetDuration && isRunning) {
                    delay(checkInterval)
                    elapsed += (checkInterval / 1000).toInt()
                    recordedWatchSeconds = elapsed

                    val snap = snapshotBridge.captureSnapshot(timeoutMs = 800L)
                    if (snap != null) {
                        if (snap.pageState == "AD_ACTIVE") return "AD_ACTIVE"
                        if (snap.pageState == "CONSENT_WALL") return "CONSENT_WALL"
                    }
                }
                "SUCCESS"
            }

            "TIER2_FALLBACK" -> requestAiRecoveryDecision()

            "TERMINATE", "COMPLETE" -> "SUCCESS"

            else -> "SUCCESS"
        }
    }

    private suspend fun executeGroundedClick(
        spec: TargetSpec,
        params: JsonObject,
        expectedState: String? = null
    ): String {
        // 1. Precondition / Target Resolution with Self-Healing Recovery
        val recoveryResult = recoveryEngine.recoverMissingTarget(
            spec = spec,
            maxScrollAttempts = params.get("max_scroll_depth")?.asInt ?: 3,
            getSnapshot = { snapshotBridge.captureSnapshot() }
        )

        val (resolvedTarget, snapshot) = when (recoveryResult) {
            is RecoveryResult.Resolved -> Pair(recoveryResult.target, recoveryResult.freshSnapshot)
            is RecoveryResult.RetryState -> return recoveryResult.reason
            is RecoveryResult.EscalateTier2 -> {
                Log.w(TAG, "Target matching spec $spec could not be resolved locally: ${recoveryResult.reason}")
                return "ELEMENT_NOT_FOUND"
            }
            is RecoveryResult.Abort -> return "FAILURE"
        }

        // 2. Coordinate Mapping
        val screenRect = coordinateMapper.mapDomToScreen(
            rect = resolvedTarget.element.rect,
            dpr = snapshot.viewport.dpr
        )
        val tapPoint = coordinateMapper.getOrganicTapPoint(screenRect)
        Log.i(TAG, "Target matching spec $spec resolved: ${resolvedTarget.element.id}. Grounded tap point: (${tapPoint.x}, ${tapPoint.y})")

        // 3. Physical Native Interaction
        inputController.tap(tapPoint)

        // 4. Assertive Verification
        val verifyTargetState = expectedState ?: params.get("verify_state")?.asString
        if (!verifyTargetState.isNullOrBlank()) {
            val verified = verificationEngine.verifyPageState(
                expectedState = verifyTargetState,
                timeoutMs = 5000L,
                getSnapshot = { snapshotBridge.captureSnapshot() }
            )
            if (verified !is VerificationResult.Verified) {
                Log.w(TAG, "Verification warning for state: $verifyTargetState. Proceeding with caution.")
            }
        } else {
            delay(1000L)
        }

        return "SUCCESS"
    }

    private fun extractTargetSpec(params: JsonObject): TargetSpec {
        return TargetSpec(
            role = params.get("role")?.asString,
            selector = params.get("selector")?.asString,
            textSnippet = params.get("text_snippet")?.asString ?: params.get("text")?.asString,
            ariaLabel = params.get("aria_label")?.asString
        )
    }

    private suspend fun handleTransition(outcome: String, error: String? = null) {
        val jobId = currentJobId ?: return
        val payload = mutableMapOf<String, Any>(
            "outcome" to outcome,
            "context_update" to mapOf("watch_seconds_spent" to recordedWatchSeconds)
        )
        if (error != null) payload["error"] = error

        val response = api.transitionState(jobId, payload)
        val isTerminal = response.get("is_terminal")?.asBoolean ?: false
        val nextState = response.get("current_state_id")?.asString ?: "exit"

        if (isTerminal || nextState == "exit") {
            Log.i(TAG, "Job $jobId finalized. Final State: $nextState")
            currentJobId = null
            currentDag = null
            currentStateId = null
            recordedWatchSeconds = 0
            onStateChanged?.invoke(false, null)
        } else {
            currentStateId = nextState
            onStateChanged?.invoke(isRunning, currentStateId)
        }
    }

    private suspend fun requestAiRecoveryDecision(): String {
        val jobId = currentJobId ?: return "FAILURE"
        Log.i(TAG, "Requesting Tier-2 AI Recovery Decision for Job: $jobId")

        val snapshot = snapshotBridge.captureSnapshot()
        val payload = mutableMapOf<String, Any>()
        if (snapshot != null) {
            payload["page_snapshot"] = gson.toJsonTree(snapshot)
        }

        return try {
            val resp = api.requestDecision(jobId, payload)
            val action = resp.get("action")?.asString ?: "BÉZIER_SWIPE"

            if (action == "TAP_COORDINATES") {
                val x = resp.get("target_x")?.asFloat ?: 500f
                val y = resp.get("target_y")?.asFloat ?: 500f
                inputController.tap(ScreenPoint(x, y))
            } else if (action == "BÉZIER_SWIPE") {
                val width = targetView.width.toFloat().coerceAtLeast(720f)
                val height = targetView.height.toFloat().coerceAtLeast(1280f)
                inputController.swipe(
                    start = ScreenPoint(width * 0.5f, height * 0.70f),
                    end = ScreenPoint(width * 0.5f, height * 0.35f),
                    durationMs = 600L
                )
            }
            "SUCCESS"
        } catch (e: Exception) {
            Log.e(TAG, "AI decision request error: ${e.message}")
            "FAILURE"
        }
    }

    private fun launchHeartbeat(jobId: String) {
        runnerScope?.launch {
            while (isRunning && currentJobId == jobId) {
                try {
                    api.sendHeartbeat(jobId)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
                delay(25000L)
            }
        }
    }
}
