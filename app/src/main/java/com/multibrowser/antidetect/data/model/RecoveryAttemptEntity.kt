package com.multibrowser.antidetect.data.model

/**
 * Tracks persistent recovery attempts per step in an automation job,
 * preventing infinite recovery/retry loops across process lifetimes.
 */
data class RecoveryAttemptEntity(
    val jobId: String,
    val failedStep: String,
    val reason: String = "",
    val attemptCount: Int = 1,
    val lastAttempt: Long = System.currentTimeMillis()
)
