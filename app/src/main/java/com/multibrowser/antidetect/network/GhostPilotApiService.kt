package com.multibrowser.antidetect.network

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

import retrofit2.http.Query

interface GhostPilotApiService {
    @GET("api/automation/ghostpilot/poll/{profile_id}/")
    suspend fun pollJob(
        @Path("profile_id") profileId: String,
        @Query("profile_name") profileName: String? = null,
        @Query("cloud_sync_id") cloudSyncId: String? = null
    ): JsonObject

    @POST("api/automation/ghostpilot/{id}/transition/")
    suspend fun transitionState(
        @Path("id") jobId: String,
        @Body payload: Map<String, @JvmSuppressWildcards Any>
    ): JsonObject

    @POST("api/automation/ghostpilot/{id}/heartbeat/")
    suspend fun sendHeartbeat(@Path("id") jobId: String): JsonObject

    @POST("api/automation/ghostpilot/{id}/decision/")
    suspend fun requestDecision(
        @Path("id") jobId: String,
        @Body payload: Map<String, @JvmSuppressWildcards Any>
    ): JsonObject
}
