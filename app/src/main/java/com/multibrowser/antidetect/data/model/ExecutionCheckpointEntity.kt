package com.multibrowser.antidetect.data.model

/**
 * Represents a durable automation execution checkpoint saved across
 * process death, device restarts, and transient network disconnections.
 */
data class ExecutionCheckpointEntity(
    val jobId: String,
    val profileId: String,
    val currentStateId: String,
    val executedSteps: Int,
    val lastCommand: String = "",
    val status: String = "RUNNING",
    val updatedAt: Long = System.currentTimeMillis()
)
