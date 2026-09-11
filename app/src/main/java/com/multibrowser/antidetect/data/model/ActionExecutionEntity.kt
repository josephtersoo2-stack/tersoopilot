package com.multibrowser.antidetect.data.model

/**
 * Represents a physical or closed-loop command action execution record.
 * Used to ensure strict idempotency across network retries, transient disconnections,
 * and state transition re-evaluations so physical gestures are never duplicated.
 */
data class ActionExecutionEntity(
    val actionId: String, // Deterministic ID: "$jobId:$stateId:$executedSteps"
    val jobId: String,
    val stateId: String,
    val command: String,
    val outcome: String,
    val executedAt: Long = System.currentTimeMillis()
)
