package com.omymaxz.download

data class MediaFile(
    val url: String,
    var title: String,
    val mimeType: String,
    val quality: String,
    val category: MediaCategory,
    var fileSize: String,
    val language: String?,
    var isMainContent: Boolean,
    var referer: String? = null,
    var audioUrl: String? = null,
    var subtitleUrls: List<String>? = null
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
            // Check subtitle suffixes before video markers: YouTube caption URLs can
            // contain a video basename like "episode.mp4_en.vtt".
            val path = lowerUrl.substringBefore("?").substringBefore("#")
            return when {
                path.endsWith(".vtt") || path.endsWith(".srt") ||
                    path.endsWith(".ttml") || path.endsWith(".dfxp") ||
                    path.endsWith(".srv1") || path.endsWith(".srv2") || path.endsWith(".srv3") -> SUBTITLE
                lowerUrl.contains(".mp4") || lowerUrl.contains(".mkv") || lowerUrl.contains(".webm") || lowerUrl.contains("videoplayback") -> VIDEO
                lowerUrl.substringBefore("?").endsWith(".mp3") || lowerUrl.substringBefore("?").endsWith(".aac") ||
                    lowerUrl.substringBefore("?").endsWith(".m4a") || lowerUrl.substringBefore("?").endsWith(".ogg") ||
                    lowerUrl.substringBefore("?").endsWith(".opus") || lowerUrl.substringBefore("?").endsWith(".wav") ||
                    lowerUrl.contains("d2lwuy8qc234o3.cloudfront.net/1/clip/") -> AUDIO
                lowerUrl.substringBefore("?").substringBefore("#").let { path ->
                    path.endsWith(".vtt") || path.endsWith(".srt") ||
                        path.endsWith(".ttml") || path.endsWith(".dfxp") ||
                        path.endsWith(".srv1") || path.endsWith(".srv2") || path.endsWith(".srv3")
                } -> SUBTITLE
                lowerUrl.contains("thumbnail") || lowerUrl.contains("preview") -> THUMBNAIL
                lowerUrl.contains("googleads") || lowerUrl.contains("doubleclick") -> AD
                else -> UNKNOWN
            }
        }
    }
}
