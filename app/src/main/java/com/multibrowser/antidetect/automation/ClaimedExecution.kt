package com.multibrowser.antidetect.automation

import com.google.gson.JsonObject

/**
 * Strongly-typed envelope for an execution claimed by AutomationWorkerService.
 * Enforces schema validation before passing to GhostPilotRunner.
 */
data class ClaimedExecution(
    val executionId: String,
    val taskId: String,
    val profileId: String,
    val profileName: String,
    val planId: String?,
    val planVersion: String,
    val compiledDag: JsonObject,
    val entryState: String,
    val leaseId: String,
    val leaseExpiresAt: String?,
    val checkpointVersion: Int,
    val lastConfirmedState: String?,
    val lastConfirmedStep: Int?,
    val lastTransitionId: String?,
    val recoveryStatus: String?,
    val executionContext: Map<String, Any?>
) {
    companion object {
        fun fromJsonObject(job: JsonObject): ClaimedExecution {
            val executionId = job.get("execution_id")?.asString
                ?: job.get("id")?.asString
                ?: throw IllegalArgumentException("Missing execution_id in job payload")
            val taskId = job.get("task_id")?.asString ?: ""
            val profileId = job.get("profile_id")?.asString
                ?: throw IllegalArgumentException("Missing profile_id in job payload")
            val profileName = job.get("profile_name")?.asString ?: "Profile $profileId"
            val planId = job.get("plan_id")?.asString
            val planVersion = job.get("plan_version")?.asString ?: "1"
            val compiledDag = job.getAsJsonObject("compiled_dag")
                ?: job.getAsJsonObject("dag")
                ?: throw IllegalArgumentException("Missing compiled_dag in job payload")
            val entryState = job.get("entry_state")?.asString
                ?: compiledDag.get("entry_state")?.asString
                ?: "init"
            val leaseId = job.get("lease_id")?.asString ?: ""
            val leaseExpiresAt = job.get("lease_expires_at")?.asString
            val checkpointVersion = job.get("checkpoint_version")?.asInt ?: 0
            val lastConfirmedState = job.get("last_confirmed_state")?.asString
            val lastConfirmedStep = job.get("last_confirmed_step")?.asInt
            val lastTransitionId = job.get("last_transition_id")?.asString
            val recoveryStatus = job.get("recovery_status")?.asString

            val contextMap = mutableMapOf<String, Any?>()
            job.getAsJsonObject("execution_context")?.entrySet()?.forEach { (k, v) ->
                contextMap[k] = if (v.isJsonPrimitive) {
                    val p = v.asJsonPrimitive
                    if (p.isBoolean) p.asBoolean
                    else if (p.isNumber) p.asNumber
                    else p.asString
                } else {
                    v.toString()
                }
            }

            return ClaimedExecution(
                executionId = executionId,
                taskId = taskId,
                profileId = profileId,
                profileName = profileName,
                planId = planId,
                planVersion = planVersion,
                compiledDag = compiledDag,
                entryState = entryState,
                leaseId = leaseId,
                leaseExpiresAt = leaseExpiresAt,
                checkpointVersion = checkpointVersion,
                lastConfirmedState = lastConfirmedState,
                lastConfirmedStep = lastConfirmedStep,
                lastTransitionId = lastTransitionId,
                recoveryStatus = recoveryStatus,
                executionContext = contextMap
            )
        }
    }
}
