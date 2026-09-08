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
    val ariaLabel: String? = null
)

data class ResolvedTarget(
    val element: ElementSnapshot,
    val isInsideViewport: Boolean
)
