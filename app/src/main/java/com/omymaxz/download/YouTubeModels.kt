package com.omymaxz.download

data class YouTubeVideo(
    val title: String,
    val formats: List<YouTubeFormat>
)

data class YouTubeFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val width: Int,
    val height: Int,
    val contentLength: String?,
    val averageBitrate: Int?,
    val audioQuality: String?,
    val fps: Int?
) {
    fun isVideoOnly(): Boolean = mimeType.startsWith("video/") && (audioQuality == null || audioQuality == "null")
    fun isAudioOnly(): Boolean = mimeType.startsWith("audio/")
    fun isCombined(): Boolean = mimeType.startsWith("video/") && audioQuality != null && audioQuality != "null"
}
