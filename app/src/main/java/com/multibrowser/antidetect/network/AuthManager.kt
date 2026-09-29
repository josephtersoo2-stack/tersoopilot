package com.multibrowser.antidetect.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AuthManager {
    private const val TAG = "AuthManager"
    private const val PREFS_NAME = "antidetect_auth_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_EMAIL = "email"
    private const val KEY_EXPIRES_AT = "token_expires_at"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

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
            val savedHost = prefs?.getString(KEY_SERVER_HOST, null)
            val isLocal = savedHost != null && (
                savedHost.contains("localhost") || savedHost.contains("127.0.0.1") ||
                savedHost.contains("192.168.") || savedHost.contains("10.")
            )

            if (!savedHost.isNullOrBlank() && (com.multibrowser.antidetect.BuildConfig.DEBUG || !isLocal)) {
                try {
                    RetrofitInstance.setHost(savedHost, clearCredentials = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid saved server host: $savedHost, falling back to default.", e)
                    try {
                        RetrofitInstance.setHost(com.multibrowser.antidetect.BuildConfig.API_BASE_URL, clearCredentials = false)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to initialize default API_BASE_URL: ${com.multibrowser.antidetect.BuildConfig.API_BASE_URL}", e2)
                    }
                }
            } else {
                if (isLocal && !com.multibrowser.antidetect.BuildConfig.DEBUG) {
                    Log.i(TAG, "Purging stale local PC server host in release build. Reverting to production backend.")
                    prefs?.edit()?.remove(KEY_SERVER_HOST)?.apply()
                }
                try {
                    RetrofitInstance.setHost(com.multibrowser.antidetect.BuildConfig.API_BASE_URL, clearCredentials = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize default API_BASE_URL: ${com.multibrowser.antidetect.BuildConfig.API_BASE_URL}", e)
                }
            }

            if (isTokenExpired()) {
                Log.i(TAG, "Stored session token has expired; clearing session.")
                logout()
                return
            }

            val token = getAuthToken()
            val username = prefs?.getString(KEY_USERNAME, null)
            val id = prefs?.getInt(KEY_USER_ID, -1) ?: -1
            val email = prefs?.getString(KEY_EMAIL, null)

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

    /**
     * Returns true if the token is past its expiration timestamp.
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L
        return expiresAt > 0L && System.currentTimeMillis() >= expiresAt
    }

    fun getAuthToken(): String? {
        if (isTokenExpired()) {
            logout()
            return null
        }

        val stored = prefs?.getString(KEY_TOKEN, null) ?: return null
        return try {
            if (stored.startsWith("v1:")) TokenVault.decrypt(stored) else {
                prefs?.edit()?.putString(KEY_TOKEN, TokenVault.encrypt(stored))?.apply()
                stored
            }
        } catch (_: Exception) {
            logout()
            null
        }
    }

    fun saveAuth(
        token: String,
        user: UserDto,
        expiresInHours: Long = 72L,
        refreshToken: String? = null
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInHours * 3600 * 1000L)
        prefs?.edit()?.apply {
            putString(KEY_TOKEN, TokenVault.encrypt(token))
            putInt(KEY_USER_ID, user.id)
            putString(KEY_USERNAME, user.username)
            putString(KEY_EMAIL, user.email ?: "")
            putLong(KEY_EXPIRES_AT, expiresAt)
            if (refreshToken != null) {
                putString(KEY_REFRESH_TOKEN, TokenVault.encrypt(refreshToken))
            }
            apply()
        }
        _isLoggedIn.value = true
        _currentUsername.value = user.username
        _currentUser.value = user
    }

    fun logout() {
        prefs?.edit()?.apply {
            remove(KEY_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_USERNAME)
            remove(KEY_EMAIL)
            remove(KEY_EXPIRES_AT)
            remove(KEY_REFRESH_TOKEN)
            apply()
        }
        _isLoggedIn.value = false
        _currentUsername.value = null
        _currentUser.value = null
    }

    fun handleUnauthorized() {
        Log.w(TAG, "Received 401 Unauthorized from backend. Invalidating local session.")
        logout()
    }

    fun getServerHost(): String {
        return prefs?.getString(KEY_SERVER_HOST, null) ?: RetrofitInstance.activeHost
    }

    fun saveServerHost(host: String) {
        if (!com.multibrowser.antidetect.BuildConfig.DEBUG) {
            val lower = host.lowercase()
            if (lower.contains("localhost") || lower.contains("127.0.0.1") ||
                lower.contains("192.168.") || lower.contains("10.") || lower.contains("10.0.2.2")) {
                throw IllegalArgumentException("Release builds must connect to Namecheap production backend (tersoopilotbd.tersoo.name.ng).")
            }
        }
        RetrofitInstance.setHost(host, clearCredentials = false)
        prefs?.edit()?.putString(KEY_SERVER_HOST, RetrofitInstance.activeHost)?.apply()
    }
}
