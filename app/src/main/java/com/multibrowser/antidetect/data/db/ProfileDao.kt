package com.multibrowser.antidetect.data.db

import com.multibrowser.antidetect.data.model.ActionExecutionEntity
import com.multibrowser.antidetect.data.model.ExecutionCheckpointEntity
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.data.model.RecoveryAttemptEntity
import com.multibrowser.antidetect.data.model.SavedTabEntity
import kotlinx.coroutines.flow.Flow

interface ProfileDao {
    fun getAllProfiles(): Flow<List<ProfileEntity>>
    suspend fun getProfileById(id: String): ProfileEntity?
    suspend fun insertProfile(profile: ProfileEntity)
    suspend fun deleteProfile(profile: ProfileEntity)
    suspend fun updateLastUsed(id: String, timestamp: Long)
    suspend fun updateCookieCount(id: String, count: Int)

    // Session auto-saving & Cloud sync
    suspend fun updateSessionData(
        id: String,
        cookiesJson: String,
        historyJson: String,
        tabsJson: String,
        cookieCount: Int
    )
    suspend fun updateCloudSync(id: String, cloudSyncId: String, lastSyncedAt: Long)
    suspend fun insertOrUpdateProfile(profile: ProfileEntity)

    // Optimistic concurrency & versioned sync
    suspend fun updateProfileWithVersion(profile: ProfileEntity, expectedVersion: Int): Boolean

    // Persistent Tab Restoration
    suspend fun getTabsForProfile(profileId: String): List<SavedTabEntity>
    suspend fun saveTabsForProfile(profileId: String, tabs: List<SavedTabEntity>)
    suspend fun clearTabsForProfile(profileId: String)

    // Durable Automation Execution Checkpoints
    suspend fun saveCheckpoint(checkpoint: ExecutionCheckpointEntity)
    suspend fun getCheckpoint(jobId: String): ExecutionCheckpointEntity?
    suspend fun getLatestCheckpointForProfile(profileId: String): ExecutionCheckpointEntity?
    suspend fun findActiveCheckpoint(profileId: String): ExecutionCheckpointEntity?
    suspend fun updateCheckpointStatus(jobId: String, status: String)
    suspend fun clearCheckpoint(jobId: String)

    // Idempotent Physical Action Tracking
    suspend fun getActionOutcome(actionId: String): String?
    suspend fun recordAction(action: ActionExecutionEntity)
    suspend fun clearJobActions(jobId: String)

    // Persistent Recovery Attempts
    suspend fun recordRecoveryAttempt(jobId: String, failedStep: String, reason: String): Int
    suspend fun getRecoveryAttemptCount(jobId: String, failedStep: String): Int
    suspend fun clearRecoveryAttempts(jobId: String)
}
