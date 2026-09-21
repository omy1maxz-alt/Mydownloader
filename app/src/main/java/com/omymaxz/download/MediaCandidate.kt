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
    var cookie: String? = null,
    var hasMSEActivity: Boolean = false,
    var durationSec: Int = 0,
    var isActivePlayer: Boolean = false,
    var isDRMProtected: Boolean = false,
    var contentType: String? = null,
    var isProgressiveFinal: Boolean = false,
    var isSegmentGroup: Boolean = false,
    val segmentUrls: MutableSet<String> = mutableSetOf(),
    var pathBase: String? = null
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
            if (hasMSEActivity) score += 30
            if (isActivePlayer) score += 50

            // Duration penalization / boost
            if (durationSec in 1..29) score -= 20
            else if (durationSec >= 30) score += 10

            // Content-Type Corroboration
            contentType?.lowercase()?.let { ct ->
                if (ct.startsWith("video/")) score += 15
                if (ct.contains("mpegurl") || ct.contains("dash+xml")) score += 20
            }

            return score
        }

    val confidence: String
        get() = when {
            finalScore >= 45 -> "HIGH"
            finalScore >= 20 -> "MEDIUM"
            else -> "LOW"
        }
}
