package com.multibrowser.antidetect.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AuthManager {
    private const val PREFS_NAME = "antidetect_auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"

    private const val KEY_SERVER_HOST = "server_host"

    private var prefs: SharedPreferences? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername.asStateFlow()

    private val _currentUser = MutableStateFlow<UserDto?>(null)
    val currentUser: StateFlow<UserDto?> = _currentUser.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs?.getString(KEY_TOKEN, null)
            val username = prefs?.getString(KEY_USERNAME, null)
            val id = prefs?.getInt(KEY_USER_ID, -1) ?: -1
            val email = prefs?.getString(KEY_EMAIL, null)

            val savedHost = prefs?.getString(KEY_SERVER_HOST, null)
            if (!savedHost.isNullOrBlank()) {
                RetrofitInstance.setHost(savedHost)
            }

            if (!token.isNullOrBlank() && !username.isNullOrBlank()) {
                _isLoggedIn.value = true
                _currentUsername.value = username
                _currentUser.value = UserDto(id = id, username = username, email = email)
            } else {
                _isLoggedIn.value = false
                _currentUsername.value = null
                _currentUser.value = null
            }
        }
    }

    fun getAuthToken(): String? {
        return prefs?.getString(KEY_TOKEN, null)
    }

    fun saveAuth(token: String, user: UserDto) {
        prefs?.edit()?.apply {
            putString(KEY_TOKEN, token)
            putInt(KEY_USER_ID, user.id)
            putString(KEY_USERNAME, user.username)
            putString(KEY_EMAIL, user.email ?: "")
            apply()
        }
        _isLoggedIn.value = true
        _currentUsername.value = user.username
        _currentUser.value = user
    }

    fun logout() {
        prefs?.edit()?.remove(KEY_TOKEN)?.remove(KEY_USER_ID)?.remove(KEY_USERNAME)?.remove(KEY_EMAIL)?.apply()
        _isLoggedIn.value = false
        _currentUsername.value = null
        _currentUser.value = null
    }

    fun getServerHost(): String {
        return prefs?.getString(KEY_SERVER_HOST, null) ?: RetrofitInstance.activeHost
    }

    fun saveServerHost(host: String) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")
            .substringBefore(":")
            .trim()
        if (cleanHost.isNotBlank()) {
            prefs?.edit()?.putString(KEY_SERVER_HOST, cleanHost)?.apply()
            RetrofitInstance.setHost(cleanHost)
        }
    }
}
