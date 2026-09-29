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
import com.multibrowser.antidetect.sync.CookieEngine
import kotlinx.coroutines.*
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
    init {
        AnchorRegistry.init(context)
    }

    fun getResolvedDeviceId(): String {
        if (deviceId.isNotBlank()) return deviceId
        val prefs = context.getSharedPreferences("terso_device_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrBlank()) {
            deviceId = saved
            return saved
        }
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        val resolved = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            "android_$androidId"
        } else {
            "android_${java.util.UUID.randomUUID().toString().take(12)}"
        }
        deviceId = resolved
        prefs.edit().putString("device_id", resolved).apply()
        return resolved
    }

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

        // Auto-sync latest visual spatial calibration anchors from backend control plane
        try {
            AnchorRegistry.get()?.checkReloadIfFileModified()
            AnchorRegistry.get()?.syncWithBackend(api)
        } catch (_: Exception) {}

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

                val state = currentStateId
                if (state == null || currentJobId == null) break

                val states = currentDag?.getAsJsonObject("states")
                val currentNode = states?.getAsJsonObject(state)

                if (currentNode == null) {
                    if (state == "exit" || state == "TERMINAL_SUCCESS") {
                        lastTerminalState = "COMPLETED"
                    } else {
                        Log.e(TAG, "Node $state not found in DAG. Marking failure.")
                        handleTransition("FAILURE", error = "Node $state missing from DAG.")
                        lastTerminalState = "FAILED"
                        executionError = "Node $state missing from DAG."
                    }
                    break
                }

                val command = currentNode.get("command")?.asString ?: "WAIT"

                // Persist execution checkpoint before running the physical action
                saveCheckpoint(
                    stateId = state,
                    stepIndex = executedSteps,
                    lastCommand = command,
                    checkpointVersion = currentCheckpointVersion
                )

                val params = currentNode.getAsJsonObject("params") ?: JsonObject()
                Log.i(TAG, "Executing step: [$state] -> Command: $command (step #$executedSteps)")

                // Action Idempotency Check
                val actionId = "${execution.executionId}:$state:$executedSteps"
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
                            stateId = state,
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

        // Synchronize live session cookies back to Room and Backend
        try {
            CookieEngine.syncProfileCookiesById(context, execution.profileId)
        } catch (e: Exception) {
            Log.w(TAG, "Post-execution cookie sync non-fatal error: ${e.message}")
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

    private suspend fun isShortsContext(): Boolean {
        val snap = snapshotBridge?.captureSnapshot(timeoutMs = 500L) ?: return false
        return snap.pageState == "SHORTS_ACTIVE" || snap.url.contains("/shorts")
    }

    private suspend fun executeClosedLoopCommand(command: String, params: JsonObject): String {
        val result = when (command) {
            "WAIT" -> {
                val seconds = params.get("seconds")?.asInt ?: 3
                delay(seconds * 1000L)
                "SUCCESS"
            }
            "WAIT_PLAYBACK" -> executeWaitPlayback(params)
            "PAGE_DWELL" -> executePageDwell(params)
            "TERMINATE", "COMPLETE" -> "SUCCESS"
            else -> {
                val currentSession = session
                if (currentSession != null) {
                    DisplayFocusManager.withDisplayFocus(
                        profileId = profileId,
                        session = currentSession,
                        geckoView = targetView as? GeckoView,
                        actionName = command
                    ) {
                        executeInteractiveCommand(command, params)
                    }
                } else {
                    executeInteractiveCommand(command, params)
                }
            }
        }

        // Honor visual workflow step dwell times (dwell_min..dwell_max or dwell_seconds)
        val dwellSec = when {
            params.has("dwell_seconds") -> params.get("dwell_seconds").asInt
            params.has("dwell_min") && params.has("dwell_max") -> {
                val minD = params.get("dwell_min").asInt
                val maxD = params.get("dwell_max").asInt
                if (maxD > minD) (minD..maxD).random() else minD
            }
            params.has("dwell_min") -> params.get("dwell_min").asInt
            else -> 0
        }
        if (dwellSec > 0 && result == "SUCCESS") {
            Log.i(TAG, "Dwelling on step for ${dwellSec}s as specified in workflow path")
            delay(dwellSec * 1000L)
        }

        return result
    }

    private suspend fun executeInteractiveCommand(command: String, params: JsonObject): String {
        return when (command) {
            "NAVIGATE", "GO_TO_URL" -> executeNavigate(params)
            "SPATIAL_ANCHOR_CLICK", "CLICK_ELEMENT", "CLICK", "GROUNDED_CLICK",
            "YT_TAP_SEARCH_BAR", "YT_SUBMIT_SEARCH" -> executeSpatialAnchorClick(command, params)
            "YT_ORGANIC_TARGET_SEARCH", "YT_SEARCH_AND_DISCOVER", "SEARCH_TARGET_VIDEO",
            "VIDEO_SEARCH", "YT_CLICK_VIDEO_CARD" -> {
                Log.i(TAG, "Routing search command '$command' to executeYouTubeSearchAndDiscover")
                executeYouTubeSearchAndDiscover(params)
            }
            "YT_SCROLL_TO_TARGET", "SCROLL_TO_TARGET", "SCROLL_TARGET_VIDEO",
            "SCROLL_TO_TARGET_VIDEO", "SCROLL_VIDEO", "ORGANIC_SCROLL" -> {
                Log.i(TAG, "Routing scroll command '$command' to executeYouTubeScrollToTarget")
                executeYouTubeScrollToTarget(params)
            }
            "YT_EXPAND_DESCRIPTION", "YT_LIKE_VIDEO", "YT_DISLIKE_VIDEO",
            "YT_SHARE_VIDEO", "YT_SUBSCRIBE_CHANNEL", "YT_DISMISS_PRE_ROLL_AD", "DISMISS_POPUP",
            "YT_SHORTS_SWIPE", "YT_SCRUB_TIMELINE", "YT_SCROLL_TO_COMMENTS",
            "YT_DWELL_ON_COMMENTS", "YT_POST_COMMENT", "YT_CLICK_UP_NEXT" -> {
                Log.i(TAG, "Routing engagement command '$command' to executeYouTubeEngagement")
                executeYouTubeEngagement(command, params)
            }
            "CLICK_LINK" -> executeClickLink(params)
            "TYPE_TEXT" -> executeTypeText(params, submitEnter = false)
            "TYPE_AND_ENTER", "SUBMIT_INPUT", "SUBMIT_FORM" -> executeTypeText(params, submitEnter = true)
            "WAIT_PLAYBACK", "WATCH_VIDEO" -> executeWaitPlayback(params)
            "PAGE_DWELL", "WAIT" -> executePageDwell(params)
            "SWIPE_VERTICAL", "BÉZIER_SWIPE" -> {
                val dir = params.get("direction")?.asString ?: "DOWN"
                val frac = params.get("fraction")?.asFloat ?: 0.35f
                performNaturalScroll(dir, frac)
                "SUCCESS"
            }
            "CLICK_AD_IFRAME" -> executeClickAdIframe(params)
            "AI_TYPE" -> executeAiType(params)
            "DYNAMIC_ACTION", "ADDON_STEP", "EXECUTE_SCRIPT", "CUSTOM_ACTION",
            "DYNAMIC_TAP", "DYNAMIC_SWIPE", "EVALUATE_JAVASCRIPT" -> executeDynamicAddonAction(command, params)
            "TIER2_FALLBACK" -> requestAiRecoveryDecision()
            "TERMINATE", "COMPLETE" -> "SUCCESS"
            else -> {
                if (command.startsWith("ADDON_") || command.startsWith("DYNAMIC_") || command.startsWith("CUSTOM_") ||
                    params.has("script") || params.has("js") || params.has("selector")) {
                    Log.i(TAG, "Routing custom command '$command' to dynamic addon runner")
                    executeDynamicAddonAction(command, params)
                } else if (params.has("spatial_anchor") || params.has("search_anchor") || params.has("normalized_x")) {
                    executeSpatialAnchorClick(command, params)
                } else {
                    Log.w(TAG, "Unrecognized command '$command', proceeding with default success")
                    "SUCCESS"
                }
            }
        }
    }

    /**
     * Universal Dynamic Addon & Extension Runner.
     * Executes arbitrary addon workflows (DOM scripts, spatial taps, gestures, inputs)
     * without modifying core Android application code.
     */
    private suspend fun executeDynamicAddonAction(command: String, params: JsonObject): String {
        Log.i(TAG, "Executing Dynamic Addon Action: $command with params: $params")
        val currentSession = session
        val controller = inputController
        val currentTargetView = targetView
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f

        // 1. Navigation
        if (params.has("url") || params.has("starting_url") || command == "NAVIGATE" || command == "GO_TO_URL") {
            val url = params.get("url")?.asString ?: params.get("starting_url")?.asString
            if (!url.isNullOrBlank()) {
                return executeNavigate(params)
            }
        }

        // 2. Pre-action JavaScript injection
        val preScript = params.get("pre_script")?.asString ?: params.get("before_script")?.asString
        if (!preScript.isNullOrBlank() && currentSession != null) {
            withContext(Dispatchers.Main) {
                currentSession.loadUri("javascript:(function(){ try { $preScript } catch(e){ console.error('Addon pre-script error:', e); } })()")
            }
            delay(500L)
        }

        // 3. Anchor or Selector Clicking
        val anchor = params.get("spatial_anchor")?.asString ?: params.get("anchor")?.asString
        if (!anchor.isNullOrBlank()) {
            if (AnchorRegistry.isKnownAnchor(anchor)) {
                val resolvedKey = AnchorRegistry.resolveKey(anchor)
                val pt = resolveAnchorToLocalPoint(resolvedKey)
                Log.i(TAG, "Dynamic action tapping spatial anchor $resolvedKey at (${pt.x}, ${pt.y})")
                controller?.tap(pt)
                delay(1000L)
            }
        }

        val selector = params.get("selector")?.asString ?: params.get("xpath")?.asString
        if (!selector.isNullOrBlank()) {
            if (AnchorRegistry.isKnownAnchor(selector)) {
                val resolvedKey = AnchorRegistry.resolveKey(selector)
                val pt = resolveAnchorToLocalPoint(resolvedKey)
                controller?.tap(pt)
                delay(1000L)
            } else {
                executeClickElementByXPath(params)
            }
        }

        // 4. Text Input
        val textToType = params.get("text")?.asString ?: params.get("input_text")?.asString ?: params.get("query")?.asString
        if (!textToType.isNullOrBlank() && controller != null) {
            val shouldEnter = params.get("press_enter")?.asBoolean ?: params.get("submit_enter")?.asBoolean ?: false
            val wpm = params.get("wpm")?.asInt ?: 65
            controller.type(textToType, wpm = wpm)
            delay(500L)
            if (shouldEnter) {
                controller.type("\n")
                delay(1500L)
            }
        }

        // 5. Scroll Interaction
        val scrollDirection = params.get("scroll_direction")?.asString ?: params.get("scroll")?.asString
        if (!scrollDirection.isNullOrBlank()) {
            val fraction = params.get("scroll_distance")?.asFloat ?: 0.35f
            performNaturalScroll(scrollDirection, distanceFraction = fraction)
        }

        // 6. Direct Javascript execution (e.g. script / js / execute_script)
        val script = params.get("script")?.asString ?: params.get("js")?.asString ?: params.get("execute_script")?.asString
        if (!script.isNullOrBlank() && currentSession != null) {
            Log.i(TAG, "Executing dynamic addon JavaScript snippet")
            withContext(Dispatchers.Main) {
                currentSession.loadUri("javascript:(function(){ try { $script } catch(e){ console.error('Addon script error:', e); } })()")
            }
            val scriptWait = params.get("delay_ms")?.asLong ?: 1000L
            delay(scriptWait)
        }

        // 7. Post-delay or dwell
        val dwellSec = params.get("duration_seconds")?.asInt ?: params.get("delay_seconds")?.asInt ?: 0
        if (dwellSec > 0) {
            delay(dwellSec * 1000L)
        }

        return "SUCCESS"
    }

    /**
     * Executes a human natural scroll using the new calibrated platform model.
     * Incorporates thumb arc geometry, Bézier acceleration/deceleration,
     * human micro-jitter, and calibrated delay intervals from AnchorRegistry.
     */
    private suspend fun performNaturalScroll(
        direction: String = "DOWN",
        distanceFraction: Float = 0.35f,
        customDurationMs: Long? = null
    ) {
        val currentTargetView = targetView ?: return
        val controller = inputController ?: return
        val width = currentTargetView.width.toFloat().takeIf { it > 10f } ?: 1080f
        val height = currentTargetView.height.toFloat().takeIf { it > 10f } ?: 2400f
        val settings = AnchorRegistry.getSettings()

        // Thumb center with organic lateral variance (48% - 53% of screen width)
        val startX = width * (0.48f + (Math.random().toFloat() * 0.05f))
        val endX = width * (0.48f + (Math.random().toFloat() * 0.05f))

        val startY: Float
        val endY: Float

        if (direction.equals("DOWN", ignoreCase = true)) {
            // Scrolling down reveals content below: finger moves upwards from bottom to top
            startY = height * (0.70f + (Math.random().toFloat() * 0.05f))
            endY = (startY - (height * distanceFraction)).coerceIn(height * 0.15f, height * 0.85f)
        } else {
            // Scrolling up reveals content above: finger moves downwards from top to bottom
            startY = height * (0.35f + (Math.random().toFloat() * 0.05f))
            endY = (startY + (height * distanceFraction)).coerceIn(height * 0.15f, height * 0.85f)
        }

        val duration = customDurationMs ?: (settings.scrollDurationMin..settings.scrollDurationMax).random()

        controller.swipe(
            start = ScreenPoint(startX, startY),
            end = ScreenPoint(endX, endY),
            durationMs = duration
        )

        // Calibrated natural delay between scrolls
        val delayMinMs = (settings.naturalScrollDelayMin * 1000).toLong()
        val delayMaxMs = (settings.naturalScrollDelayMax * 1000).toLong()
        val pause = if (delayMaxMs > delayMinMs) (delayMinMs..delayMaxMs).random() else delayMinMs
        delay(pause)
    }

    /**
     * Executes natural human overshoot flick past target, pauses, and gently
     * scrolls back up to center the element comfortably in the viewport.
     */
    private suspend fun performOvershootCorrection() {
        val settings = AnchorRegistry.getSettings()
        if (!settings.overshootScrollEnabled) return

        val currentTargetView = targetView ?: return
        val controller = inputController ?: return
        val width = currentTargetView.width.toFloat().takeIf { it > 10f } ?: 1080f
        val height = currentTargetView.height.toFloat().takeIf { it > 10f } ?: 2400f

        Log.i(TAG, "Executing human natural overshoot flick past target...")
        // Quick gentle flick downward (overshoot)
        val overStartX = width * (0.50f + (Math.random().toFloat() * 0.04f - 0.02f))
        val overEndX = width * (0.50f + (Math.random().toFloat() * 0.04f - 0.02f))
        controller.swipe(
            start = ScreenPoint(overStartX, height * 0.62f),
            end = ScreenPoint(overEndX, height * 0.44f),
            durationMs = (400L..550L).random()
        )

        // Natural cognitive realization pause
        delay((900L..1400L).random())

        Log.i(TAG, "Gently scrolling back up to center target in viewport...")
        // Corrective upward scroll
        val corrStartX = width * (0.50f + (Math.random().toFloat() * 0.04f - 0.02f))
        val corrEndX = width * (0.50f + (Math.random().toFloat() * 0.04f - 0.02f))
        controller.swipe(
            start = ScreenPoint(corrStartX, height * 0.42f),
            end = ScreenPoint(corrEndX, height * 0.60f),
            durationMs = (450L..650L).random()
        )
        delay((1000L..1800L).random())
    }

    private suspend fun executePageDwell(params: JsonObject): String {
        val dwell = params.get("duration_seconds")?.asInt ?: 60
        val scrollReading = params.get("scroll_reading")?.asBoolean ?: true
        val checkInterval = 3000L
        var elapsed = 0

        while (elapsed < dwell && isRunning) {
            delay(checkInterval)
            elapsed += (checkInterval / 1000).toInt()
            if (scrollReading && Math.random() > 0.4) {
                val currentSession = session
                if (currentSession != null) {
                    try {
                        DisplayFocusManager.withDisplayFocus(
                            profileId = profileId,
                            session = currentSession,
                            geckoView = targetView as? GeckoView,
                            actionName = "PAGE_DWELL_SWIPE"
                        ) {
                            performNaturalScroll("DOWN", distanceFraction = 0.25f)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Dwell swipe skipped display focus: ${e.message}")
                    }
                }
            }
        }
        return "SUCCESS"
    }

    /**
     * Resolves a spatial anchor key to local targetView coordinates,
     * fully compensating for status bar, address bar, and GeckoView's layout offset on screen.
     */
    fun resolveAnchorToLocalPoint(anchorKey: String, randomizeJitter: Boolean = true): ScreenPoint {
        val resolved = AnchorRegistry.resolveKey(anchorKey)
        val anchor = AnchorRegistry.getAnchor(resolved)
        val currentTargetView = targetView
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val physX = (anchor.x / 1000f) * screenW
        val physY = (anchor.y / 1000f) * screenH

        val location = IntArray(2)
        currentTargetView?.getLocationOnScreen(location)
        val viewW = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: screenW
        val viewH = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: screenH

        val localX = (physX - location[0]).coerceIn(0f, viewW)
        val localY = (physY - location[1]).coerceIn(0f, viewH)

        return if (randomizeJitter) {
            val jitterX = (Math.random().toFloat() * 6f - 3f)
            val jitterY = (Math.random().toFloat() * 6f - 3f)
            ScreenPoint((localX + jitterX).coerceIn(0f, viewW), (localY + jitterY).coerceIn(0f, viewH))
        } else {
            ScreenPoint(localX, localY)
        }
    }

    /**
     * Executes a calibrated spatial anchor click with millimeter precision.
     * Maps normalized (0..1000) coordinates to exact screen pixels,
     * compensating for GeckoView's layout offset within the Activity window.
     */
    private suspend fun executeSpatialAnchorClick(command: String, params: JsonObject): String {
        val controller = inputController ?: return "FAILURE"
        val anchorKey = params.get("spatial_anchor")?.asString
            ?: params.get("search_anchor")?.asString
            ?: params.get("anchor")?.asString
            ?: params.get("selector")?.asString
            ?: params.get("xpath")?.asString
            ?: when (command) {
                "YT_TAP_SEARCH_BAR" -> AnchorRegistry.SEARCH_BUTTON_HOME
                "YT_SUBMIT_SEARCH" -> AnchorRegistry.SEARCH_SUBMIT
                "YT_LIKE_VIDEO" -> AnchorRegistry.LIKE_BUTTON
                "YT_DISLIKE_VIDEO" -> AnchorRegistry.DISLIKE_BUTTON
                "YT_SHARE_VIDEO" -> AnchorRegistry.SHARE_BUTTON
                "YT_SUBSCRIBE_CHANNEL" -> AnchorRegistry.SUBSCRIBE_BUTTON
                "YT_SCROLL_TO_COMMENTS" -> AnchorRegistry.COMMENTS_SECTION
                "YT_EXPAND_DESCRIPTION" -> AnchorRegistry.DESCRIPTION_EXPAND
                else -> ""
            }

        val tapPoint = if (params.has("normalized_x") && params.has("normalized_y")) {
            val normX = params.get("normalized_x").asInt
            val normY = params.get("normalized_y").asInt
            val metrics = context.resources.displayMetrics
            val physX = (normX / 1000f) * metrics.widthPixels
            val physY = (normY / 1000f) * metrics.heightPixels
            val location = IntArray(2)
            targetView?.getLocationOnScreen(location)
            val viewW = targetView?.width?.toFloat()?.takeIf { it > 10f } ?: metrics.widthPixels.toFloat()
            val viewH = targetView?.height?.toFloat()?.takeIf { it > 10f } ?: metrics.heightPixels.toFloat()
            ScreenPoint((physX - location[0]).coerceIn(0f, viewW), (physY - location[1]).coerceIn(0f, viewH))
        } else {
            resolveAnchorToLocalPoint(anchorKey)
        }

        Log.i(TAG, "Executing deterministic SPATIAL_ANCHOR_CLICK on '$anchorKey' (command: $command): localView=(${tapPoint.x}, ${tapPoint.y})")
        controller.tap(tapPoint)
        val delayMs = params.get("delay_ms")?.asLong ?: 1000L
        delay(delayMs)
        return "SUCCESS"
    }

    private suspend fun executeNavigate(params: JsonObject): String {
        val url = params.get("url")?.asString 
            ?: params.get("starting_url")?.asString 
            ?: "about:blank"
        val referrer = params.get("referrer")?.asString?.takeIf { it.isNotBlank() }
        Log.i(TAG, "Navigating to: $url (referrer: $referrer)")
        val currentSession = session
        if (currentSession != null) {
            withContext(Dispatchers.Main) { currentSession.loadUri(url) }
        }
        delay(3500L)
        return "SUCCESS"
    }

    private suspend fun executeWaitPlayback(params: JsonObject): String {
        val controller = inputController
        val currentTargetView = targetView
        val currentSession = session
        val bridge = snapshotBridge
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f

        val targetVideoId = params.get("target_video_id")?.asString
            ?: params.get("video_id")?.asString
            ?: currentContextVars["target_video_id"] as? String
        val targetVideoUrl = params.get("target_video_url")?.asString
            ?: params.get("video_url")?.asString
            ?: currentContextVars["target_video_url"] as? String
        val targetQuery = targetVideoId?.ifBlank { null } ?: targetVideoUrl ?: ""

        val targetDuration = params.get("duration_seconds")?.asInt ?: 60

        // 1. Check if we need to click the target video card to initiate watch session
        val activeUrl = getCurrentUrl?.invoke() ?: ""
        val isAlreadyPlaying = activeUrl.contains("/watch") || activeUrl.contains("/shorts") ||
            (!targetVideoId.isNullOrBlank() && activeUrl.contains(targetVideoId))

        if (!isAlreadyPlaying && targetQuery.isNotBlank()) {
            Log.i(TAG, "WAIT_PLAYBACK inherited target video '$targetQuery'. Initiating watch session...")
            bridge?.dismissOpenPopupsOrSheets()
            delay(300L)

            val extractedId = Regex("(?:v=|/shorts/|/embed/|\\.be/|vi/)([a-zA-Z0-9_-]{11})").find(targetQuery)?.groupValues?.get(1) ?: targetQuery.trim()

            // ── Strategy 1: Target DOM thumbnail with touch & pointer events (bypasses 3-dots and coordinates) ──
            Log.i(TAG, "Attempting DOM touch & click on thumbnail for '$targetQuery' (ID: $extractedId)...")
            val jsClick = "javascript:(function(){ try { var vId = '$extractedId'; var card = null; var links = Array.from(document.querySelectorAll('a[href*=\"' + vId + '\"]')); if (links.length > 0) { card = links[0].closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, div.media-item') || links[0].parentElement; } else { var imgs = Array.from(document.querySelectorAll('img[src*=\"' + vId + '\"], img[src*=\"/vi/' + vId + '/\"]')); if (imgs.length > 0) { card = imgs[0].closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, div.media-item') || imgs[0].parentElement; } } if (!card) { var all = Array.from(document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, div.media-item')); for (var i = 0; i < all.length; i++) { if (all[i].innerHTML.indexOf(vId) !== -1) { card = all[i]; break; } } } if (card) { var thumb = card.querySelector('img.yt-core-image, img, ytm-thumbnail-cover, a.media-item-thumbnail-container, .video-thumbnail-container, .thumbnail') || card; var rect = thumb.getBoundingClientRect(); var cx = rect.left + rect.width * 0.35; var cy = rect.top + rect.height * 0.45; ['pointerdown', 'touchstart', 'pointerup', 'touchend', 'click'].forEach(function(t){ try { thumb.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy})); }catch(e){} }); var a = card.querySelector('a') || (card.tagName === 'A' ? card : null); if (a) a.click(); } } catch(e){} })()"
            currentSession?.loadUri(jsClick)
            delay(2000L)

            // Check if JS click worked
            var postJsUrl = getCurrentUrl?.invoke() ?: ""
            val jsClickWorked = postJsUrl.contains("/watch") || postJsUrl.contains("/shorts") ||
                (!targetVideoId.isNullOrBlank() && postJsUrl.contains(targetVideoId))

            if (jsClickWorked) {
                Log.i(TAG, "JavaScript click successfully navigated to watch page: $postJsUrl")
            } else {
                // ── Strategy 2: Physical tap with SAFE LEFT-CENTER coordinates (far away from 3-dot menu) ──
                Log.i(TAG, "JS click did not navigate. Attempting safe physical tap...")
                val spot = bridge?.scanForVideoTarget(targetQuery, timeoutMs = 3500L, targetCleanTitle = currentContextVars["target_title"] as? String)
                if (spot?.rect != null && coordinateMapper != null) {
                    val dpr = if (spot.dpr > 0f) spot.dpr else 2.625f
                    val screenRect = coordinateMapper!!.mapDomToScreen(spot.rect, dpr)
                    // The 3-dot overflow menu sits on the far-right edge (x > 850px).
                    // Confine safeX firmly to the left thumbnail area (between 30px and 220px)
                    val safeX = (screenRect.left + 50f).coerceIn(30f, (width * 0.28f).coerceAtMost(250f))
                    val safeY = screenRect.centerY().coerceIn(100f, height - 150f)
                    val tapPoint = ScreenPoint(safeX, safeY)
                    Log.i(TAG, "Tapping SAFE LEFT thumbnail at (${tapPoint.x}, ${tapPoint.y})")
                    controller?.tap(tapPoint)
                    delay(2500L)

                    bridge?.dismissOpenPopupsOrSheets()
                } else {
                    Log.i(TAG, "scanForVideoTarget returned no rect. Skipping physical tap.")
                }

                // Check if physical tap worked
                val postTapUrl = getCurrentUrl?.invoke() ?: ""
                val tapWorked = postTapUrl.contains("/watch") || postTapUrl.contains("/shorts") ||
                    (!targetVideoId.isNullOrBlank() && postTapUrl.contains(targetVideoId))

                if (!tapWorked && !targetVideoId.isNullOrBlank()) {
                    // ── Strategy 3: Direct URL navigation (guaranteed fallback) ──
                    Log.i(TAG, "Direct URL navigation fallback: https://m.youtube.com/watch?v=$targetVideoId")
                    withContext(Dispatchers.Main) {
                        currentSession?.loadUri("https://m.youtube.com/watch?v=$targetVideoId")
                    }
                    delay(3500L)
                } else if (tapWorked) {
                    Log.i(TAG, "Physical tap successfully navigated to watch page: $postTapUrl")
                }
            }
            bridge?.enforceLowestBitrate()
        }

        val checkInterval = 2000L
        var elapsed = 0

        delay(2000L)

        // Android mobile browsers (GeckoView) require a user gesture to satisfy HTML5 video autoplay policies.
        // Inject a physical hardware tap directly onto the video player overlay to activate playback.
        val playerPoint = resolveAnchorToLocalPoint(AnchorRegistry.PLAYER_PLAY_PAUSE, randomizeJitter = false)
        Log.i(TAG, "Tapping PLAYER_PLAY_PAUSE at (${playerPoint.x}, ${playerPoint.y}) to satisfy mobile autoplay policy")
        controller?.tap(playerPoint)
        delay(1200L)

        // Resume playback and click video player play overlays
        currentSession?.loadUri("javascript:(function(){ try { var btn = document.querySelector('.ytp-large-play-button, button.ytp-play-button, ytm-play-pause-button-renderer button, .player-control-play-pause-icon, .html5-video-player'); if (btn) btn.click(); var v = document.querySelector('video'); if (v) { v.muted = false; if (v.paused) v.play(); } } catch(e){} })()")

        Log.i(TAG, "Starting organic watch session for $targetDuration seconds")

        while (elapsed < targetDuration && isRunning) {
            delay(checkInterval)
            elapsed += (checkInterval / 1000).toInt()
            recordedWatchSeconds = elapsed

            if (elapsed % 10 == 0 || elapsed >= targetDuration) {
                Log.i(TAG, "Organic watch progress: ${elapsed}s / ${targetDuration}s")
            }

            // During initial watch window, ensure video hasn't stalled or paused
            if (elapsed in 4..14 && elapsed % 4 == 0) {
                currentSession?.loadUri("javascript:(function(){ try { var v = document.querySelector('video'); if (v && v.paused) { var btn = document.querySelector('.ytp-large-play-button, button.ytp-play-button, ytm-play-pause-button-renderer button'); if (btn) btn.click(); v.play(); } } catch(e){} })()")
            }

            val snap = snapshotBridge?.captureSnapshot(timeoutMs = 800L)
            if (snap != null) {
                if (snap.pageState == "AD_ACTIVE") return "AD_ACTIVE"
                if (snap.pageState == "CONSENT_WALL") return "CONSENT_WALL"
            }
        }
        Log.i(TAG, "Organic watch session finished: watched ${recordedWatchSeconds}s")
        return "SUCCESS"
    }

    private suspend fun executeYouTubeSearchAndDiscover(params: JsonObject): String {
        val controller = inputController
        val currentTargetView = targetView
        val currentSession = session
        val bridge = snapshotBridge
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f
        val settings = AnchorRegistry.getSettings()

        val isFeedSelection = params.get("feed_selection")?.asBoolean == true
        if (isFeedSelection) {
            Log.i(TAG, "Executing organic feed video selection on upper card thumbnail")
            val cardTapPoint = ScreenPoint(width * 0.5f, height * 0.35f)
            controller?.tap(cardTapPoint)
            delay(3000L)
            return "SUCCESS"
        }

        val targetTitle = params.get("target_title")?.asString
        val targetChannel = params.get("target_channel")?.asString
        val targetVideoId = params.get("target_video_id")?.asString ?: params.get("video_id")?.asString
        val targetVideoUrl = params.get("target_video_url")?.asString ?: params.get("video_url")?.asString
        val wpm = params.get("wpm")?.asInt ?: 65
        val typo = params.get("typo_probability")?.asDouble ?: 0.03
        val searchRetryMode = params.get("search_retry_mode")?.asString ?: "CLEAR_SEARCH_BAR"

        // Build sequential search query queue: candidate_keywords + [target_title]
        val searchQueue = mutableListOf<String>()
        fun addKeyword(raw: String?) {
            if (raw.isNullOrBlank()) return
            val parts = if (raw.contains("\n")) raw.split("\n") else if (raw.contains(",")) raw.split(",") else listOf(raw)
            for (p in parts) {
                val clean = p.trim()
                if (clean.isNotBlank() && !searchQueue.any { it.equals(clean, ignoreCase = true) }) {
                    searchQueue.add(clean)
                }
            }
        }

        // 1. Extract candidate_keywords (array or string)
        val candidateArray = params.get("candidate_keywords")?.let { if (it.isJsonArray) it.asJsonArray else null }
        if (candidateArray != null) {
            for (elem in candidateArray) {
                addKeyword(elem.asString)
            }
        } else {
            addKeyword(params.get("candidate_keywords")?.let { if (it.isJsonPrimitive) it.asString else null })
        }

        // 2. Extract target_keywords (array or string)
        val targetKwArray = params.get("target_keywords")?.let { if (it.isJsonArray) it.asJsonArray else null }
        if (targetKwArray != null) {
            for (elem in targetKwArray) {
                addKeyword(elem.asString)
            }
        } else {
            addKeyword(params.get("target_keywords")?.let { if (it.isJsonPrimitive) it.asString else null })
        }

        // 3. Fallback to individual keyword fields (Primary + Fallbacks) if list was empty
        if (searchQueue.isEmpty()) {
            addKeyword(params.get("keyword")?.asString)
            addKeyword(params.get("fallback_keyword_1")?.asString)
            addKeyword(params.get("fallback_keyword_2")?.asString)
            addKeyword(params.get("target_keyword")?.asString)
            addKeyword(params.get("text")?.asString)
            addKeyword(params.get("query")?.asString)
        }

        // 4. Append exact video title as the final guaranteed search query
        val cleanTitle = targetTitle?.trim()
        if (!cleanTitle.isNullOrBlank() && !searchQueue.any { it.equals(cleanTitle, ignoreCase = true) }) {
            searchQueue.add(cleanTitle)
        }

        // 5. Absolute fallback query if nothing was specified
        if (searchQueue.isEmpty()) {
            val fallbackQ = targetVideoId?.ifBlank { null } ?: "trending"
            searchQueue.add(fallbackQ)
        }

        Log.i(TAG, "Initiating Smart YouTube Search & Discovery Engine with ${searchQueue.size} queries: $searchQueue")

        var extractedVideoId = targetVideoId?.ifBlank { null }
        if (extractedVideoId == null && !targetVideoUrl.isNullOrBlank()) {
            val match = Regex("(?:v=|/)([0-9A-Za-z_-]{11})(?:[?&/]|$)").find(targetVideoUrl)
            if (match != null) {
                extractedVideoId = match.groupValues[1]
                Log.i(TAG, "Extracted target video ID '$extractedVideoId' from URL: $targetVideoUrl")
            }
        }
        val targetScanQuery = extractedVideoId ?: targetVideoUrl ?: cleanTitle ?: ""

        // Check 0: If target video is ALREADY open or playing on screen, stick to it and complete search step
        val activeUrl = getCurrentUrl?.invoke() ?: ""
        if (!targetVideoId.isNullOrBlank() && activeUrl.contains(targetVideoId)) {
            Log.i(TAG, "Target video ($targetVideoId) is ALREADY active on screen. Marking search step complete!")
            currentContextVars["target_video_id"] = extractedVideoId ?: ""
            currentContextVars["target_video_url"] = targetVideoUrl ?: ""
            currentContextVars["target_title"] = cleanTitle ?: ""
            return "SUCCESS"
        }

        for ((qIndex, query) in searchQueue.withIndex()) {
            if (!isRunning) break

            val isExactTitlePass = (qIndex == searchQueue.size - 1 && cleanTitle != null && query.equals(cleanTitle, ignoreCase = true))
            val passLabel = when {
                qIndex == 0 -> "First Keyword"
                isExactTitlePass -> "Exact Title Fallback"
                qIndex == searchQueue.size - 1 -> "Last Keyword"
                else -> "Middle Keyword #${qIndex + 1}"
            }
            Log.i(TAG, "=== Search Pass [${qIndex + 1}/${searchQueue.size} - $passLabel]: '$query' ===")

            // 1. Scroll to top so search bar is cleanly visible
            currentSession?.loadUri("javascript:window.scrollTo({top: 0, behavior: 'smooth'});")
            delay(400L)

            // 2. Dismiss any open bottom sheets or 3-dots popup before starting search
            bridge?.dismissOpenPopupsOrSheets()
            delay(300L)

            // 3. Open or focus search input
            val currentUrlForSearch = getCurrentUrl?.invoke() ?: ""
            val isOnSearchResults = currentUrlForSearch.contains("search_query") || currentUrlForSearch.contains("/results") || currentUrlForSearch.contains("search?")
            val searchInputStatus = bridge?.verifySearchInputOpen(timeoutMs = 600L)
            val isSearchAlreadyOpen = searchInputStatus?.open == true

            if (!isSearchAlreadyOpen) {
                if (isOnSearchResults) {
                    val searchButtonKey = AnchorRegistry.SEARCH_BUTTON_RESULTS
                    val searchTapPoint = resolveAnchorToLocalPoint(searchButtonKey)
                    Log.i(TAG, "Tapping calibrated results search icon $searchButtonKey: (${searchTapPoint.x}, ${searchTapPoint.y})")
                    controller?.tap(searchTapPoint)
                    delay(800L)
                } else {
                    val searchButtonKey = when {
                        currentUrlForSearch.contains("watch") || currentUrlForSearch.contains("shorts") -> AnchorRegistry.SEARCH_BUTTON_WATCH
                        else -> AnchorRegistry.SEARCH_BUTTON_HOME
                    }
                    val searchTapPoint = resolveAnchorToLocalPoint(searchButtonKey)
                    Log.i(TAG, "Tapping header search icon $searchButtonKey: (${searchTapPoint.x}, ${searchTapPoint.y})")
                    controller?.tap(searchTapPoint)
                    delay(800L)
                }
            } else {
                Log.i(TAG, "Search input is already open on page. Skipping redundant search button tap.")
            }

            // 4. Focus search input field
            val inputTapPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_INPUT)
            Log.i(TAG, "Tapping SEARCH_INPUT: (${inputTapPoint.x}, ${inputTapPoint.y})")
            controller?.tap(inputTapPoint)
            delay(500L)

            // 5. Clear existing search text cleanly
            currentSession?.loadUri("javascript:(function(){ try { var inp = document.querySelector('input.search-input, input[type=\"search\"], input[name=\"search_query\"], input#search'); if (inp) { inp.value = ''; inp.dispatchEvent(new Event('input', {bubbles: true})); } var clr = document.querySelector('button[aria-label*=\"Clear\" i], .searchbox-clear-button'); if (clr) clr.click(); } catch(e){} })()")
            delay(250L)

            // 6. Manually type keyword letter-by-letter with profile persona WPM & typo rate
            Log.i(TAG, "Typing search query '$query' letter-by-letter at $wpm WPM (typo rate: $typo)...")
            controller?.type(query, wpm, typo)
            delay(500L)

            // 7. Submit search via calibrated submit anchor & Enter keystroke
            val submitTapPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_SUBMIT)
            controller?.tap(submitTapPoint)
            controller?.type("\n")
            currentSession?.loadUri("javascript:(function(){ try { var f = document.querySelector('form#search-form, form.searchbox'); if (f) f.submit(); } catch(e){} })()")
            delay(2000L)

            // Check if navigation succeeded; fallback to direct URL if needed
            val postSearchUrl = getCurrentUrl?.invoke() ?: ""
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            if (!postSearchUrl.contains("search_query") && !postSearchUrl.contains("/results")) {
                Log.w(TAG, "Search submission did not navigate. Loading search URL directly: https://m.youtube.com/results?search_query=$encodedQuery")
                withContext(Dispatchers.Main) {
                    currentSession?.loadUri("https://m.youtube.com/results?search_query=$encodedQuery")
                }
                delay(2000L)
            }

            // CRITICAL: Ensure search results page is FULLY loaded with video cards rendered before proceeding
            Log.i(TAG, "Waiting for search results page to fully load and render video cards...")
            val isPageLoaded = bridge?.waitForSearchResultsLoaded(timeoutMs = 15000L) ?: false
            if (!isPageLoaded) {
                Log.w(TAG, "Search results page did not report ready. Loading direct search URL as fallback...")
                withContext(Dispatchers.Main) {
                    currentSession?.loadUri("https://m.youtube.com/results?search_query=$encodedQuery")
                }
                bridge?.waitForSearchResultsLoaded(timeoutMs = 12000L)
            }

            // Allow search results page to settle naturally and images/virtualized DOM to stabilize
            delay((2000L..3000L).random())

            // 8. Background Scan & Natural Scroll Loop through top 10 results
            Log.i(TAG, "Initiating background scan and natural scroll loop to inspect top 10 results for target video ($targetScanQuery)...")
            var targetFound = false
            var foundRank = -1
            var bestScanResult: SearchResultsScanResult? = null
            var bestVideoSpot: VideoScanResult? = null

            // Initial check on top of search results
            val initialScan = bridge?.scanSearchResults(
                videoUrlOrId = targetScanQuery,
                maxResults = 10,
                targetCleanTitle = cleanTitle,
                timeoutMs = 3500L
            )
            if (initialScan != null && initialScan.targetFound) {
                targetFound = true
                foundRank = if (initialScan.targetIndex >= 0) initialScan.targetIndex + 1 else 1
                bestScanResult = initialScan
                Log.i(TAG, "Target video FOUND immediately on initial search results view (rank #$foundRank)!")
            } else {
                val initialSpot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 2500L, targetCleanTitle = cleanTitle)
                if (initialSpot != null && initialSpot.found) {
                    targetFound = true
                    foundRank = 1
                    bestVideoSpot = initialSpot
                    Log.i(TAG, "Target video SPOTTED on initial search results view via scanForVideoTarget!")
                }
            }

            // Natural human scrolling to reveal and inspect top 10 virtualized cards
            val maxInspectionScrolls = 4
            var inspectScroll = 0
            while (!targetFound && inspectScroll < maxInspectionScrolls && isRunning) {
                inspectScroll++
                Log.i(TAG, "Target video not in top view. Natural scroll batch #$inspectScroll of $maxInspectionScrolls to uncover top 10 results...")
                val scrollDist = (0.28f + Math.random().toFloat() * 0.10f)
                performNaturalScroll("DOWN", distanceFraction = scrollDist)
                delay((1200L..2000L).random())

                val currentScan = bridge?.scanSearchResults(
                    videoUrlOrId = targetScanQuery,
                    maxResults = 10,
                    targetCleanTitle = cleanTitle,
                    timeoutMs = 3000L
                )
                if (currentScan != null && currentScan.targetFound) {
                    targetFound = true
                    foundRank = if (currentScan.targetIndex >= 0) currentScan.targetIndex + 1 else (inspectScroll * 2 + 1)
                    bestScanResult = currentScan
                    Log.i(TAG, "Target video FOUND after scroll batch #$inspectScroll (estimated rank #$foundRank)!")
                    break
                }

                val currentSpot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 2500L, targetCleanTitle = cleanTitle)
                if (currentSpot != null && currentSpot.found) {
                    targetFound = true
                    foundRank = (inspectScroll * 2 + 1)
                    bestVideoSpot = currentSpot
                    Log.i(TAG, "Target video SPOTTED after scroll batch #$inspectScroll!")
                    break
                }
            }

            if (targetFound) {
                Log.i(TAG, "Target video FOUND in top 10 results for query '$query' (rank #$foundRank)! Executing natural browsing, clicking video, and locking in search step.")

                // Natural browsing: scroll past and return or center comfortably in viewport
                val shouldScrollPast = params.get("scroll_past_and_return")?.asBoolean ?: true
                if (shouldScrollPast) {
                    performOvershootCorrection()
                } else {
                    val spot = bestVideoSpot ?: bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 2000L, targetCleanTitle = cleanTitle)
                    if (spot != null && spot.found) {
                        if (spot.isBelowViewport) {
                            performNaturalScroll("DOWN", distanceFraction = 0.20f)
                        } else if (spot.isAboveViewport) {
                            performNaturalScroll("UP", distanceFraction = 0.20f)
                        }
                    }
                    delay((800L..1400L).random())
                }

                // Click target video card / thumbnail
                Log.i(TAG, "Clicking target video card to initiate playback...")
                val mapper = coordinateMapper
                val freshSpot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 2500L, targetCleanTitle = cleanTitle)
                val targetRect = freshSpot?.rect ?: bestScanResult?.targetItem?.rect
                if (targetRect != null && mapper != null) {
                    val dpr = freshSpot?.dpr ?: bestScanResult?.dpr ?: 1f
                    val screenRect = mapper.mapDomToScreen(targetRect, dpr)
                    val tapPoint = mapper.getOrganicTapPoint(screenRect)
                    Log.i(TAG, "Tapping target video thumbnail at screen point (${tapPoint.x}, ${tapPoint.y})...")
                    controller?.tap(tapPoint)
                } else {
                    val fallbackPoint = ScreenPoint(width * 0.5f, height * 0.35f)
                    Log.i(TAG, "Tapping upper-center video card area at (${fallbackPoint.x}, ${fallbackPoint.y})...")
                    controller?.tap(fallbackPoint)
                }

                // Bulletproof DOM click fallback
                val safeVideoId = extractedVideoId ?: ""
                withContext(Dispatchers.Main) {
                    val clickJs = "javascript:(function(){ try { " +
                        "var vid = '$safeVideoId';" +
                        "var el = vid ? document.querySelector('a[href*=\"' + vid + '\"], img[src*=\"' + vid + '\"]') : null;" +
                        "if (!el) el = document.querySelector('ytm-video-with-context-renderer, ytm-compact-video-renderer');" +
                        "if (el) { var a = el.closest('a') || el.querySelector('a') || el; a.click(); }" +
                        "} catch(e){} })();"
                    currentSession?.loadUri(clickJs)
                }

                delay((3000L..4500L).random())
                bridge?.dismissOpenPopupsOrSheets()

                // Lock in context variables
                currentContextVars["target_video_id"] = extractedVideoId ?: ""
                currentContextVars["target_video_url"] = targetVideoUrl ?: ""
                currentContextVars["target_title"] = cleanTitle ?: ""
                currentContextVars["search_query"] = query
                currentContextVars["target_search_rank"] = foundRank.toString()

                Log.i(TAG, "Target video clicked and playback initiated! Exiting executeYouTubeSearchAndDiscover with SUCCESS immediately. Remaining fallback queries skipped.")
                return "SUCCESS"
            }

            // Target was NOT found in top 10 results
            Log.w(TAG, "Target video NOT found in top 10 results for query '$query'.")

            val hasMoreKeywords = qIndex < searchQueue.size - 1
            if (!hasMoreKeywords) {
                Log.w(TAG, "All search queries exhausted without finding target video in top 10. Locking in last searched query '$query'.")
                currentContextVars["target_video_id"] = extractedVideoId ?: ""
                currentContextVars["target_video_url"] = targetVideoUrl ?: ""
                currentContextVars["target_title"] = cleanTitle ?: ""
                currentContextVars["search_query"] = query
                bridge?.dismissOpenPopupsOrSheets()
                return "SUCCESS"
            }

            // Fallback keyword available! Execute user-selected search_retry_mode:
            Log.i(TAG, "Preparing fallback search retry (Strategy: $searchRetryMode) for next keyword #${qIndex + 2}...")
            if (searchRetryMode.equals("RETURN_TO_HOME", ignoreCase = true)) {
                // Provision A: Go back to YouTube home page and search using home search anchor
                Log.i(TAG, "Navigating back to YouTube home page (https://m.youtube.com/) for fresh search...")
                withContext(Dispatchers.Main) {
                    currentSession?.loadUri("https://m.youtube.com/")
                }
                delay(2500L)
            } else {
                // Provision B: CLEAR_SEARCH_BAR (default)
                // Stay on search results page:
                // 1. Scroll back smoothly to top so search button is visible
                currentSession?.loadUri("javascript:window.scrollTo({top: 0, behavior: 'smooth'});")
                delay(600L)

                // 2. Tap SEARCH_BUTTON_RESULTS (calibrated anchor for search button on results feed)
                val resultsSearchPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_BUTTON_RESULTS)
                Log.i(TAG, "Tapping calibrated SEARCH_BUTTON_RESULTS anchor at (${resultsSearchPoint.x}, ${resultsSearchPoint.y}) to open search bar...")
                controller?.tap(resultsSearchPoint)
                delay(600L)

                // 3. Clear existing query
                val clearTapPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_CLEAR)
                controller?.tap(clearTapPoint)
                delay(250L)
                currentSession?.loadUri("javascript:(function(){ try { var inp = document.querySelector('input.search-input, input[type=\"search\"], input[name=\"search_query\"], input#search'); if (inp) { inp.value = ''; inp.dispatchEvent(new Event('input', {bubbles: true})); } var clr = document.querySelector('button[aria-label*=\"Clear\" i], .searchbox-clear-button'); if (clr) clr.click(); } catch(e){} })()")
                delay(300L)
            }
        }

        Log.i(TAG, "Search queue finished. Search step locked in.")
        currentContextVars["target_video_id"] = extractedVideoId ?: ""
        currentContextVars["target_video_url"] = targetVideoUrl ?: ""
        currentContextVars["target_title"] = cleanTitle ?: ""
        return "SUCCESS"
    }

    /**
     * Dedicated Scroll to Target Video step.
     * Naturally scrolls search results, detects the targeted video,
     * scrolls past and returns back to center the targeted video in the viewport.
     * Completes cleanly without clicking.
     */
    private suspend fun executeYouTubeScrollToTarget(params: JsonObject): String {
        val bridge = snapshotBridge
        val controller = inputController
        val currentSession = session

        val targetVideoId = params.get("target_video_id")?.asString
            ?: params.get("video_id")?.asString
            ?: currentContextVars["target_video_id"] as? String
        val targetVideoUrl = params.get("target_video_url")?.asString
            ?: params.get("video_url")?.asString
            ?: currentContextVars["target_video_url"] as? String
        val targetTitle = params.get("target_title")?.asString
            ?: params.get("video_title")?.asString
            ?: params.get("title")?.asString?.takeIf { !it.contains("scroll", ignoreCase = true) }
            ?: currentContextVars["target_title"] as? String

        var extractedVideoId = targetVideoId?.ifBlank { null }
        if (extractedVideoId == null && !targetVideoUrl.isNullOrBlank()) {
            val match = Regex("(?:v=|/)([0-9A-Za-z_-]{11})(?:[?&/]|$)").find(targetVideoUrl)
            if (match != null) {
                extractedVideoId = match.groupValues[1]
            }
        }

        val targetScanQuery = extractedVideoId ?: targetVideoUrl ?: targetTitle ?: ""
        Log.i(TAG, "Starting dedicated YT_SCROLL_TO_TARGET step for target: '$targetScanQuery'")

        // Dismiss any popups or sheets that might have opened
        bridge?.dismissOpenPopupsOrSheets()
        delay(400L)

        if (targetScanQuery.isBlank()) {
            Log.w(TAG, "No target video query or ID provided for scroll step. Continuing gracefully.")
            return "SUCCESS"
        }

        val maxScrollBatches = params.get("max_scroll_batches")?.asInt ?: 10
        val scrollPastAndReturn = params.get("scroll_past_and_return")?.asBoolean ?: true

        // Ensure we start checking from top of search results
        currentSession?.loadUri("javascript:window.scrollTo({top: 0, behavior: 'instant'});")
        delay(600L)

        var spot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 3500L, targetCleanTitle = targetTitle)
        var targetFound = spot?.found == true

        if (targetFound) {
            Log.i(TAG, "Target video '$targetScanQuery' spotted at initial position (scroll batch 0)!")
        }

        var scrollBatch = 0
        while (!targetFound && scrollBatch < maxScrollBatches && isRunning) {
            scrollBatch++
            Log.i(TAG, "Target video not in current view. Natural downward scroll batch #$scrollBatch of $maxScrollBatches...")
            val scrollDist = (0.28f + Math.random().toFloat() * 0.12f)
            performNaturalScroll("DOWN", distanceFraction = scrollDist)
            delay((1200L..2000L).random())

            spot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 3000L, targetCleanTitle = targetTitle)
            targetFound = spot?.found == true
        }

        // Resilient fallback: If not found after downward scrolling, scroll smoothly back to top and re-scan
        if (!targetFound && scrollBatch > 0) {
            Log.i(TAG, "Target not found after downward batches. Scrolling smoothly back up to top to re-check...")
            currentSession?.loadUri("javascript:window.scrollTo({top: 0, behavior: 'smooth'});")
            delay(1500L)
            spot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 3500L, targetCleanTitle = targetTitle)
            targetFound = spot?.found == true
        }

        if (targetFound) {
            Log.i(TAG, "Target video spotted! Executing natural browsing: scroll past and return back to center...")

            if (scrollPastAndReturn) {
                // Naturally scroll past the target video
                val passDist = (0.22f + Math.random().toFloat() * 0.12f)
                Log.i(TAG, "Organic browsing: Scrolling past target video (dist: $passDist)...")
                performNaturalScroll("DOWN", distanceFraction = passDist)
                delay((1200L..2000L).random())

                // Naturally scroll back up to center the target video
                Log.i(TAG, "Organic browsing: Scrolling back up to center target video in viewport...")
                performNaturalScroll("UP", distanceFraction = (passDist * 1.05f).coerceAtMost(0.38f))
                delay((1000L..1600L).random())
            }

            // Check if micro-centering is needed
            val finalSpot = bridge?.scanForVideoTarget(targetScanQuery, timeoutMs = 3000L, targetCleanTitle = targetTitle)
            if (finalSpot != null && finalSpot.found && !finalSpot.isInViewport) {
                if (finalSpot.isBelowViewport) {
                    performNaturalScroll("DOWN", distanceFraction = 0.18f)
                } else if (finalSpot.isAboveViewport) {
                    performNaturalScroll("UP", distanceFraction = 0.18f)
                }
                delay(800L)
            }

            bridge?.dismissOpenPopupsOrSheets()
            Log.i(TAG, "Target video centered cleanly in viewport. Dedicated scroll step complete WITHOUT clicking.")
        } else {
            Log.w(TAG, "Target video not located after $maxScrollBatches scroll batches. Leaving viewport at current position.")
        }

        // Forward and preserve context variables for subsequent WAIT_PLAYBACK step
        currentContextVars["target_video_id"] = extractedVideoId ?: ""
        currentContextVars["target_video_url"] = targetVideoUrl ?: ""
        currentContextVars["target_title"] = targetTitle ?: ""

        delay((1000L..1800L).random())
        return "SUCCESS"
    }

    private suspend fun executeYouTubeEngagement(command: String, params: JsonObject): String {
        val controller = inputController
        val currentTargetView = targetView
        val currentSession = session
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f

        return when (command) {
            "YT_EXPAND_DESCRIPTION" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_EXPAND_DESCRIPTION")
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.DESCRIPTION_EXPAND)
                Log.i(TAG, "Tapping DESCRIPTION_EXPAND anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay((2000L..4000L).random())

                // Dismiss description sheet so that subsequent actions are unobstructed
                controller?.back()
                delay(800L)
                "SUCCESS"
            }

            "YT_LIKE_VIDEO" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_LIKE_VIDEO")
                currentSession?.loadUri("javascript:(function(){ try { var b = document.querySelector('like-button-view-model button, ytm-like-button-renderer button, button[aria-label*=\"like this video\" i], [aria-label*=\"like\" i]'); if (b) b.click(); } catch(e){} })()")
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.LIKE_BUTTON)
                Log.i(TAG, "Tapping LIKE_BUTTON anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay(1200L)
                "SUCCESS"
            }

            "YT_DISLIKE_VIDEO" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_DISLIKE_VIDEO")
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.DISLIKE_BUTTON)
                Log.i(TAG, "Tapping DISLIKE_BUTTON anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay(1200L)
                "SUCCESS"
            }

            "YT_SHARE_VIDEO" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SHARE_VIDEO")
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.SHARE_BUTTON)
                Log.i(TAG, "Tapping SHARE_BUTTON anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay(1500L)
                controller?.back()
                delay(600L)
                "SUCCESS"
            }

            "YT_SUBSCRIBE_CHANNEL" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SUBSCRIBE_CHANNEL")
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.SUBSCRIBE_BUTTON)
                Log.i(TAG, "Tapping SUBSCRIBE_BUTTON anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay(1200L)
                "SUCCESS"
            }

            "YT_DISMISS_PRE_ROLL_AD", "DISMISS_POPUP" -> {
                val mapper = coordinateMapper
                val rec = recoveryEngine
                if (mapper != null && snapshotBridge != null && rec != null) {
                    val dismissed = rec.dismissBlockingOverlay(
                        coordinateMapper = mapper,
                        getSnapshot = { snapshotBridge?.captureSnapshot() }
                    )
                    if (dismissed) "SUCCESS" else "SKIP"
                } else "SKIP"
            }

            "YT_SHORTS_SWIPE" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SHORTS_SWIPE")
                performNaturalScroll("DOWN", distanceFraction = 0.55f, customDurationMs = 450L)
                delay(800L)
                "SUCCESS"
            }

            "YT_SCRUB_TIMELINE" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SCRUB_TIMELINE")
                val playerPoint = resolveAnchorToLocalPoint(AnchorRegistry.PLAYER_PLAY_PAUSE, randomizeJitter = false)
                controller?.swipe(
                    start = ScreenPoint(width * 0.2f, playerPoint.y),
                    end = ScreenPoint(width * 0.8f, playerPoint.y),
                    durationMs = 600L
                )
                delay(1500L)
                "SUCCESS"
            }

            "YT_SCROLL_TO_COMMENTS" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SCROLL_TO_COMMENTS")
                performNaturalScroll("DOWN", distanceFraction = 0.30f)
                val tapPoint = resolveAnchorToLocalPoint(AnchorRegistry.COMMENTS_SECTION)
                Log.i(TAG, "Tapping COMMENTS_SECTION anchor at (${tapPoint.x}, ${tapPoint.y})")
                controller?.tap(tapPoint)
                delay(1500L)
                "SUCCESS"
            }

            "YT_DWELL_ON_COMMENTS" -> {
                val duration = params.get("duration_seconds")?.asInt ?: 12
                Log.i(TAG, "Executing Calibrated Action: YT_DWELL_ON_COMMENTS for ${duration}s")
                val checkInterval = 2500L
                var elapsed = 0

                while (elapsed < duration && isRunning) {
                    delay(checkInterval)
                    elapsed += (checkInterval / 1000).toInt()

                    if (Math.random() > 0.35) {
                        val dir = if (Math.random() > 0.3) "DOWN" else "UP"
                        performNaturalScroll(dir, distanceFraction = 0.25f)
                    }
                }

                // Dismiss comments sheet cleanly
                controller?.back()
                delay(800L)
                "SUCCESS"
            }

            "YT_POST_COMMENT" -> {
                val commentText = params.get("comment_text")?.asString
                    ?: params.get("text")?.asString
                    ?: params.get("comment")?.asString
                    ?: params.get("prompt")?.asString
                    ?: ""
                val wpm = params.get("wpm")?.asInt ?: 60
                val typo = params.get("typo_probability")?.asDouble ?: 0.03
                Log.i(TAG, "Executing Calibrated Action: YT_POST_COMMENT ('$commentText')")

                // 1. Scroll naturally to comments
                performNaturalScroll("DOWN", distanceFraction = 0.30f)
                delay(800L)

                if (commentText.isNotBlank()) {
                    // 2. Tap comments teaser anchor / DOM element
                    currentSession?.loadUri("javascript:(function(){ try { var t = document.querySelector('ytm-comments-entry-point-header-renderer, .comment-teaser, [aria-label*=\"comment\" i]'); if (t) t.click(); } catch(e){} })()")
                    val teaserPoint = resolveAnchorToLocalPoint(AnchorRegistry.COMMENTS_SECTION)
                    controller?.tap(teaserPoint)
                    delay(1200L)

                    // 3. Focus comment input field
                    currentSession?.loadUri("javascript:(function(){ try { var inp = document.querySelector('ytm-comment-simplebox-renderer textarea, textarea#comment-input, textarea.comment-simplebox-text, input#comment-input, [aria-label*=\"Add a comment\" i], .comment-simplebox-prompt'); if (inp) { inp.focus(); inp.click(); } } catch(e){} })()")
                    delay(600L)

                    // 4. Type comment with human cadence
                    controller?.type(commentText, wpm, typo)
                    delay(1000L)

                    // 5. Submit comment via DOM click and submit anchor
                    currentSession?.loadUri("javascript:(function(){ try { var btn = document.querySelector('button.comment-simplebox-submit, button[aria-label*=\"Comment\" i], ytm-comment-simplebox-renderer button[type=\"submit\"]'); if (btn && !btn.disabled) btn.click(); } catch(e){} })()")
                    val submitPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_SUBMIT)
                    controller?.tap(submitPoint)
                    delay(1500L)

                    // 6. Dismiss comments sheet
                    controller?.back()
                    delay(800L)
                }
                "SUCCESS"
            }

            else -> "SUCCESS"
        }
    }

    private suspend fun executeGeneralInteraction(command: String, params: JsonObject): String {
        val controller = inputController
        val currentTargetView = targetView
        val currentSession = session
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f

        return when (command) {
            "YT_TAP_SEARCH_BAR" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_TAP_SEARCH_BAR")
                val activeUrl = getCurrentUrl?.invoke() ?: ""
                val searchBtnKey = when {
                    activeUrl.contains("watch") || activeUrl.contains("shorts") -> AnchorRegistry.SEARCH_BUTTON_WATCH
                    activeUrl.contains("search_query") || activeUrl.contains("/results") -> AnchorRegistry.SEARCH_BUTTON_RESULTS
                    else -> AnchorRegistry.SEARCH_BUTTON_HOME
                }

                val buttonPoint = resolveAnchorToLocalPoint(searchBtnKey)
                Log.i(TAG, "Tapping $searchBtnKey anchor at (${buttonPoint.x}, ${buttonPoint.y})")
                controller?.tap(buttonPoint)
                delay(800L)

                val inputPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_INPUT)
                Log.i(TAG, "Tapping SEARCH_INPUT anchor at (${inputPoint.x}, ${inputPoint.y})")
                controller?.tap(inputPoint)
                delay(600L)
                "SUCCESS"
            }

            "TYPE_TEXT" -> {
                if (params.has("xpath") && params.get("xpath").asString.isNotBlank()) {
                    executeTypeText(params, submitEnter = false)
                } else {
                    val text = params.get("text")?.asString ?: ""
                    val wpm = params.get("wpm")?.asInt ?: 65
                    val typo = params.get("typo_probability")?.asDouble ?: 0.03
                    Log.i(TAG, "Executing Calibrated Action TYPE_TEXT: '$text' at $wpm WPM")

                    val inputPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_INPUT)
                    controller?.tap(inputPoint)
                    delay(400L)

                    currentSession?.loadUri("javascript:(function(){ try { var inp = document.querySelector('input.search-input, input[type=\"search\"], input[name=\"search_query\"], input#search'); if (inp) { inp.value = ''; } } catch(e){} })()")
                    delay(200L)
                    controller?.type(text, wpm, typo)
                    delay(600L)
                    "SUCCESS"
                }
            }

            "YT_SUBMIT_SEARCH", "SUBMIT_INPUT" -> {
                Log.i(TAG, "Executing Calibrated Action: YT_SUBMIT_SEARCH")
                val submitPoint = resolveAnchorToLocalPoint(AnchorRegistry.SEARCH_SUBMIT)
                controller?.tap(submitPoint)
                controller?.type("\n")
                currentSession?.loadUri("javascript:(function(){ try { var f = document.querySelector('form#search-form, form.searchbox'); if (f) f.submit(); } catch(e){} })()")
                delay(2500L)
                "SUCCESS"
            }

            "BÉZIER_SWIPE", "SWIPE_VERTICAL" -> {
                val direction = params.get("direction")?.asString ?: "DOWN"
                val duration = params.get("duration_ms")?.asLong
                performNaturalScroll(direction, distanceFraction = 0.40f, customDurationMs = duration)
                delay(600L)
                "SUCCESS"
            }

            "CLICK_LINK", "CLICK_INTERNAL_LINK" -> executeClickLink(params)

            "SUBMIT_FORM" -> {
                controller?.type("\n")
                delay(3000L)
                "SUCCESS"
            }

            else -> "SUCCESS"
        }
    }

    private suspend fun executeClickElementByXPath(params: JsonObject): String {
        val currentTargetView = targetView
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f
        val controller = inputController ?: return "FAILURE"

        // 1. Check if an explicit Spatial Anchor key is specified FIRST.
        //    If an explicit spatial anchor is specified (e.g. SEARCH_BUTTON_HOME, LIKE_BUTTON, etc.),
        //    execute that anchor click directly rather than diverting to search.
        val anchorCandidate = params.get("spatial_anchor")?.asString
            ?: params.get("anchor")?.asString
            ?: ""

        if (anchorCandidate.isNotBlank() && AnchorRegistry.isKnownAnchor(anchorCandidate)) {
            val resolvedKey = AnchorRegistry.resolveKey(anchorCandidate)
            val anchorPoint = resolveAnchorToLocalPoint(resolvedKey)
            Log.i(TAG, "CLICK_ELEMENT targeting calibrated spatial anchor $resolvedKey at (${anchorPoint.x}, ${anchorPoint.y})")
            controller.tap(anchorPoint)
            delay(1200L)
            return "SUCCESS"
        }

        // 2. Resolve XPath/selector for DOM lookup.
        //    Also allow the selector field here (it is only excluded from the anchor fast-path above).
        val selectorFallback = params.get("selector")?.asString?.takeIf { it.isNotBlank() }
        val xpath = params.get("xpath")?.asString?.takeIf { it.isNotBlank() }
            ?: selectorFallback
            ?: return "FAILURE"

        // 3. If the xpath string itself happens to be a known spatial anchor alias, use it.
        //    This handles shorthand values like xpath="LIKE_BUTTON".
        if (AnchorRegistry.isKnownAnchor(xpath)) {
            val resolvedFromXpath = AnchorRegistry.resolveKey(xpath)
            val anchorPoint = resolveAnchorToLocalPoint(resolvedFromXpath)
            Log.i(TAG, "CLICK_ELEMENT XPath value is a known anchor alias $resolvedFromXpath at (${anchorPoint.x}, ${anchorPoint.y})")
            controller.tap(anchorPoint)
            delay(1200L)
            return "SUCCESS"
        }

        val bridge = snapshotBridge ?: return "FAILURE"
        val mapper = coordinateMapper ?: return "FAILURE"

        Log.i(TAG, "Executing deterministic CLICK_ELEMENT with DOM XPath: '$xpath'")

        for (attempt in 1..3) {
            val spot = bridge.locateElementByXPath(xpath, performAction = "click", timeoutMs = 2500L)
            if (spot != null && spot.found && spot.rect != null) {
                val screenRect = mapper.mapDomToScreen(spot.rect, spot.dpr)
                val tapPoint = mapper.getOrganicTapPoint(screenRect)
                Log.i(TAG, "XPath element found at CSS (${spot.rect.left}, ${spot.rect.top}), screen point (${tapPoint.x}, ${tapPoint.y}). Tapping natively.")
                controller.tap(tapPoint)
                delay(1200L)
                return "SUCCESS"
            }
            if (attempt < 3) {
                Log.w(TAG, "XPath '$xpath' not found on attempt $attempt, scrolling gently down...")
                performNaturalScroll("DOWN", distanceFraction = 0.25f)
                delay(1500L)
            }
        }

        Log.e(TAG, "Failed to locate XPath element '$xpath' after 3 attempts. Aborting step without false clicks.")
        return "FAILURE"
    }

    private suspend fun executeClickLink(params: JsonObject): String {
        val targetUrl = params.get("target_url")?.asString
            ?: params.get("url")?.asString
            ?: params.get("url_pattern")?.asString
            ?: ""
        val matchType = params.get("match_type")?.asString ?: "contains"

        if (targetUrl.isBlank()) {
            Log.e(TAG, "CLICK_LINK called with blank target URL pattern")
            return "FAILURE"
        }

        val bridge = snapshotBridge ?: return "FAILURE"
        val mapper = coordinateMapper ?: return "FAILURE"
        val controller = inputController ?: return "FAILURE"

        Log.i(TAG, "Executing deterministic CLICK_LINK with pattern: '$targetUrl' (mode: $matchType)")

        for (attempt in 1..3) {
            val spot = bridge.locateLink(targetUrl, matchType = matchType, performAction = "click", timeoutMs = 2500L)
            if (spot != null && spot.found && spot.rect != null) {
                val screenRect = mapper.mapDomToScreen(spot.rect, spot.dpr)
                val tapPoint = mapper.getOrganicTapPoint(screenRect)
                Log.i(TAG, "Matching link found at CSS (${spot.rect.left}, ${spot.rect.top}), screen point (${tapPoint.x}, ${tapPoint.y}). Tapping natively.")
                controller.tap(tapPoint)
                delay(2000L)
                return "SUCCESS"
            }
            if (attempt < 3) {
                Log.w(TAG, "Link '$targetUrl' not found on attempt $attempt, scrolling gently down...")
                performNaturalScroll("DOWN", distanceFraction = 0.30f)
                delay(1500L)
            }
        }

        Log.e(TAG, "Failed to locate link matching '$targetUrl' after 3 attempts. Aborting step without false clicks.")
        return "FAILURE"
    }

    private suspend fun executeTypeText(params: JsonObject, submitEnter: Boolean = false): String {
        val xpath = params.get("xpath")?.asString?.takeIf { it.isNotBlank() }
        val text = params.get("text")?.asString ?: ""
        val wpm = params.get("wpm")?.asInt ?: 65
        val typo = params.get("typo_probability")?.asDouble ?: 0.02
        val controller = inputController ?: return "FAILURE"
        val mapper = coordinateMapper
        val bridge = snapshotBridge
        val currentSession = session

        Log.i(TAG, "Executing deterministic TYPE_TEXT: '$text' (enter=$submitEnter, xpath=$xpath)")

        // Only explicit spatial_anchor / anchor params trigger the spatial-anchor fast path.
        // If xpath holds an XPath expression, it must NOT be used as an anchor key.
        val anchorKey = params.get("spatial_anchor")?.asString
            ?: params.get("anchor")?.asString
            ?: ""

        if (anchorKey.isNotBlank() && AnchorRegistry.isKnownAnchor(anchorKey)) {
            val currentTargetView = targetView
            val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
            val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f
            val resolvedAnchor = AnchorRegistry.resolveKey(anchorKey)
            val anchorPoint = AnchorRegistry.getScreenPoint(resolvedAnchor, width, height)
            Log.i(TAG, "TYPE_TEXT tapping calibrated spatial anchor $resolvedAnchor at (${anchorPoint.x}, ${anchorPoint.y})")
            controller.tap(anchorPoint)
            delay(500L)
        } else if (xpath != null && bridge != null && mapper != null) {
            val spot = bridge.locateElementByXPath(xpath, performAction = "focus", timeoutMs = 2000L)
            if (spot != null && spot.found && spot.rect != null) {
                val screenRect = mapper.mapDomToScreen(spot.rect, spot.dpr)
                val tapPoint = mapper.getOrganicTapPoint(screenRect)
                controller.tap(tapPoint)
                delay(500L)
            }
        } else {
            val currentTargetView = targetView
            val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
            val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f
            val inputPoint = AnchorRegistry.getScreenPoint(AnchorRegistry.SEARCH_INPUT, width, height)
            controller.tap(inputPoint)
            delay(500L)
        }

        val clearFirst = params.get("clear_before_type")?.asBoolean ?: true
        if (clearFirst && xpath != null) {
            val encodedXp = java.net.URLEncoder.encode(xpath, "UTF-8")
            currentSession?.loadUri("javascript:(function(){ try { var el = document.evaluate(decodeURIComponent('$encodedXp'), document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; if (el) { el.value = ''; } } catch(e){} })()")
            delay(200L)
        }

        controller.type(text, wpm, typo)
        delay(600L)

        if (submitEnter) {
            Log.i(TAG, "Pressing Enter after typing text")
            controller.type("\n")
            if (xpath != null) {
                val encodedXp = java.net.URLEncoder.encode(xpath, "UTF-8")
                currentSession?.loadUri("javascript:(function(){ try { var el = document.evaluate(decodeURIComponent('$encodedXp'), document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; if (el) { var form = el.form || el.closest('form'); if (form) form.submit(); } } catch(e){} })()")
            }
            delay(2000L)
        }

        return "SUCCESS"
    }

    private suspend fun executeClickAdIframe(params: JsonObject): String {
        val iframeSel = params.get("iframe_xpath")?.asString
            ?: params.get("iframe_selector")?.asString
            ?: "googleads"
        val linkSel = params.get("link_xpath")?.asString
            ?: params.get("link_selector")?.asString

        val bridge = snapshotBridge ?: return "FAILURE"
        val mapper = coordinateMapper ?: return "FAILURE"
        val controller = inputController ?: return "FAILURE"

        Log.i(TAG, "Executing deterministic CLICK_AD_IFRAME (iframe: '$iframeSel', link: '$linkSel')")

        val spot = bridge.locateAdIframe(iframeSel, linkSel, performAction = "click", timeoutMs = 2500L)
        if (spot != null && spot.found && spot.rect != null) {
            val screenRect = mapper.mapDomToScreen(spot.rect, spot.dpr)
            val tapPoint = mapper.getOrganicTapPoint(screenRect)
            Log.i(TAG, "Ad/iFrame located at (${tapPoint.x}, ${tapPoint.y}). Tapping natively.")
            controller.tap(tapPoint)
            delay(2500L)
            return "SUCCESS"
        }

        Log.w(TAG, "Ad/iFrame '$iframeSel' not found on page. Skipping gracefully.")
        return "SUCCESS"
    }

    private suspend fun executeAiType(params: JsonObject): String {
        val prompt = params.get("prompt")?.asString ?: "Great content, thanks for sharing!"
        val xpath = params.get("xpath")?.asString ?: "//input[@name='comment_text']"

        val typeParams = JsonObject().apply {
            addProperty("xpath", xpath)
            addProperty("text", prompt)
            addProperty("wpm", 55)
        }
        return executeTypeText(typeParams, submitEnter = false)
    }

    private suspend fun executeGroundedClick(
        spec: TargetSpec,
        params: JsonObject,
        expectedState: String? = null
    ): String {
        val currentTargetView = targetView
        val width = currentTargetView?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
        val height = currentTargetView?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f
        val controller = inputController ?: return "FAILURE"

        // First check: does target match a calibrated spatial anchor in our model?
        val targetKeyCandidate = spec.actionType ?: spec.role ?: spec.ariaLabel ?: spec.textSnippet ?: params.get("anchor")?.asString ?: ""
        val resolvedAnchor = AnchorRegistry.resolveKey(targetKeyCandidate)
        if (resolvedAnchor != targetKeyCandidate.trim().uppercase()) {
            val anchorPoint = AnchorRegistry.getScreenPoint(resolvedAnchor, width, height)
            Log.i(TAG, "Grounded click directly matched calibrated spatial anchor $resolvedAnchor: (${anchorPoint.x}, ${anchorPoint.y})")
            controller.tap(anchorPoint)
            delay(1000L)
            return "SUCCESS"
        }

        val bridge = snapshotBridge ?: return "FAILURE"
        val mapper = coordinateMapper ?: return "FAILURE"
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
            ariaLabel = params.get("aria_label")?.asString,
            actionType = params.get("action_type")?.asString,
            hrefContains = params.get("href_contains")?.asString
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
        val gView = targetView as? GeckoView

        Log.i(TAG, "Requesting Tier-3 AI/VLM Recovery Decision for Job: $jobId")

        val snapshot = bridge.captureSnapshot()
        val imageBase64 = ScreenCaptureBridge.captureBase64(gView)

        val payload = mutableMapOf<String, Any>()
        if (snapshot != null) {
            payload["page_snapshot"] = gson.toJsonTree(snapshot)
        }
        if (imageBase64 != null) {
            payload["image_base64"] = imageBase64
        }

        return try {
            val resp = api.requestDecision(jobId, payload)

            // Confidence check to prevent acting on low-confidence AI predictions
            val confidence = resp.get("confidence")?.asFloat ?: 1.0f
            if (confidence < 0.7f) {
                Log.w(TAG, "AI recovery decision rejected: confidence ($confidence) is below threshold 0.7")
                return "FAILURE"
            }

            val action = resp.get("action")?.asString ?: "BÉZIER_SWIPE"
            val view = targetView
            val width = view?.width?.toFloat()?.takeIf { it > 10f } ?: 1080f
            val height = view?.height?.toFloat()?.takeIf { it > 10f } ?: 2400f

            if (action == "TAP_COORDINATES") {
                val normX = resp.get("target_x")?.asFloat ?: 500f
                val normY = resp.get("target_y")?.asFloat ?: 500f

                val screenX = (normX / 1000f) * width
                val screenY = (normY / 1000f) * height

                if (screenX in 0f..width && screenY in 0f..height) {
                    Log.i(TAG, "Executing VLM TAP_COORDINATES at ($screenX, $screenY) [Norm: $normX, $normY]")
                    controller.tap(ScreenPoint(screenX, screenY))
                } else {
                    Log.w(TAG, "VLM coordinates ($screenX, $screenY) out of bounds ($width x $height). Falling back to swipe.")
                    controller.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.70f),
                        end = ScreenPoint(width * 0.5f, height * 0.35f),
                        durationMs = 600L
                    )
                }
            } else if (action == "BÉZIER_SWIPE") {
                val dir = resp.get("swipe_direction")?.asString ?: "DOWN"
                if (dir == "UP") {
                    controller.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.75f),
                        end = ScreenPoint(width * 0.5f, height * 0.35f),
                        durationMs = 600L
                    )
                } else {
                    controller.swipe(
                        start = ScreenPoint(width * 0.5f, height * 0.35f),
                        end = ScreenPoint(width * 0.5f, height * 0.75f),
                        durationMs = 600L
                    )
                }
            } else if (action == "NAVIGATE") {
                val navUrl = resp.get("navigate_url")?.asString
                if (!navUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { session?.loadUri(navUrl) }
                    delay(3000L)
                }
            } else if (action == "WAIT") {
                val waitSec = resp.get("wait_seconds")?.asInt ?: 5
                delay(waitSec * 1000L)
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
                        "device_id" to getResolvedDeviceId(),
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
