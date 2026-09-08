package com.multibrowser.antidetect.sync

import android.content.Context
import android.util.Log
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.AuthManager
import com.multibrowser.antidetect.network.AutoSaveSessionRequest
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.network.SyncPushRequest
import com.multibrowser.antidetect.network.toCloudDto
import com.multibrowser.antidetect.network.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SyncManager {
    private const val TAG = "SyncManager"
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var autoSaveDebounceJob: Job? = null

    /**
     * Pushes a single profile to the backend cloud immediately.
     */
    suspend fun pushProfileToCloud(context: Context, profile: ProfileEntity): Result<String> = withContext(Dispatchers.IO) {
        if (!AuthManager.isLoggedIn.value) {
            return@withContext Result.failure(Exception("Not logged in. Please sign in to sync profiles."))
        }

        try {
            val dto = profile.toCloudDto()
            val response = RetrofitInstance.api.pushProfiles(SyncPushRequest(listOf(dto)))
            if (response.status == "success" && response.profiles.isNotEmpty()) {
                val cloudProfile = response.profiles.first()
                val db = AppDatabase.getDatabase(context)
                cloudProfile.id?.let { cloudId ->
                    db.dao.updateCloudSync(profile.id, cloudId, System.currentTimeMillis())
                }
                Result.success("Profile '${profile.name}' synced to cloud successfully.")
            } else {
                Result.failure(Exception(response.message ?: "Failed to sync profile."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profile to cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Pushes all local profiles to cloud.
     */
    suspend fun pushAllProfiles(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!AuthManager.isLoggedIn.value) {
            return@withContext Result.failure(Exception("Not logged in. Please sign in to sync."))
        }

        try {
            val db = AppDatabase.getDatabase(context)
            val allProfiles = db.dao.getAllProfiles().first()
            if (allProfiles.isEmpty()) {
                return@withContext Result.success(0)
            }

            val dtos = allProfiles.map { it.toCloudDto() }
            val response = RetrofitInstance.api.pushProfiles(SyncPushRequest(dtos))
            if (response.status == "success") {
                for (cp in response.profiles) {
                    val matchingLocal = allProfiles.find { it.id == cp.deviceSyncId || it.name == cp.name }
                    if (matchingLocal != null && cp.id != null) {
                        db.dao.updateCloudSync(matchingLocal.id, cp.id, System.currentTimeMillis())
                    }
                }
                Result.success(response.syncedCount)
            } else {
                Result.failure(Exception(response.message ?: "Sync push failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing all profiles", e)
            Result.failure(e)
        }
    }

    /**
     * Pulls user profiles from cloud account and saves/restores them to SQLite.
     * This is used when moving to a new phone or restoring backups.
     */
    suspend fun pullProfilesFromCloud(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!AuthManager.isLoggedIn.value) {
            return@withContext Result.failure(Exception("Not logged in. Please sign in to restore cloud profiles."))
        }

        try {
            val response = RetrofitInstance.api.pullProfiles()
            if (response.status == "success") {
                val db = AppDatabase.getDatabase(context)
                var restoredCount = 0
                for (cloudDto in response.profiles) {
                    val profileEntity = cloudDto.toEntity()
                    db.dao.insertOrUpdateProfile(profileEntity)
                    restoredCount++
                }
                Result.success(restoredCount)
            } else {
                Result.failure(Exception("Failed to retrieve profiles from cloud."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling profiles from cloud", e)
            Result.failure(e)
        }
    }

    /**
     * Debounced auto-save session:
     * Saves cookies, visited history, and tabs to local SQLite, and syncs to backend if logged in.
     */
    fun scheduleAutoSave(
        context: Context,
        profileId: String,
        name: String,
        cookiesJson: String,
        historyJson: String,
        tabsJson: String,
        cookieCount: Int
    ) {
        autoSaveDebounceJob?.cancel()
        autoSaveDebounceJob = scope.launch {
            delay(1500) // 1.5s debounce so fast navigations don't flood the network

            // 1. Always save to local database
            val db = AppDatabase.getDatabase(context)
            try {
                db.dao.updateSessionData(
                    id = profileId,
                    cookiesJson = cookiesJson,
                    historyJson = historyJson,
                    tabsJson = tabsJson,
                    cookieCount = cookieCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session locally", e)
            }

            // 2. If user is logged in, auto-save to cloud backend
            if (AuthManager.isLoggedIn.value) {
                try {
                    val req = AutoSaveSessionRequest(
                        deviceSyncId = profileId,
                        name = name,
                        cookiesData = cookiesJson,
                        historyData = historyJson,
                        tabsData = tabsJson,
                        cookieCount = cookieCount,
                        lastUsedTimestamp = System.currentTimeMillis()
                    )
                    RetrofitInstance.api.autoSaveSession(req)
                    Log.d(TAG, "Auto-saved session to cloud for profile '$name'")
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud auto-save skipped or offline: ${e.message}")
                }
            }
        }
    }
}
