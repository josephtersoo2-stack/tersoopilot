package com.multibrowser.antidetect.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val db = AppDatabase.getDatabase(application)

    val profiles: Flow<List<ProfileEntity>> = db.dao.getAllProfiles()

    private val _maxAllowedConcurrency = MutableStateFlow(5)
    val maxAllowedConcurrency: StateFlow<Int> = _maxAllowedConcurrency.asStateFlow()

    // Sheet & Dialog Visibility States
    val showCreateSheet = MutableStateFlow(false)
    val profileToEdit = MutableStateFlow<ProfileEntity?>(null)
    val showTabsSheet = MutableStateFlow(false)
    val showActiveSheet = MutableStateFlow(false)
    val showProfileSwitcherSheet = MutableStateFlow(false)
    val profileToDelete = MutableStateFlow<ProfileEntity?>(null)
    val profileToStopConfirm = MutableStateFlow<Pair<String, String>?>(null)
    val showExitAppDialog = MutableStateFlow(false)
    val showLogoutConfirmDialog = MutableStateFlow(false)
    val showAccountMenuDialog = MutableStateFlow(false)
    val showAuthDialog = MutableStateFlow(false)
    val showBrowserMenuSheet = MutableStateFlow(false)
    val showBookmarksSheet = MutableStateFlow(false)
    val showHistorySheet = MutableStateFlow(false)
    val cookieActionProfile = MutableStateFlow<Pair<String, String>?>(null)

    init {
        fetchGlobalSettings()
    }

    fun fetchGlobalSettings() {
        viewModelScope.launch {
            try {
                val globalSettings = withContext(Dispatchers.IO) {
                    RetrofitInstance.api.getGlobalSettings()
                }
                _maxAllowedConcurrency.value = globalSettings.maxActiveProfiles
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch global settings, falling back to default concurrency: ${e.message}")
                _maxAllowedConcurrency.value = 5
            }
        }
    }

    fun saveProfile(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.dao.insertOrUpdateProfile(profile)
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.dao.deleteProfile(profile)
        }
    }

    fun pushProfileToCloud(profile: ProfileEntity, onComplete: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            val result = SyncManager.pushProfileToCloud(getApplication(), profile)
            onComplete(result)
        }
    }

    fun pushAllProfiles(onComplete: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = SyncManager.pushAllProfiles(getApplication())
            onComplete(result)
        }
    }

    fun pullProfilesFromCloud(onComplete: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val result = SyncManager.pullProfilesFromCloud(getApplication())
            onComplete(result)
        }
    }

    fun autoSaveSession(
        profileId: String,
        name: String,
        cookiesJson: String,
        historyJson: String,
        tabsJson: String,
        cookieCount: Int
    ) {
        SyncManager.scheduleAutoSave(
            context = getApplication(),
            profileId = profileId,
            name = name,
            cookiesJson = cookiesJson,
            historyJson = historyJson,
            tabsJson = tabsJson,
            cookieCount = cookieCount
        )
    }
}
