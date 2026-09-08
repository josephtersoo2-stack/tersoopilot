package com.multibrowser.antidetect.models

data class BrowserProfile(
    val id: String,
    val name: String,
    val userAgent: String,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Double,
    val webGlVendor: String,
    val webGlRenderer: String
)

object ProfilePresets {
    val SAMSUNG_A54 = BrowserProfile(
        id = "profile_samsung_a54",
        name = "Samsung Galaxy A54 (Android 14)",
        userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
        hardwareConcurrency = 8,
        deviceMemory = 8,
        screenWidth = 412,
        screenHeight = 915,
        devicePixelRatio = 2.625,
        webGlVendor = "ARM",
        webGlRenderer = "Mali-G68 MP5"
    )

    val PIXEL_8A = BrowserProfile(
        id = "profile_pixel_8a",
        name = "Google Pixel 8a (Android 14)",
        userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
        hardwareConcurrency = 9,
        deviceMemory = 8,
        screenWidth = 412,
        screenHeight = 915,
        devicePixelRatio = 2.625,
        webGlVendor = "ARM",
        webGlRenderer = "Mali-G715"
    )

    val TECNO_CAMON_30 = BrowserProfile(
        id = "profile_tecno_camon30",
        name = "Tecno Camon 30 Pro (Android 14)",
        userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
        hardwareConcurrency = 8,
        deviceMemory = 12,
        screenWidth = 393,
        screenHeight = 873,
        devicePixelRatio = 2.75,
        webGlVendor = "ARM",
        webGlRenderer = "Mali-G610 MC6"
    )
}
