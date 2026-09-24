import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# Requirements: "Current safePlayables also contains this loophole: adScore == 0 || finalScore > 0... Therefore a candidate marked as an ad can still survive if its score becomes positive... If a candidate is confirmed/high-confidence ad, it should be hard excluded."

old_getbest = """        // Check for strict DMM false-positive avoidance: if we have DRM streams, filter out short low-req non-DRM streams
        val hasDRMPlayables = playables.any { it.isDRMProtected }
        if (hasDRMPlayables) {
            val drmFiltered = playables.filter { it.isDRMProtected || it.requestCount > 10 }
            val safePlayables = drmFiltered.filter { (it.durationSec == 0 || it.durationSec >= 60) && (it.adScore == 0 || it.finalScore > 0) }
            if (safePlayables.isNotEmpty()) return safePlayables.maxByOrNull { it.finalScore }
        }

        // Group into presentations logically before scoring them against each other.
        // We do not want an isolated MP4 ad url to beat an underlying HLS manifest just because of raw score.
        // We filter out high-probability ads AND explicitly reject known videos under 60 seconds (MIN_ACCEPTED_VIDEO_DURATION_SECONDS).
        val safePlayables = playables.filter { (it.durationSec == 0 || it.durationSec >= 60) && (it.adScore == 0 || it.finalScore > 0) }"""

new_getbest = """        // Check for strict DMM false-positive avoidance: if we have DRM streams, filter out short low-req non-DRM streams
        val hasDRMPlayables = playables.any { it.isDRMProtected }
        if (hasDRMPlayables) {
            val drmFiltered = playables.filter { it.isDRMProtected || it.requestCount > 10 }
            val safePlayables = drmFiltered.filter { (it.durationSec == 0 || it.durationSec >= 60) && it.adScore == 0 }
            if (safePlayables.isNotEmpty()) return safePlayables.maxByOrNull { it.finalScore }
        }

        // Group into presentations logically before scoring them against each other.
        // We do not want an isolated MP4 ad url to beat an underlying HLS manifest just because of raw score.
        // We filter out high-probability ads AND explicitly reject known videos under 60 seconds (MIN_ACCEPTED_VIDEO_DURATION_SECONDS).
        // A known strong ad (adScore > 0) is hard-excluded from ever winning, regardless of how high its playback/telemetry score gets.
        val safePlayables = playables.filter { (it.durationSec == 0 || it.durationSec >= 60) && it.adScore == 0 }"""

content = content.replace(old_getbest, new_getbest)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Fixed safePlayables ad exclusion")
