package com.multibrowser.antidetect.network

import com.google.gson.annotations.SerializedName
import com.multibrowser.antidetect.data.model.ProfileEntity
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class GenerateDeviceRequest(
    val query: String,
    val provider: String? = null,
    val model: String? = null
)

data class DeviceFingerprintDto(
    val brand: String,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("model_code") val modelCode: String,
    @SerializedName("android_version") val androidVersion: Int,
    val soc: String,
    @SerializedName("webgl_vendor") val webGlVendor: String,
    @SerializedName("webgl_renderer") val webGlRenderer: String,
    @SerializedName("ram_gb") val ramGb: Int,
    @SerializedName("cpu_cores") val cpuCores: Int,
    @SerializedName("screen_width") val screenWidth: Int,
    @SerializedName("screen_height") val screenHeight: Int,
    val dpr: Double,
    @SerializedName("user_agent") val userAgent: String
)

data class IPLookupDto(
    val ip: String,
    val country: String,
    @SerializedName("country_code") val countryCode: String,
    val city: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double
)

data class ModelItemDto(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerializedName("context_length") val contextLength: Int? = null
)

data class ProviderModelsResponseDto(
    val provider: String,
    val models: List<ModelItemDto> = emptyList(),
    val count: Int? = null,
    val error: String? = null
)

data class GlobalSettingDto(
    @SerializedName("max_active_profiles") val maxActiveProfiles: Int = 5,
    @SerializedName("force_global_mute") val forceGlobalMute: Boolean = true,
    @SerializedName("default_video_resolution") val defaultVideoResolution: String = "240p",
    @SerializedName("updated_at") val updatedAt: String? = null
)

// Auth & Cloud Sync Models
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String = ""
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class UserDto(
    val id: Int,
    val username: String,
    val email: String? = null,
    @SerializedName("profile_count") val profileCount: Int? = 0
)

data class AuthResponseDto(
    val status: String,
    val message: String? = null,
    val token: String,
    val user: UserDto
)

