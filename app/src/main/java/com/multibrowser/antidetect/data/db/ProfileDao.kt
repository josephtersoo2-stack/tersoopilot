package com.multibrowser.antidetect.data.db

import com.multibrowser.antidetect.data.model.ProfileEntity
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

    // Persistent Tab Restoration
    suspend fun getTabsForProfile(profileId: String): List<SavedTabEntity>
    suspend fun saveTabsForProfile(profileId: String, tabs: List<SavedTabEntity>)
    suspend fun clearTabsForProfile(profileId: String)
}
