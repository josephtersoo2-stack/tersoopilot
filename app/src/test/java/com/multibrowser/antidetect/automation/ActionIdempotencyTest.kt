package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.data.model.ActionExecutionEntity
import org.junit.Assert.*
import org.junit.Test

class ActionIdempotencyTest {

    @Test
    fun testDeterministicActionIdGeneration() {
        val jobId = "job_yt_987"
        val stateId = "click_like_button"
        val executedSteps = 4

        val actionId = "$jobId:$stateId:$executedSteps"
        assertEquals("job_yt_987:click_like_button:4", actionId)

        val entity = ActionExecutionEntity(
            actionId = actionId,
            jobId = jobId,
            stateId = stateId,
            command = "YT_LIKE_VIDEO",
            outcome = "SUCCESS",
            executedAt = 1700000000000L
        )

        assertEquals("job_yt_987:click_like_button:4", entity.actionId)
        assertEquals("job_yt_987", entity.jobId)
        assertEquals("click_like_button", entity.stateId)
        assertEquals("YT_LIKE_VIDEO", entity.command)
        assertEquals("SUCCESS", entity.outcome)
        assertEquals(1700000000000L, entity.executedAt)
    }

    @Test
    fun testIdempotencyCachePreventsDuplicateExecution() {
        val simulatedDb = mutableMapOf<String, ActionExecutionEntity>()

        val jobId = "job_session_1"
        val stateId = "submit_form"
        val step = 2
        val actionId = "$jobId:$stateId:$step"

        var physicalActionsPerformed = 0

        fun executeWithIdempotencyGuard(cmd: String): String {
            val cached = simulatedDb[actionId]
            if (cached != null) {
                return cached.outcome
            }

            // Perform physical action
            physicalActionsPerformed++
            val outcome = "SUCCESS"

            simulatedDb[actionId] = ActionExecutionEntity(
                actionId = actionId,
                jobId = jobId,
                stateId = stateId,
                command = cmd,
                outcome = outcome
            )
            return outcome
        }

        // First attempt (e.g. before network drop)
        val res1 = executeWithIdempotencyGuard("SUBMIT_INPUT")
        assertEquals("SUCCESS", res1)
        assertEquals(1, physicalActionsPerformed)

        // Second attempt (simulating network failure retry of the same transition)
        val res2 = executeWithIdempotencyGuard("SUBMIT_INPUT")
        assertEquals("SUCCESS", res2)
        // Physical action must NOT have been called again!
        assertEquals("Physical action must be idempotent and executed exactly once", 1, physicalActionsPerformed)
    }

    @Test
    fun testClearJobActionsOnFinalization() {
        val simulatedDb = mutableMapOf<String, ActionExecutionEntity>()
        val jobId = "job_finished"

        simulatedDb["$jobId:step1:0"] = ActionExecutionEntity("$jobId:step1:0", jobId, "step1", "NAVIGATE", "SUCCESS")
        simulatedDb["$jobId:step2:1"] = ActionExecutionEntity("$jobId:step2:1", jobId, "step2", "CLICK", "SUCCESS")
        simulatedDb["other_job:step1:0"] = ActionExecutionEntity("other_job:step1:0", "other_job", "step1", "NAVIGATE", "SUCCESS")

        assertEquals(3, simulatedDb.size)

        // Clear actions for completed job
        simulatedDb.entries.removeIf { it.value.jobId == jobId }

        assertEquals(1, simulatedDb.size)
        assertTrue(simulatedDb.containsKey("other_job:step1:0"))
        assertFalse(simulatedDb.containsKey("$jobId:step1:0"))
    }
}
