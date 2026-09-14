package com.multibrowser.antidetect.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.multibrowser.antidetect.MainActivity
import com.multibrowser.antidetect.network.GhostPilotApiService
import com.multibrowser.antidetect.network.RetrofitInstance
import kotlinx.coroutines.*

/**
 * TersoPilot Automation Worker Foreground Service.
 *
 * Implements Section 20 of the TersoPilot Automation V2 Specification:
 * - Supervises mobile worker lifecycle.
 * - Polls and claims work from server via POST /api/automation/ghostpilot/claim-next/.
 * - Coordinates DAG execution with GhostPilotRunner.
 * - Manages partial WakeLock safely with thermal/battery safeguards.
 * - Sends periodic device telemetry and execution heartbeats.
 * - Handles process restart, reconnect, and resumption seamlessly.
 */
class AutomationWorkerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private var isPaused = false

    private val api: GhostPilotApiService by lazy {
        RetrofitInstance.retrofit.create(GhostPilotApiService::class.java)
    }

    private val sessionManager by lazy {
        WorkerProfileSessionManager(this@AutomationWorkerService)
    }

    private var activeJobId: String? = null
    private var activeProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupWakeLock()
        Log.i(TAG, "AutomationWorkerService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startWorker()
            ACTION_STOP -> stopWorker()
            ACTION_PAUSE -> pauseWorker()
            ACTION_RESUME -> resumeWorker()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorker() {
        if (isRunning) return
        isRunning = true
        isPaused = false

        startForeground(NOTIFICATION_ID, buildNotification("Worker active. Ready for work."))
        Log.i(TAG, "Starting Automation Worker loop.")

        workerJob = serviceScope.launch {
            registerDeviceWithControlPlane()
            startDeviceHeartbeatLoop()
            claimAndExecuteLoop()
        }
    }

    private fun stopWorker() {
        Log.i(TAG, "Stopping Automation Worker Service.")
        isRunning = false
        isPaused = false
        workerJob?.cancel()
        heartbeatJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseWorker() {
        Log.i(TAG, "Pausing Automation Worker.")
        isPaused = true
        releaseWakeLock()
        updateNotification("Worker paused by operator.")
    }

    private fun resumeWorker() {
        Log.i(TAG, "Resuming Automation Worker.")
        isPaused = false
        updateNotification("Worker resumed. Ready for work.")
    }

    private suspend fun registerDeviceWithControlPlane() {
        try {
            val deviceId = resolveWorkerDeviceId()
            val metrics = resources.displayMetrics
            val payload = mapOf(
                "device_id" to deviceId,
                "model_name" to Build.MODEL,
                "brand" to Build.BRAND,
                "model_code" to Build.DEVICE,
                "android_version" to Build.VERSION.SDK_INT,
                "app_version" to "1.0.0",
                "geckoview_version" to "135.0",
                "screen_width" to metrics.widthPixels,
                "screen_height" to metrics.heightPixels,
                "dpr" to metrics.density.toDouble(),
                "battery_percent" to getBatteryLevel(),
                "capabilities" to listOf("geckoview", "touch", "dom_perception", "screenshot")
            )
            api.registerDevice(payload)
            Log.i(TAG, "Registered device $deviceId with control plane.")
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}. Continuing with claim loop.")
        }
    }

    private fun startDeviceHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isRunning) {
                try {
                    val status = if (activeJobId != null) "BUSY" else "ONLINE"
                    val payload = mapOf(
                        "device_id" to resolveWorkerDeviceId(),
                        "battery_percent" to getBatteryLevel(),
                        "status" to status,
                        "current_execution" to (activeJobId ?: "")
                    )
                    api.sendDeviceHeartbeat(payload)
                } catch (e: Exception) {
                    Log.d(TAG, "Device heartbeat error: ${e.message}")
                }
                delay(30_000L)
            }
        }
    }

    private suspend fun claimAndExecuteLoop() {
        val deviceId = resolveWorkerDeviceId()

        while (isRunning) {
            if (isPaused) {
                delay(5_000L)
                continue
            }

            // Battery threshold check: if battery < 15% and discharging, pause claiming work
            val batteryLevel = getBatteryLevel()
            val isCharging = isDeviceCharging()
            if (batteryLevel < 15 && !isCharging) {
                Log.w(TAG, "Low battery ($batteryLevel%) and not charging. Throttling worker.")
                updateNotification("Paused: Low battery ($batteryLevel%). Waiting for charge.")
                releaseWakeLock()
                delay(60_000L)
                continue
            }

            try {
                updateNotification("Polling control plane for eligible jobs...")
                val claimResp = api.claimNext(mapOf("device_id" to deviceId))

                val hasWork = claimResp.get("has_work")?.asBoolean ?: false
                if (hasWork && claimResp.has("job") && !claimResp.get("job").isJsonNull) {
                    val job = claimResp.getAsJsonObject("job")
                    val execution = try {
                        ClaimedExecution.fromJsonObject(job)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse claimed execution envelope: ${e.message}")
                        null
                    }

                    if (execution != null) {
                        activeJobId = execution.executionId
                        activeProfileName = execution.profileName
                        acquireWakeLock()
                        updateNotification("Running: ${execution.profileName} [${execution.executionId.take(8)}]")
                        Log.i(TAG, "Claimed execution ${execution.executionId} on profile ${execution.profileName}. Preparing session...")

                        var workerContext: WorkerExecutionContext? = null
                        try {
                            workerContext = sessionManager.prepare(
                                profileId = execution.profileId,
                                profileName = execution.profileName,
                                deviceId = deviceId
                            )
                            updateNotification("Executing DAG on ${execution.profileName}...")
                            val result = workerContext.runner.execute(execution)
                            Log.i(TAG, "Execution ${execution.executionId} finished. Success: ${result.success}, State: ${result.terminalState}, Steps: ${result.stepsExecuted}")
                            updateNotification("Completed ${execution.profileName} (Status: ${result.terminalState})")
                        } catch (e: CancellationException) {
                            Log.i(TAG, "Execution cancelled for ${execution.executionId}")
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Execution failed for ${execution.executionId}: ${e.message}", e)
                            updateNotification("Execution failed: ${e.message?.take(30)}")
                            try {
                                api.transitionState(
                                    execution.executionId,
                                    mapOf(
                                        "transition_id" to java.util.UUID.randomUUID().toString(),
                                        "outcome" to "FAILURE",
                                        "error" to (e.message ?: "Worker execution failure")
                                    )
                                )
                            } catch (ignored: Exception) {}
                        } finally {
                            if (workerContext != null) {
                                sessionManager.cleanup(workerContext)
                            }
                            activeJobId = null
                            activeProfileName = null
                            releaseWakeLock()
                        }
                    }
                } else {
                    updateNotification("Online - Idle. Waiting for scheduled automations.")
                    delay(10_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in claim loop: ${e.message}. Backing off 15s.")
                updateNotification("Connection issue. Retrying in 15s...")
                delay(15_000L)
            }
        }
    }

    private fun resolveWorkerDeviceId(): String {
        val prefs = getSharedPreferences("terso_device_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id.isNullOrBlank()) {
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "android_$androidId"
            } else {
                "android_${java.util.UUID.randomUUID().toString().take(12)}"
            }
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getBatteryLevel(): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun isDeviceCharging(): Boolean {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TersoPilot:AutomationWorkerWakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min safety timeout
                Log.d(TAG, "WakeLock acquired.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TersoPilot Automation Worker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TersoPilot automation worker responsive for scheduled executions"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TersoPilot Automation Worker")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWorker()
        serviceScope.cancel()
        Log.i(TAG, "AutomationWorkerService destroyed.")
    }

    companion object {
        private const val TAG = "AutomationWorkerService"
        const val CHANNEL_ID = "terso_automation_worker_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.multibrowser.antidetect.action.START_WORKER"
        const val ACTION_STOP = "com.multibrowser.antidetect.action.STOP_WORKER"
        const val ACTION_PAUSE = "com.multibrowser.antidetect.action.PAUSE_WORKER"
        const val ACTION_RESUME = "com.multibrowser.antidetect.action.RESUME_WORKER"

        fun start(context: Context) {
            val intent = Intent(context, AutomationWorkerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutomationWorkerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
