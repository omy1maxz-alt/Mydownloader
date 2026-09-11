package com.omymaxz.download

data class MediaCandidate(
    val url: String,
    var type: String,
    var isManifest: Boolean = false,
    var isSegment: Boolean = false,
    var requestCount: Int = 1,
    val firstSeenTime: Long = System.currentTimeMillis(),
    var lastSeenTime: Long = System.currentTimeMillis(),
    var startedAfterPlayback: Boolean = false,
    var adScore: Int = 0,
    var playbackScore: Int = 0,
    var referer: String? = null,
    var userAgent: String? = null,
    var cookie: String? = null
) {
    val finalScore: Int
        get() {
            var score = 0
            if (isManifest) score += 20
            score += playbackScore
            score -= adScore

            // Boost based on continued requests (e.g. streaming segments)
            if (requestCount > 5) score += 10
            if (requestCount > 15) score += 15

            // Significant boost if this stream started fetching after a play event
            if (startedAfterPlayback) score += 25

            return score
        }

    val confidence: String
        get() = when {
            finalScore >= 45 -> "HIGH"
            finalScore >= 20 -> "MEDIUM"
            else -> "LOW"
        }
}
