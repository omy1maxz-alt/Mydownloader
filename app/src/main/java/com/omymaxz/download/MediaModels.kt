package com.omymaxz.download

data class MediaFile(
    val url: String,
    val title: String,
    val mimeType: String,
    val quality: String,
    val category: MediaCategory,
    val fileSize: String,
    val language: String?,
    val isMainContent: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MediaCategory(val displayName: String, val priority: Int) {
    VIDEO("Video", 0),
    AUDIO("Audio", 1),
    SUBTITLE("Subtitle", 2),
    THUMBNAIL("Thumbnail", 3),
    AD("Ad", 99),
    UNKNOWN("Unknown", 100);

    companion object {
        fun fromUrl(url: String): MediaCategory {
            val lowerUrl = url.lowercase()
            return when {
                lowerUrl.contains(".mp4") || lowerUrl.contains(".mkv") || lowerUrl.contains(".webm") || lowerUrl.contains("videoplayback") -> VIDEO
                lowerUrl.contains(".mp3") || lowerUrl.contains(".aac") || lowerUrl.contains(".m4a") -> AUDIO
                lowerUrl.contains(".vtt") || lowerUrl.contains(".srt") -> SUBTITLE
                lowerUrl.contains("thumbnail") || lowerUrl.contains("preview") -> THUMBNAIL
                lowerUrl.contains("googleads") || lowerUrl.contains("doubleclick") -> AD
                else -> UNKNOWN
            }
        }
    }
}