data class CloudProfileDto(
    val id: String? = null,
    val name: String,
    val tag: String? = "Default",
    val brand: String,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("model_code") val modelCode: String,
    @SerializedName("android_version") val androidVersion: Int = 14,
    val soc: String,
    @SerializedName("webgl_vendor") val webGlVendor: String,
    @SerializedName("webgl_renderer") val webGlRenderer: String,
    @SerializedName("ram_gb") val ramGb: Int = 8,
    @SerializedName("cpu_cores") val cpuCores: Int = 8,
    @SerializedName("screen_width") val screenWidth: Int = 384,
    @SerializedName("screen_height") val screenHeight: Int = 854,
    val dpr: Double = 2.8125,
    @SerializedName("user_agent") val userAgent: String = "",
    @SerializedName("proxy_type") val proxyType: String = "DIRECT",
    @SerializedName("proxy_host") val proxyHost: String = "",
    @SerializedName("proxy_port") val proxyPort: Int = 0,
    @SerializedName("proxy_user") val proxyUser: String = "",
    @SerializedName("proxy_pass") val proxyPass: String = "",
    @SerializedName("web_rtc_mode") val webRtcMode: String = "Mdns",
    @SerializedName("cookies_data") val cookiesData: String = "[]",
    @SerializedName("history_data") val historyData: String = "[]",
    @SerializedName("tabs_data") val tabsData: String = "[]",
    @SerializedName("last_used_timestamp") val lastUsedTimestamp: Long = 0L,
    @SerializedName("cookie_count") val cookieCount: Int = 0,
    @SerializedName("device_sync_id") val deviceSyncId: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class SyncPushRequest(
    val profiles: List<CloudProfileDto>
)

data class SyncPushResponse(
    val status: String,
    val message: String? = null,
    @SerializedName("synced_count") val syncedCount: Int = 0,
    val profiles: List<CloudProfileDto> = emptyList()
)

data class SyncPullResponse(
    val status: String,
    val count: Int = 0,
    val profiles: List<CloudProfileDto> = emptyList()
)

data class AutoSaveSessionRequest(
    @SerializedName("device_sync_id") val deviceSyncId: String,
    val name: String,
    @SerializedName("cookies_data") val cookiesData: String,
    @SerializedName("history_data") val historyData: String,
    @SerializedName("tabs_data") val tabsData: String,
    @SerializedName("cookie_count") val cookieCount: Int,
    @SerializedName("last_used_timestamp") val lastUsedTimestamp: Long
)

data class AutoSaveSessionResponse(
    val status: String,
    @SerializedName("profile_id") val profileId: String? = null,
    @SerializedName("cookie_count") val cookieCount: Int? = 0
)

fun ProfileEntity.toCloudDto(): CloudProfileDto {
    return CloudProfileDto(
        id = if (cloudSyncId.isNotBlank()) cloudSyncId else null,
        name = name,
        tag = tag.ifBlank { "Default" },
        brand = brand,
        modelName = modelName,
        modelCode = modelCode,
        androidVersion = androidVersion,
        soc = soc,
        webGlVendor = webGlVendor,
        webGlRenderer = webGlRenderer,
        ramGb = ramGb,
        cpuCores = cpuCores,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        dpr = dpr,
        userAgent = userAgent,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUser = proxyUser,
        proxyPass = proxyPass,
        webRtcMode = webRtcMode,
        cookiesData = cookiesJson,
        historyData = historyJson,
        tabsData = tabsJson,
        lastUsedTimestamp = lastUsedTimestamp,
        cookieCount = cookieCount,
        deviceSyncId = id
    )
}

fun CloudProfileDto.toEntity(): ProfileEntity {
    return ProfileEntity(
        id = deviceSyncId?.ifBlank { null } ?: (id ?: java.util.UUID.randomUUID().toString()),
        name = name,
        description = "Synced from Cloud",
        tag = tag ?: "Default",
        brand = brand,
        modelName = modelName,
        modelCode = modelCode,
        androidVersion = androidVersion,
        userAgent = userAgent,
        soc = soc,
        webGlVendor = webGlVendor,
        webGlRenderer = webGlRenderer,
        ramGb = ramGb,
        cpuCores = cpuCores,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        dpr = dpr,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUser = proxyUser,
        proxyPass = proxyPass,
        webRtcMode = webRtcMode,
        lastUsedTimestamp = lastUsedTimestamp,
        cookieCount = cookieCount,
        cookiesJson = cookiesData,
        historyJson = historyData,
        tabsJson = tabsData,
        cloudSyncId = id ?: "",
        lastSyncedAt = System.currentTimeMillis()
    )
}

interface ApiService {
    @GET("api/settings/global/")
    suspend fun getGlobalSettings(): GlobalSettingDto

    @GET("api/devices/models/")
    suspend fun getAvailableModels(@Query("provider") provider: String): ProviderModelsResponseDto

    @POST("api/devices/generate/")
    suspend fun generateDevice(@Body request: GenerateDeviceRequest): DeviceFingerprintDto

    @GET("api/ip/lookup/")
    suspend fun lookupIp(@Query("ip") ip: String? = null): IPLookupDto

    // Auth endpoints
    @POST("api/auth/register/")
    suspend fun register(@Body req: RegisterRequest): AuthResponseDto

    @POST("api/auth/login/")
    suspend fun login(@Body req: LoginRequest): AuthResponseDto

    @GET("api/auth/me/")
    suspend fun getMe(): UserDto

    // Sync endpoints
    @POST("api/sync/push/")
    suspend fun pushProfiles(@Body req: SyncPushRequest): SyncPushResponse

    @GET("api/sync/pull/")
    suspend fun pullProfiles(): SyncPullResponse

    @POST("api/sync/auto-save/")
    suspend fun autoSaveSession(@Body req: AutoSaveSessionRequest): AutoSaveSessionResponse
}

object RetrofitInstance {
    @Volatile
    private var endpoint = com.multibrowser.antidetect.BuildConfig.API_BASE_URL

    val activeHost: String get() = endpoint

    @Synchronized
    fun setHost(host: String, clearCredentials: Boolean = true) {
        val value = host.trim().trimEnd('/')
        val candidate = if (value.contains("://")) value else {
            if (com.multibrowser.antidetect.BuildConfig.DEBUG) "http://$value:8000" else "https://$value"
        }
        val url = candidate.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials are not allowed in server URLs." }
        require(url.query == null && url.fragment == null && url.encodedPath == "/") { "Use a server origin without a path or query." }
        require(com.multibrowser.antidetect.BuildConfig.DEBUG || url.isHttps) { "HTTPS is required in release builds." }
        val normalized = url.toString()
        if (endpoint != normalized) {
            // An account token belongs to one server, never forward it after a server change.
            if (clearCredentials) AuthManager.logout()
            endpoint = normalized
            _retrofit = null
            _api = null
        }
    }

    @Volatile private var _retrofit: Retrofit? = null
    @Volatile private var _api: ApiService? = null

    val retrofit: Retrofit
        get() = _retrofit ?: synchronized(this) {
            _retrofit ?: run {
                val logging = HttpLoggingInterceptor().apply {
                    level = if (com.multibrowser.antidetect.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                    redactHeader("Authorization")
                }
                val clientEndpoint = endpoint
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        check(clientEndpoint == endpoint) { "Server changed; recreate the API client." }
                        val request = chain.request().newBuilder()
                        AuthManager.getAuthToken()?.takeIf { it.isNotBlank() }?.let {
                            request.header("Authorization", "Token $it")
                        }
                        chain.proceed(request.build())
                    }
                    .addInterceptor(logging)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
                Retrofit.Builder().baseUrl(endpoint).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().also { _retrofit = it }
            }
        }

    val api: ApiService
        get() = _api ?: synchronized(this) {
            _api ?: retrofit.create(ApiService::class.java).also { _api = it }
        }
}
