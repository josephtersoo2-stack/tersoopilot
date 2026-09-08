package com.multibrowser.antidetect.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

data class CookieSyncPayload(
    @SerializedName("cookies") val cookies: List<Map<String, Any>>
)

data class CookieSyncResponse(
    @SerializedName("status") val status: String,
    @SerializedName("profile_id") val profileId: String,
    @SerializedName("imported_count") val importedCount: Int
)

interface CookieApiService {
    @POST("api/profiles/{id}/cookies/import/")
    suspend fun pushCookiesToBackend(
        @Path("id") profileId: String,
        @Body payload: CookieSyncPayload
    ): CookieSyncResponse
}
