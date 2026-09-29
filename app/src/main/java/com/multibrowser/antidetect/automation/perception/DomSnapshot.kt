package com.multibrowser.antidetect.automation.perception

import com.google.gson.annotations.SerializedName

data class DomRect(
    @SerializedName("left") val left: Float,
    @SerializedName("top") val top: Float,
    @SerializedName("width") val width: Float,
    @SerializedName("height") val height: Float
)

data class ViewportInfo(
    @SerializedName("width") val width: Float,
    @SerializedName("height") val height: Float,
    @SerializedName("dpr") val dpr: Float,
    @SerializedName("scrollX") val scrollX: Float,
    @SerializedName("scrollY") val scrollY: Float
)

data class VideoPlaybackState(
    @SerializedName("currentTime") val currentTime: Int,
    @SerializedName("duration") val duration: Int,
    @SerializedName("paused") val paused: Boolean,
    @SerializedName("ended") val ended: Boolean
)

data class ElementSnapshot(
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String?,
    @SerializedName("tag") val tag: String?,
    @SerializedName("text") val text: String?,
    @SerializedName("ariaLabel") val ariaLabel: String?,
    @SerializedName("href") val href: String? = null,
    @SerializedName("actionType") val actionType: String? = null,
    @SerializedName("visible") val visible: Boolean,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rect") val rect: DomRect
)

data class DomSnapshot(
    @SerializedName("url") val url: String,
    @SerializedName("title") val title: String?,
    @SerializedName("pageState") val pageState: String,
    @SerializedName("viewport") val viewport: ViewportInfo,
    @SerializedName("elements") val elements: List<ElementSnapshot>,
    @SerializedName("videoState") val videoState: VideoPlaybackState?
)

data class TargetSpec(
    val role: String? = null,
    val selector: String? = null,
    val textSnippet: String? = null,
    val ariaLabel: String? = null,
    val actionType: String? = null,
    val hrefContains: String? = null,
    val targetVideoId: String? = null,
    val coordinateX: Float? = null,
    val coordinateY: Float? = null
)


data class ResolvedTarget(
    val element: ElementSnapshot,
    val isInsideViewport: Boolean
)

data class VideoScanResult(
    @SerializedName("found") val found: Boolean,
    @SerializedName("videoId") val videoId: String? = null,
    @SerializedName("isInViewport") val isInViewport: Boolean = false,
    @SerializedName("isPartiallyInViewport") val isPartiallyInViewport: Boolean = false,
    @SerializedName("isAboveViewport") val isAboveViewport: Boolean = false,
    @SerializedName("isBelowViewport") val isBelowViewport: Boolean = false,
    @SerializedName("rect") val rect: DomRect? = null,
    @SerializedName("pageTop") val pageTop: Float = 0f,
    @SerializedName("pageScrollY") val pageScrollY: Float = 0f,
    @SerializedName("viewportHeight") val viewportHeight: Float = 0f,
    @SerializedName("viewportWidth") val viewportWidth: Float = 0f,
    @SerializedName("dpr") val dpr: Float = 1f
)

data class ElementSpotResult(
    @SerializedName("found") val found: Boolean,
    @SerializedName("spot") val spot: String? = null,
    @SerializedName("actionSuccess") val actionSuccess: Boolean = false,
    @SerializedName("isInViewport") val isInViewport: Boolean = false,
    @SerializedName("isPartiallyInViewport") val isPartiallyInViewport: Boolean = false,
    @SerializedName("isAboveViewport") val isAboveViewport: Boolean = false,
    @SerializedName("isBelowViewport") val isBelowViewport: Boolean = false,
    @SerializedName("rect") val rect: DomRect? = null,
    @SerializedName("pageTop") val pageTop: Float = 0f,
    @SerializedName("pageScrollY") val pageScrollY: Float = 0f,
    @SerializedName("viewportHeight") val viewportHeight: Float = 0f,
    @SerializedName("viewportWidth") val viewportWidth: Float = 0f,
    @SerializedName("dpr") val dpr: Float = 1f
)

data class SearchResultItem(
    @SerializedName("index") val index: Int,
    @SerializedName("videoId") val videoId: String?,
    @SerializedName("href") val href: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("isTarget") val isTarget: Boolean = false,
    @SerializedName("rect") val rect: DomRect? = null,
    @SerializedName("isInViewport") val isInViewport: Boolean = false,
    @SerializedName("isAboveViewport") val isAboveViewport: Boolean = false,
    @SerializedName("isBelowViewport") val isBelowViewport: Boolean = false
)

data class SearchResultsScanResult(
    @SerializedName("totalFound") val totalFound: Int = 0,
    @SerializedName("targetFound") val targetFound: Boolean = false,
    @SerializedName("targetIndex") val targetIndex: Int = -1,
    @SerializedName("targetItem") val targetItem: SearchResultItem? = null,
    @SerializedName("dpr") val dpr: Float = 1f,
    @SerializedName("viewportHeight") val viewportHeight: Float = 0f,
    @SerializedName("viewportWidth") val viewportWidth: Float = 0f
)

data class SearchInputVerifyResult(
    @SerializedName("found") val found: Boolean = false,
    @SerializedName("open") val open: Boolean = false,
    @SerializedName("focused") val focused: Boolean = false,
    @SerializedName("rect") val rect: DomRect? = null,
    @SerializedName("dpr") val dpr: Float = 1f
)

data class SpatialAnchorCoord(
    @SerializedName("x") val x: Int = 0,
    @SerializedName("y") val y: Int = 0
)

data class SpatialAnchorsCalibrationResult(
    @SerializedName("calibratedAt") val calibratedAt: Long = 0L,
    @SerializedName("viewportWidth") val viewportWidth: Int = 0,
    @SerializedName("viewportHeight") val viewportHeight: Int = 0,
    @SerializedName("anchors") val anchors: Map<String, SpatialAnchorCoord> = emptyMap()
)


