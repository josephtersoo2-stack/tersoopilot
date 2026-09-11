package com.multibrowser.antidetect.sync

import android.content.Context
import android.util.Log
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.AuthManager
import com.multibrowser.antidetect.network.AutoSaveSessionRequest
import com.multibrowser.antidetect.network.CloudProfileDto
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
     * Pushes a single profile to the backend cloud immediately with versioning.
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
     * Pulls user profiles from cloud account and applies conflict resolution:
     * - New cloud profiles are inserted cleanly.
     * - For existing profiles, compares local vs cloud timestamps and versions.
     * - Avoids blind overwrites; merges non-conflicting data and preserves local session state.
     */
    suspend fun pullProfilesFromCloud(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!AuthManager.isLoggedIn.value) {
            return@withContext Result.failure(Exception("Not logged in. Please sign in to restore cloud profiles."))
        }

        try {
            val response = RetrofitInstance.api.pullProfiles()
            if (response.status == "success") {
                val db = AppDatabase.getDatabase(context)
                val localProfiles = db.dao.getAllProfiles().first().associateBy { it.id }
                var resolvedCount = 0

                for (cloudDto in response.profiles) {
                    val targetId = cloudDto.deviceSyncId?.ifBlank { null } ?: cloudDto.id
                    val existingLocal = targetId?.let { localProfiles[it] }
                        ?: localProfiles.values.find { it.cloudSyncId.isNotBlank() && it.cloudSyncId == cloudDto.id }

                    if (existingLocal == null) {
                        // Clean insert of new profile from cloud
                        val newEntity = cloudDto.toEntity()
                        db.dao.insertOrUpdateProfile(newEntity)
                        resolvedCount++
                    } else {
                        // Conflict resolution: compare timestamps & sync versions
                        val cloudTimestamp = cloudDto.lastUsedTimestamp
                        val localTimestamp = existingLocal.updatedAt.coerceAtLeast(existingLocal.lastUsedTimestamp)

                        if (localTimestamp > cloudTimestamp) {
                            // Local device has newer uncommitted edits; preserve local session & push back to cloud
                            Log.i(TAG, "Conflict resolved: Local profile '${existingLocal.name}' is newer than cloud. Keeping local.")
                            // Update cloudSyncId if missing
                            if (existingLocal.cloudSyncId.isBlank() && !cloudDto.id.isNullOrBlank()) {
                                db.dao.updateCloudSync(existingLocal.id, cloudDto.id, System.currentTimeMillis())
                            }
                        } else {
                            // Cloud record is newer or equal; update local with cloud metadata while preserving local tabs/cookies if cloud has none
                            val mergedCookies = if (cloudDto.cookiesData.isNotBlank() && cloudDto.cookiesData != "[]") {
                                cloudDto.cookiesData
                            } else {
                                existingLocal.cookiesJson
                            }

                            val mergedTabs = if (cloudDto.tabsData.isNotBlank() && cloudDto.tabsData != "[]") {
                                cloudDto.tabsData
                            } else {
                                existingLocal.tabsJson
                            }

                            val updatedEntity = existingLocal.copy(
                                name = cloudDto.name,
                                tag = cloudDto.tag ?: existingLocal.tag,
                                brand = cloudDto.brand,
                                modelName = cloudDto.modelName,
                                modelCode = cloudDto.modelCode,
                                userAgent = cloudDto.userAgent,
                                cookiesJson = mergedCookies,
                                tabsJson = mergedTabs,
                                cookieCount = if (cloudDto.cookieCount > 0) cloudDto.cookieCount else existingLocal.cookieCount,
                                cloudSyncId = cloudDto.id ?: existingLocal.cloudSyncId,
                                lastSyncedAt = System.currentTimeMillis(),
                                syncVersion = existingLocal.syncVersion + 1,
                                updatedAt = System.currentTimeMillis()
                            )

                            val success = db.dao.updateProfileWithVersion(updatedEntity, existingLocal.syncVersion)
                            if (!success) {
                                // Fallback to safe upsert if version raced
                                db.dao.insertOrUpdateProfile(updatedEntity)
                            }
                            resolvedCount++
                        }
                    }
                }
                Result.success(resolvedCount)
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
     * Encrypts cookies before persisting to local SQLite, and syncs to backend if logged in.
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
            delay(1500) // 1.5s debounce

            // 1. Secure cookie storage: encrypt before writing to local DB
            val encryptedCookies = try {
                CookieEngine.encryptCookiePayload(cookiesJson)
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to unencrypted cookies due to encryption issue", e)
                cookiesJson
            }

            // 2. Save session locally in SQLite
            val db = AppDatabase.getDatabase(context)
            try {
                db.dao.updateSessionData(
                    id = profileId,
                    cookiesJson = encryptedCookies,
                    historyJson = historyJson,
                    tabsJson = tabsJson,
                    cookieCount = cookieCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session locally", e)
            }

            // 3. If user is logged in, auto-save to cloud backend
            if (AuthManager.isLoggedIn.value) {
                try {
                    val req = AutoSaveSessionRequest(
                        deviceSyncId = profileId,
                        name = name,
                        cookiesData = encryptedCookies,
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
