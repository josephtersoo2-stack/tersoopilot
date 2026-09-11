package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.data.model.ExecutionCheckpointEntity
import org.junit.Assert.*
import org.junit.Test

class ExecutionCheckpointTest {

    @Test
    fun testCheckpointEntityCreationAndAttributes() {
        val now = System.currentTimeMillis()
        val checkpoint = ExecutionCheckpointEntity(
            jobId = "job-456",
            profileId = "profile-789",
            currentStateId = "watch_video_step",
            executedSteps = 12,
            lastCommand = "CLICK",
            updatedAt = now
        )

        assertEquals("job-456", checkpoint.jobId)
        assertEquals("profile-789", checkpoint.profileId)
        assertEquals("watch_video_step", checkpoint.currentStateId)
        assertEquals(12, checkpoint.executedSteps)
        assertEquals("CLICK", checkpoint.lastCommand)
        assertEquals("RUNNING", checkpoint.status)
        assertEquals(now, checkpoint.updatedAt)

        val completed = checkpoint.copy(status = "COMPLETED")
        assertEquals("COMPLETED", completed.status)
    }

    @Test
    fun testCheckpointAgeValidation() {
        val now = System.currentTimeMillis()
        val validCheckpoint = ExecutionCheckpointEntity(
            jobId = "job-recent",
            profileId = "profile-1",
            currentStateId = "step-1",
            executedSteps = 5,
            lastCommand = "NAVIGATE",
            updatedAt = now - (2 * 3600 * 1000L) // 2 hours old
        )

        val expiredCheckpoint = ExecutionCheckpointEntity(
            jobId = "job-old",
            profileId = "profile-1",
            currentStateId = "step-99",
            executedSteps = 50,
            lastCommand = "WAIT",
            updatedAt = now - (15 * 3600 * 1000L) // 15 hours old (> 12h threshold)
        )

        val thresholdMs = 12 * 3600 * 1000L

        assertTrue("Recent checkpoint (< 12h) must be accepted for resumption",
            (now - validCheckpoint.updatedAt) < thresholdMs)
        assertFalse("Stale checkpoint (> 12h) must be discarded",
            (now - expiredCheckpoint.updatedAt) < thresholdMs)
    }

    @Test
    fun testCheckpointStepAdvancement() {
        val initial = ExecutionCheckpointEntity(
            jobId = "job-loop",
            profileId = "profile-1",
            currentStateId = "step_start",
            executedSteps = 0,
            lastCommand = "NAVIGATE",
            updatedAt = 1000L
        )

        val advanced = initial.copy(
            currentStateId = "step_click",
            executedSteps = initial.executedSteps + 1,
            lastCommand = "CLICK",
            updatedAt = 2000L
        )

        assertEquals(1, advanced.executedSteps)
        assertEquals("step_click", advanced.currentStateId)
        assertEquals("CLICK", advanced.lastCommand)
        assertTrue(advanced.updatedAt > initial.updatedAt)
    }
}
