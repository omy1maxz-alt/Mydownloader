import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# Fix MediaDetectionEngine getBestCandidate logic and ad filtering.
# "We need to distinguish RAW DISCOVERY from ELIGIBLE DOWNLOAD MEDIA"
# The user wants "duration < 60 seconds => NEVER an eligible detected-media item" but only for FINITE videos!
# So `duration > 0 && duration < 60` is bad.
# If `duration == 0` it's fine.

old_safe = """        // Group into presentations logically before scoring them against each other.
        // We do not want an isolated MP4 ad url to beat an underlying HLS manifest just because of raw score.
        // We filter out high-probability ads AND explicitly reject known videos under 60 seconds (MIN_ACCEPTED_VIDEO_DURATION_SECONDS).
        // A known strong ad (adScore > 0) is hard-excluded from ever winning, regardless of how high its playback/telemetry score gets.
        val safePlayables = playables.filter { (it.durationSec == 0 || it.durationSec >= 60) && it.adScore == 0 }"""

new_safe = """        // Group into presentations logically before scoring them against each other.
        // We filter out high-probability ads AND explicitly reject known videos under 60 seconds (MIN_ACCEPTED_VIDEO_DURATION_SECONDS).
        val safePlayables = playables.filter {
            val isShortAd = it.durationSec in 1..59
            !isShortAd && it.adScore == 0
        }"""
content = content.replace(old_safe, new_safe)

old_prog = """        // If no active manifests, check if we have a strong progressive presentation.
        if (progressives.isNotEmpty()) {
            // Find progressive streams that are actually playing and have decent duration/size, separating them from short pre-rolls
            val strongProgressives = progressives.filter { it.isActivePlayer && (it.durationSec == 0 || it.durationSec >= 60) && it.adScore == 0 }
            if (strongProgressives.isNotEmpty()) {
                return strongProgressives.maxByOrNull { it.finalScore }
            }
        }"""

new_prog = """        // If no active manifests, check if we have a strong progressive presentation.
        if (progressives.isNotEmpty()) {
            // Find progressive streams that are actually playing and have decent duration/size, separating them from short pre-rolls
            val strongProgressives = progressives.filter { it.isActivePlayer && !(it.durationSec in 1..59) && it.adScore == 0 }
            if (strongProgressives.isNotEmpty()) {
                return strongProgressives.maxByOrNull { it.finalScore }
            }
        }"""
content = content.replace(old_prog, new_prog)


# Let's fix processRequest where it penalizes specific CDN clusters (e.g. growcdnssedge) to prevent ad-flood if they are extremely short and isolated
old_ad_url = """        // Check for ad URL signals
        val isLikelyAd = isAdUrl(url)"""

new_ad_url = """        // Check for ad URL signals
        val isLikelyAd = isAdUrl(url) || url.contains("media-hls.growcdnssedge.com/b-hls-") // Specifically block the known SupJav 6s segment ads from flooding the candidate pool"""
content = content.replace(old_ad_url, new_ad_url)


with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
