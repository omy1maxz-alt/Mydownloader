package com.omymaxz.download

data class MediaCandidate(
    val url: String,
    val type: String, // e.g., "video/mp4", "application/x-mpegURL", "dash", "segment"
    val isManifest: Boolean,
    val isSegment: Boolean,
    var isAd: Boolean = false,
    val pageUrl: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    var requestCount: Int = 1,
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var score: Int = 0,
    var startedAfterPlayback: Boolean = false,
    var associatedManifestUrl: String? = null
) {
    // Determine the confidence that this is the main playing video
    fun getConfidence(): Float {
        if (isAd || isSegment) return 0f

        var confidence = 0f

        // Manifests are highly preferred over raw mp4s if segments are actively being requested
        if (isManifest && requestCount > 0) confidence += 0.4f

        // High request count for manifests/segments implies active streaming
        if (requestCount > 5) confidence += 0.3f

        // Started after user pressed play (or player initialized)
        if (startedAfterPlayback) confidence += 0.3f

        return confidence.coerceIn(0f, 1f)
    }
}
