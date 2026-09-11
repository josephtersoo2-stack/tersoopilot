package com.multibrowser.antidetect.data.model

import java.util.UUID

data class ProfileEntity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val tag: String = "",
    val brand: String,
    val modelName: String,
    val modelCode: String,
    val androidVersion: Int = 14,
    val userAgent: String,
    val soc: String,
    val webGlVendor: String,
    val webGlRenderer: String,
    val ramGb: Int = 8,
    val cpuCores: Int = 8,
    val screenWidth: Int = 412,
    val screenHeight: Int = 915,
    val dpr: Double = 2.625,
    // Proxy & Location Configuration
    val proxyType: String = "DIRECT", // DIRECT, HTTP, SOCKS5
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val timezone: String = "Auto",
    val language: String = "Auto",
    val webRtcMode: String = "Mdns", // Mdns, Disabled, Direct
    // Runtime Metadata
    val lastUsedTimestamp: Long = 0L,
    val cookieCount: Int = 0,
    val selectedCameraVideoPath: String? = null,
    // Cloud Sync & Session Persistence
    val cookiesJson: String = "[]",
    val historyJson: String = "[]",
    val tabsJson: String = "[]",
    val cloudSyncId: String = "",
    val lastSyncedAt: Long = 0L,
    // Concurrency & Optimistic Versioning
    val syncVersion: Int = 1,
    val updatedAt: Long = 0L
)

