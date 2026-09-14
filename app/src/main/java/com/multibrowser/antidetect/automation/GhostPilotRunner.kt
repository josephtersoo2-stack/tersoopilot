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
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ActionExecutionEntity
import com.multibrowser.antidetect.data.model.ExecutionCheckpointEntity
import com.multibrowser.antidetect.network.GhostPilotApiService
import com.multibrowser.antidetect.network.RetrofitInstance
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession

/**
 * GhostPilot Closed-Loop Automation Runner.
 *
 * Implements:
 * 1. Persistent Execution State & Resumption across process death via ExecutionCheckpointEntity.
 * 2. Idempotent Physical Native Interactions: Prevents duplicate gestures upon network retry.
 * 3. Lifecycle-Safe Coroutine Management: Externally scoped execution without Activity leaks.
 * 4. AI Recovery Safety Verification: Viewport boundary clamping and confidence thresholding.
 * 5. Static DAG Pre-Validation via CommandRegistry.
 * 6. Dynamic Server Limits: Configurable max_steps and timeout_seconds.
 */
data class ExecutionResult(
    val success: Boolean,
    val executionId: String,
    val terminalState: String?,
    val stepsExecuted: Int,
    val error: String? = null
)

class GhostPilotRunner(
    private val context: Context,
    val profileId: String,
    var session: GeckoSession?,
    var targetView: View?,
    var inputController: InputController?,
    var profileName: String = "",
    var cloudSyncId: String = "",
    var deviceId: String = "",
    private val runnerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val TAG = "GhostPilotRunner"
    private val api = RetrofitInstance.retrofit.create(GhostPilotApiService::class.java)
    private val gson = Gson()

    private var snapshotBridge: SnapshotBridge? = session?.let { SnapshotBridge(it) }
    private val targetResolver = TargetResolver()
    private var coordinateMapper: CoordinateMapper? = targetView?.let { CoordinateMapper(it) }
    private val verificationEngine = VerificationEngine()
    private val db = AppDatabase.getDatabase(context)
    private var recoveryEngine: RecoveryEngine? = inputController?.let { RecoveryEngine(it, targetResolver, db) }

    private var executionJob: Job? = null
    private var heartbeatJob: Job? = null

    var isRunning by mutableStateOf(false)
        private set

    private var currentJobId: String? = null
    private var currentDag: JsonObject? = null
    var currentStateId by mutableStateOf<String?>(null)
        private set

    var currentCheckpointVersion: Int = 0
    var currentPlanId: String = ""
    var currentPlanVersion: String = "1"
    var currentContextVars: MutableMap<String, Any> = mutableMapOf()

    private var pendingOutcome: String? = null
    private var transitionId: String? = null
    private var executedSteps = 0
    private var jobStartedAt = 0L
    var recordedWatchSeconds: Int = 0

    // Callbacks to preserve UI & toolbar integration
    var getCurrentUrl: (() -> String)? = null
        set(value) {
            field = value
            snapshotBridge?.getCurrentUrl = value
        }

    var getCurrentTitle: (() -> String)? = null
        set(value) {
            field = value
            snapshotBridge?.getCurrentTitle = value
        }

    var onStateChanged: ((Boolean, String?) -> Unit)? = null

    fun updateSessionAndView(newSession: GeckoSession, newTargetView: View, newInputController: InputController) {
        this.session = newSession
        this.targetView = newTargetView
        this.inputController = newInputController
        this.snapshotBridge = SnapshotBridge(newSession, getCurrentUrl, getCurrentTitle)
        this.coordinateMapper = CoordinateMapper(newTargetView)
        this.recoveryEngine = RecoveryEngine(newInputController, targetResolver, db)
    }

    fun start() {
        if (isRunning) return
        Log.i(TAG, "GhostPilot runner started for profile: $profileId")
        isRunning = true
        onStateChanged?.invoke(true, currentStateId)
    }

    fun stop() {
        val activeJobId = currentJobId
        if (activeJobId != null) {
            runnerScope.launch {
                try {
                    db.dao.clearCheckpoint(activeJobId)
                    db.dao.clearRecoveryAttempts(activeJobId)
                    db.dao.clearJobActions(activeJobId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear checkpoint on stop: ${e.message}")
                }
            }
        }
        isRunning = false
        executionJob?.cancel()
        executionJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        pendingOutcome = null
        transitionId = null
        currentJobId = null
        currentDag = null
        currentStateId = null
        recordedWatchSeconds = 0
        onStateChanged?.invoke(false, null)

        // Nullify view references to prevent leaking Activity or Views on destroy
        targetView = null
        inputController = null
        coordinateMapper = null
        recoveryEngine = null

        Log.i(TAG, "GhostPilot runner stopped for profile: $profileId")
    }

    suspend fun execute(execution: ClaimedExecution): ExecutionResult {
        currentJobId = execution.executionId
        currentDag = execution.compiledDag
        currentStateId = execution.entryState
        currentPlanId = execution.planId.orEmpty()
        currentPlanVersion = execution.planVersion
        currentCheckpointVersion = execution.checkpointVersion
        executedSteps = execution.lastConfirmedStep ?: 0
        jobStartedAt = android.os.SystemClock.elapsedRealtime()
        isRunning = true
        onStateChanged?.invoke(true, currentStateId)

        Log.i(TAG, "GhostPilotRunner executing claimed job ${execution.executionId} on profile $profileId (plan: $currentPlanId v$currentPlanVersion, entry: $currentStateId)")

        // 1. Static Pre-validation of DAG commands before executing
        val validation = CommandRegistry.validateDag(execution.compiledDag)
        if (validation is CommandRegistry.ValidationResult.Invalid) {
            Log.e(TAG, "Rejecting job ${execution.executionId}: ${validation.reason}")
            handleTransition("FAILURE", error = "DAG validation rejected: ${validation.reason}")
            isRunning = false
            return ExecutionResult(
                success = false,
                executionId = execution.executionId,
                terminalState = "FAILURE",
                stepsExecuted = 0,
                error = "DAG validation rejected: ${validation.reason}"
            )
        }

        // 2. Reconcile checkpoint
        if (!execution.lastConfirmedState.isNullOrBlank()) {
            currentStateId = execution.lastConfirmedState
            Log.i(TAG, "Reconciled execution from server checkpoint state: $currentStateId (step: $executedSteps)")
        } else {
            val pendingCheckpoint = db.dao.findActiveCheckpoint(profileId)
            if (pendingCheckpoint != null && pendingCheckpoint.jobId == execution.executionId) {
                currentStateId = pendingCheckpoint.currentStateId
                executedSteps = pendingCheckpoint.executedSteps
                Log.i(TAG, "Restored execution from local Room checkpoint: state $currentStateId (step: $executedSteps)")
            }
        }

        launchHeartbeat(execution.executionId)

        var lastTerminalState: String? = null
        var executionError: String? = null

        try {
            while (isRunning && currentJobId != null) {
                pendingOutcome?.let {
                    handleTransition(it)
                    pendingOutcome = null
                    return@let
                }

                if (currentJobId == null) break

                val maxSteps = currentDag?.get("max_steps")?.asInt ?: 500
                val timeoutSeconds = currentDag?.get("timeout_seconds")?.asLong ?: 1800L
                val timeoutMs = timeoutSeconds * 1000L

                if (executedSteps >= maxSteps || (android.os.SystemClock.elapsedRealtime() - jobStartedAt > timeoutMs)) {
                    Log.i(TAG, "Execution reached limits (steps=$executedSteps/$maxSteps, elapsed=${android.os.SystemClock.elapsedRealtime() - jobStartedAt}ms). Stopping runner.")
                    lastTerminalState = "COMPLETED"
                    break
                }

                val states = currentDag?.getAsJsonObject("states")
                val currentNode = states?.getAsJsonObject(currentStateId)

                if (currentNode == null) {
                    if (currentStateId == "exit" || currentStateId == "TERMINAL_SUCCESS") {
                        lastTerminalState = "COMPLETED"
                    } else {
                        Log.e(TAG, "Node $currentStateId not found in DAG. Marking failure.")
                        handleTransition("FAILURE", error = "Node $currentStateId missing from DAG.")
                        lastTerminalState = "FAILED"
                        executionError = "Node $currentStateId missing from DAG."
                    }
                    break
                }

                val command = currentNode.get("command")?.asString ?: "WAIT"

                // Persist execution checkpoint before running the physical action
                saveCheckpoint(
                    stateId = currentStateId!!,
                    stepIndex = executedSteps,
                    lastCommand = command,
                    checkpointVersion = currentCheckpointVersion
                )

                val params = currentNode.getAsJsonObject("params") ?: JsonObject()
                Log.i(TAG, "Executing step: [$currentStateId] -> Command: $command (step #$executedSteps)")

                // Action Idempotency Check
                val actionId = "${execution.executionId}:$currentStateId:$executedSteps"
                val cachedOutcome = db.dao.getActionOutcome(actionId)

                val outcome = if (cachedOutcome != null) {
                    Log.i(TAG, "Action $actionId already executed with outcome '$cachedOutcome'. Replaying cached outcome without re-triggering gestures.")
                    cachedOutcome
                } else {
                    val result = executeClosedLoopCommand(command, params)
                    db.dao.recordAction(
                        ActionExecutionEntity(
                            actionId = actionId,
                            jobId = execution.executionId,
                            stateId = currentStateId!!,
                            command = command,
                            outcome = result,
                            executedAt = System.currentTimeMillis()
                        )
                    )
                    result
                }

                executedSteps++
                pendingOutcome = outcome
                val reported = reportTransition(outcome)
                pendingOutcome = null

                if (!reported) {
                    Log.w(TAG, "Transition reporting temporarily unavailable, will retry or continue.")
                }

                if (currentJobId == null || !isRunning) {
                    lastTerminalState = if (outcome == "SUCCESS") "COMPLETED" else outcome
                    break
                }
            }
        } catch (e: CancellationException) {
            lastTerminalState = "CANCELLED"
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Execution error: ${e.message}", e)
            lastTerminalState = "FAILED"
            executionError = e.message
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
            isRunning = false
            onStateChanged?.invoke(false, null)
        }

        val finalSuccess = lastTerminalState == "COMPLETED" || lastTerminalState == "SUCCESS" || lastTerminalState == "TERMINAL_SUCCESS"
        return ExecutionResult(
            success = finalSuccess,
            executionId = execution.executionId,
            terminalState = lastTerminalState ?: if (finalSuccess) "COMPLETED" else "FAILED",
            stepsExecuted = executedSteps,
            error = executionError
        )
    }

    private suspend fun executeClosedLoopCommand(command: String, params: JsonObject): String {
        val controller = inputController
        val currentTargetView = targetView
        val currentSession = session

        return when (command) {
            "NAVIGATE" -> {
                val url = params.get("url")?.asString ?: "about:blank"
                Log.i(TAG, "Navigating to: $url")
                if (currentSession != null) {
                    withContext(Dispatchers.Main) { currentSession.loadUri(url) }
                }
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

            "YT_TAP_SEARCH_BAR" -> {
                val spec = TargetSpec(
                    role = "button",
                    textSnippet = "Search",
                    ariaLabel = "Search YouTube",
                    selector = "form#search-form"
                )
                val res = executeGroundedClick(spec, params, expectedState = "SEARCH_INPUT_ACTIVE")
                if (res != "SUCCESS") {
                    Log.i(TAG, "Grounded search icon fallback to organic top-right search icon")
                    val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                    controller?.tap(ScreenPoint(width * 0.90f, 140f))
                    delay(1200L)
                    "SUCCESS"
                } else {
                    "SUCCESS"
                }
            }

            "YT_SUBMIT_SEARCH", "SUBMIT_INPUT" -> {
                controller?.type("\n")
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
                    if (!targetVideoUrl.isNullOrBlank() || !targetVideoId.isNullOrBlank()) {
                        val directUrl = if (!targetVideoId.isNullOrBlank()) "https://m.youtube.com/watch?v=$targetVideoId" else targetVideoUrl!!
                        Log.i(TAG, "Target video not clicked organically, using direct URL fallback: $directUrl")
                        if (currentSession != null) {
                            withContext(Dispatchers.Main) { currentSession.loadUri(directUrl) }
                        }
                        delay(3500L)
                        "SUCCESS"
                    } else {
                        Log.i(TAG, "Clicking top video on results page")
                        val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                        val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                        controller?.tap(ScreenPoint(width * 0.5f, height * 0.32f))
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
                val mapper = coordinateMapper
                val bridge = snapshotBridge
                val rec = recoveryEngine
                if (mapper != null && bridge != null && rec != null) {
                    val dismissed = rec.dismissBlockingOverlay(
                        coordinateMapper = mapper,
                        getSnapshot = { bridge.captureSnapshot() }
                    )
                    if (dismissed) "SUCCESS" else "SKIP"
                } else "SKIP"
            }

            "TYPE_TEXT" -> {
                val text = params.get("text")?.asString ?: ""
                val wpm = params.get("wpm")?.asInt ?: 65
                val typo = params.get("typo_probability")?.asDouble ?: 0.03
                controller?.type(text, wpm, typo)
                delay(400L)
                "SUCCESS"
            }

            "BÉZIER_SWIPE" -> {
                val duration = params.get("duration_ms")?.asLong ?: 650L
                val direction = params.get("direction")?.asString ?: "DOWN"

                val width = (currentTargetView?.width?.toFloat() ?: 720f).coerceAtLeast(720f)
                val height = (currentTargetView?.height?.toFloat() ?: 1280f).coerceAtLeast(1280f)

                if (direction == "DOWN") {
                    controller?.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.75f),
                        end = ScreenPoint(width * 0.5f, height * 0.35f),
                        durationMs = duration
                    )
                } else {
                    controller?.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.35f),
                        end = ScreenPoint(width * 0.5f, height * 0.75f),
                        durationMs = duration
                    )
                }
                delay(600L)
                "SUCCESS"
            }

            "YT_SHORTS_SWIPE" -> {
                val width = (currentTargetView?.width?.toFloat() ?: 720f).coerceAtLeast(720f)
                val height = (currentTargetView?.height?.toFloat() ?: 1280f).coerceAtLeast(1280f)
                controller?.swipe(
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

                delay(2000L)
                val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                controller?.tap(ScreenPoint(width * 0.5f, height * 0.25f))

                while (elapsed < targetDuration && isRunning) {
                    delay(checkInterval)
                    elapsed += (checkInterval / 1000).toInt()
                    recordedWatchSeconds = elapsed

                    val snap = snapshotBridge?.captureSnapshot(timeoutMs = 800L)
                    if (snap != null) {
                        if (snap.pageState == "AD_ACTIVE") return "AD_ACTIVE"
                        if (snap.pageState == "CONSENT_WALL") return "CONSENT_WALL"
                    }
                }
                "SUCCESS"
            }

            "YT_SCRUB_TIMELINE" -> {
                val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                controller?.swipe(
                    start = ScreenPoint(width * 0.2f, height * 0.25f),
                    end = ScreenPoint(width * 0.8f, height * 0.25f),
                    durationMs = 600L
                )
                delay(1500L)
                "SUCCESS"
            }

            "YT_SCROLL_TO_COMMENTS" -> {
                val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                for (i in 1..3) {
                    controller?.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.75f),
                        end = ScreenPoint(width * 0.5f, height * 0.35f),
                        durationMs = 650L
                    )
                    delay(1200L)
                }
                "SUCCESS"
            }

            "YT_DWELL_ON_COMMENTS" -> {
                val duration = params.get("duration_seconds")?.asInt ?: 15
                val checkInterval = 3000L
                var elapsed = 0
                val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                while (elapsed < duration && isRunning) {
                    val isDown = Math.random() > 0.5
                    if (isDown) {
                        controller?.swipe(
                            start = ScreenPoint(width * 0.5f, height * 0.65f),
                            end = ScreenPoint(width * 0.5f, height * 0.45f),
                            durationMs = 800L
                        )
                    } else {
                        controller?.swipe(
                            start = ScreenPoint(width * 0.5f, height * 0.45f),
                            end = ScreenPoint(width * 0.5f, height * 0.65f),
                            durationMs = 800L
                        )
                    }
                    delay(checkInterval)
                    elapsed += (checkInterval / 1000).toInt() + 1
                }
                "SUCCESS"
            }

            "YT_CLICK_UP_NEXT" -> {
                val index = params.get("target_index")?.asInt ?: 1
                val width = (currentTargetView?.width?.toFloat() ?: 1080f).coerceAtLeast(1080f)
                val height = (currentTargetView?.height?.toFloat() ?: 2400f).coerceAtLeast(2400f)
                controller?.swipe(
                    start = ScreenPoint(width * 0.5f, height * 0.75f),
                    end = ScreenPoint(width * 0.5f, height * 0.35f),
                    durationMs = 650L
                )
                delay(1500L)
                val yTarget = if (index == 1) height * 0.4f else height * 0.7f
                controller?.tap(ScreenPoint(width * 0.5f, yTarget))
                delay(3500L)
                "SUCCESS"
            }

            "TIER2_FALLBACK" -> requestAiRecoveryDecision()

            "TERMINATE", "COMPLETE" -> "SUCCESS"

            else -> {
                Log.e(TAG, "Unsupported command: $command")
                "FAILURE"
            }
        }
    }

    private suspend fun executeGroundedClick(
        spec: TargetSpec,
        params: JsonObject,
        expectedState: String? = null
    ): String {
        val bridge = snapshotBridge ?: return "FAILURE"
        val mapper = coordinateMapper ?: return "FAILURE"
        val controller = inputController ?: return "FAILURE"
        val rec = recoveryEngine ?: return "FAILURE"

        val recoveryResult = rec.recoverMissingTarget(
            spec = spec,
            maxScrollAttempts = params.get("max_scroll_depth")?.asInt ?: 3,
            jobId = currentJobId,
            stepId = currentStateId,
            getSnapshot = { bridge.captureSnapshot() }
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

        val screenRect = mapper.mapDomToScreen(
            rect = resolvedTarget.element.rect,
            dpr = snapshot.viewport.dpr
        )
        val tapPoint = mapper.getOrganicTapPoint(screenRect)
        Log.i(TAG, "Target matching spec $spec resolved: ${resolvedTarget.element.id}. Grounded tap point: (${tapPoint.x}, ${tapPoint.y})")

        controller.tap(tapPoint)

        val verifyTargetState = expectedState ?: params.get("verify_state")?.asString
        if (!verifyTargetState.isNullOrBlank()) {
            val verified = verificationEngine.verifyPageState(
                expectedState = verifyTargetState,
                timeoutMs = 5000L,
                getSnapshot = { bridge.captureSnapshot() }
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

    // --- Section 21 Contract Methods ---

    suspend fun restoreCheckpoint(jobId: String? = null): ExecutionCheckpointEntity? {
        return if (jobId != null) {
            db.dao.getCheckpoint(jobId)
        } else {
            db.dao.findActiveCheckpoint(profileId) ?: db.dao.getLatestCheckpointForProfile(profileId)
        }
    }

    suspend fun saveCheckpoint(
        stateId: String,
        stepIndex: Int,
        lastCommand: String = "",
        checkpointVersion: Int = currentCheckpointVersion,
        status: String = "RUNNING"
    ) {
        val jId = currentJobId ?: return
        db.dao.saveCheckpoint(
            ExecutionCheckpointEntity(
                jobId = jId,
                profileId = profileId,
                currentStateId = stateId,
                executedSteps = stepIndex,
                lastCommand = lastCommand,
                status = status,
                updatedAt = System.currentTimeMillis(),
                planId = currentPlanId,
                planVersion = currentPlanVersion,
                contextVars = gson.toJson(currentContextVars),
                lastTransitionId = transitionId ?: "",
                checkpointVersion = checkpointVersion
            )
        )
    }

    suspend fun requestResumePlan(jobId: String): Boolean {
        return try {
            val resp = api.resumeExecution(jobId, deviceId.ifBlank { null })
            val resumeRequired = resp.get("resume_required")?.asBoolean ?: false
            if (resp.has("compiled_dag") && !resp.get("compiled_dag").isJsonNull) {
                currentDag = resp.getAsJsonObject("compiled_dag")
            }
            if (resp.has("state_id") && !resp.get("state_id").isJsonNull) {
                val serverState = resp.get("state_id").asString
                if (serverState.isNotBlank()) {
                    currentStateId = serverState
                }
            }
            if (resp.has("step_index") && !resp.get("step_index").isJsonNull) {
                executedSteps = resp.get("step_index").asInt
            }
            if (resp.has("checkpoint_version") && !resp.get("checkpoint_version").isJsonNull) {
                currentCheckpointVersion = resp.get("checkpoint_version").asInt
            }
            if (resp.has("plan_id") && !resp.get("plan_id").isJsonNull) {
                currentPlanId = resp.get("plan_id").asString
            }
            if (resp.has("plan_version") && !resp.get("plan_version").isJsonNull) {
                currentPlanVersion = resp.get("plan_version").asString
            }
            Log.i(TAG, "Reconciled resume plan for $jobId: state=$currentStateId, step=$executedSteps, version=$currentCheckpointVersion")
            resumeRequired
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request resume plan for $jobId: ${e.message}")
            false
        }
    }

    suspend fun verifyBeforeReplay(expectedState: String?): Boolean {
        if (expectedState.isNullOrBlank()) return false
        val bridge = snapshotBridge ?: return false
        val verified = verificationEngine.verifyPageState(
            expectedState = expectedState,
            timeoutMs = 3000L,
            getSnapshot = { bridge.captureSnapshot() }
        )
        return verified is VerificationResult.Verified
    }

    suspend fun reportTransition(outcome: String, error: String? = null): Boolean {
        val jobId = currentJobId ?: return false
        if (transitionId == null) transitionId = java.util.UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any>(
            "transition_id" to transitionId!!,
            "outcome" to outcome,
            "context_update" to mapOf("watch_seconds_spent" to recordedWatchSeconds)
        )
        if (error != null) payload["error"] = error

        return try {
            val response = api.transitionState(jobId, payload)
            transitionId = null
            currentCheckpointVersion++
            val isTerminal = response.get("is_terminal")?.asBoolean ?: false
            val nextState = response.get("current_state_id")?.asString ?: "exit"

            if (isTerminal || nextState == "exit") {
                Log.i(TAG, "Job $jobId finalized by server. Final State: $nextState")
                handleServerTerminalState("TERMINAL_SUCCESS")
            } else {
                currentStateId = nextState
                saveCheckpoint(nextState, executedSteps + 1, checkpointVersion = currentCheckpointVersion)
                onStateChanged?.invoke(isRunning, currentStateId)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Transition network call failed: ${e.message}. Preserving outcome for retry.")
            pendingOutcome = outcome
            false
        }
    }

    fun handleServerTerminalState(directive: String, reason: String? = null) {
        val activeJobId = currentJobId
        Log.i(TAG, "Handling server terminal directive: $directive for job $activeJobId (reason: $reason)")
        if (activeJobId != null) {
            runnerScope.launch {
                try {
                    val status = when (directive) {
                        "TERMINAL_SUCCESS" -> "COMPLETED"
                        "CANCEL" -> "CANCELLED"
                        else -> "FAILED"
                    }
                    db.dao.updateCheckpointStatus(activeJobId, status)
                    db.dao.clearCheckpoint(activeJobId)
                    db.dao.clearRecoveryAttempts(activeJobId)
                    db.dao.clearJobActions(activeJobId)
                } catch (e: Exception) {
                    Log.w(TAG, "Error cleaning up terminal state: ${e.message}")
                }
            }
        }
        currentJobId = null
        currentDag = null
        currentStateId = null
        recordedWatchSeconds = 0
        currentCheckpointVersion = 0
        onStateChanged?.invoke(false, null)
    }

    private suspend fun handleTransition(outcome: String, error: String? = null) {
        reportTransition(outcome, error)
    }

    private suspend fun requestAiRecoveryDecision(): String {
        val jobId = currentJobId ?: return "FAILURE"
        val controller = inputController ?: return "FAILURE"
        val bridge = snapshotBridge ?: return "FAILURE"

        Log.i(TAG, "Requesting Tier-2 AI Recovery Decision for Job: $jobId")

        val snapshot = bridge.captureSnapshot()
        val payload = mutableMapOf<String, Any>()
        if (snapshot != null) {
            payload["page_snapshot"] = gson.toJsonTree(snapshot)
        }

        return try {
            val resp = api.requestDecision(jobId, payload)

            // Confidence check to prevent acting on low-confidence AI predictions
            val confidence = resp.get("confidence")?.asFloat ?: 1.0f
            if (confidence < 0.8f) {
                Log.w(TAG, "AI recovery decision rejected: confidence ($confidence) is below threshold 0.8")
                return "FAILURE"
            }

            val action = resp.get("action")?.asString ?: "BÉZIER_SWIPE"

            if (action == "TAP_COORDINATES") {
                val x = resp.get("target_x")?.asFloat ?: 500f
                val y = resp.get("target_y")?.asFloat ?: 500f

                // Bounds validation: ensure coordinates stay strictly within view bounds
                val view = targetView
                if (view != null && view.width > 0 && view.height > 0) {
                    if (x < 0f || y < 0f || x > view.width || y > view.height) {
                        Log.w(TAG, "AI recovery coordinates ($x, $y) out of view bounds (${view.width}x${view.height}). Rejecting action.")
                        return "FAILURE"
                    }
                }

                controller.tap(ScreenPoint(x, y))
            } else if (action == "BÉZIER_SWIPE") {
                val view = targetView
                val width = (view?.width?.toFloat() ?: 720f).coerceAtLeast(720f)
                val height = (view?.height?.toFloat() ?: 1280f).coerceAtLeast(1280f)
                controller.swipe(
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
        heartbeatJob?.cancel()
        heartbeatJob = runnerScope.launch {
            while (isRunning && currentJobId == jobId) {
                try {
                    val hbPayload = mutableMapOf<String, Any>(
                        "device_id" to deviceId,
                        "execution_id" to jobId,
                        "current_state_id" to (currentStateId ?: ""),
                        "checkpoint_version" to currentCheckpointVersion,
                        "timestamp" to System.currentTimeMillis()
                    )
                    val heartbeat = api.sendHeartbeat(jobId, hbPayload)
                    val directive = heartbeat.get("action")?.asString
                        ?: heartbeat.get("directive")?.asString
                        ?: heartbeat.get("status")?.asString

                    when (directive) {
                        "TERMINAL_SUCCESS", "TERMINAL_FAILURE", "CANCEL" -> {
                            Log.i(TAG, "Heartbeat received terminal directive $directive. Stopping runner.")
                            handleServerTerminalState(directive)
                            break
                        }
                        "LEASE_EXPIRED" -> {
                            Log.w(TAG, "Heartbeat lease expired on server. Stopping runner.")
                            handleServerTerminalState("LEASE_EXPIRED")
                            break
                        }
                        "RESUME_REQUIRED" -> {
                            Log.i(TAG, "Heartbeat requested resume reconciliation.")
                            requestResumePlan(jobId)
                        }
                        else -> {
                            // CONTINUE / ALIVE - normal execution
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error: ${e.message}")
                }
                delay(25000L)
            }
        }
    }
}
