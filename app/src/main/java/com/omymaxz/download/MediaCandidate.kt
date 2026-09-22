package com.omymaxz.download


enum class MediaKind {
    HLS_MANIFEST,
    DASH_MANIFEST,
    PROGRESSIVE,
    SEGMENT,
    UNKNOWN
}

data class PlayerTelemetry(
    val url: String,
    val width: Int,
    val height: Int,
    val durationSec: Double,
    val currentTimeSec: Double,
    val paused: Boolean,
    val muted: Boolean,
    val autoplay: Boolean,
    val fullscreen: Boolean,
    val visibleAreaRatio: Double,
    val displayedWidth: Double,
    val displayedHeight: Double
)

data class MediaCandidate(
    var mediaKind: MediaKind = MediaKind.UNKNOWN,
    var telemetry: PlayerTelemetry? = null,
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
            if (isManifest || mediaKind == MediaKind.HLS_MANIFEST || mediaKind == MediaKind.DASH_MANIFEST) score += 20
            if (mediaKind == MediaKind.PROGRESSIVE) score += 15

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

            // Telemetry Scoring
            telemetry?.let { t ->
                if (!t.paused) score += 35
                if (t.currentTimeSec > 1.0) score += 20
                if (t.durationSec > 30.0) score += 10
                if (t.fullscreen) score += 30

                if (t.visibleAreaRatio >= 0.35) score += 35
                else if (t.visibleAreaRatio >= 0.10) score += 20
                else if (t.visibleAreaRatio >= 0.02) score += 5

                if (t.width >= 1920 || t.height >= 1080) score += 20
                else if (t.width >= 1280 || t.height >= 720) score += 15
                else if (t.width >= 640 || t.height >= 360) score += 5

                if (t.muted && t.autoplay && t.visibleAreaRatio < 0.05) {
                    score -= 35
                }

                if (t.displayedWidth < 180 || t.displayedHeight < 100) {
                    score -= 25
                }
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
