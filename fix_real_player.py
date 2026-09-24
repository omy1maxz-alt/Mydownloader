import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# Make playback verified only if currentTime has moved across multiple samples.
# "ACTUAL PLAYBACK VERIFICATION: Do NOT consider playback verified from one snapshot such as isActivePlayer == true... Require currentTime to actually advance across multiple samples."

old_tel = """    fun updatePlayerTelemetry(url: String, telemetry: PlayerTelemetry) {
        // Sanitize: Do not grant active player status for 0 duration non-live streams or tiny outstream/banner ad dimensions.
        val isRealPlayer = (telemetry.durationSec > 0.0 || telemetry.currentTimeSec > 0.0) &&
                           (telemetry.width >= 300 || telemetry.height >= 250)

        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.telemetry = telemetry
            if (telemetry.durationSec > 0) candidate.durationSec = telemetry.durationSec.toInt()
            if (!telemetry.paused && isRealPlayer) candidate.isActivePlayer = true
        }

        // Propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations.
        if (url.startsWith("blob:")) {
            candidates.values.filter { it.isManifest || it.isSegmentGroup || it.hasMSEActivity }.forEach { relatedCandidate ->
                // Only propagate if the network activity happened around the same time or it has MSE activity
                if (relatedCandidate.hasMSEActivity || relatedCandidate.startedAfterPlayback || relatedCandidate.requestCount > 2) {
                    Log.d(TAG, "Propagating Blob Telemetry to network presentation: ${relatedCandidate.url}")
                    relatedCandidate.telemetry = telemetry
                    if (telemetry.durationSec > 0) relatedCandidate.durationSec = telemetry.durationSec.toInt()
                    if (!telemetry.paused && isRealPlayer) relatedCandidate.isActivePlayer = true
                }
            }
        }
    }"""

new_tel = """    // Internal state to track playback progression
    private val playbackSnapshots = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun updatePlayerTelemetry(url: String, telemetry: PlayerTelemetry) {
        // Sanitize: Do not grant active player status for 0 duration non-live streams or tiny outstream/banner ad dimensions.
        val isRealPlayer = (telemetry.durationSec > 0.0 || telemetry.currentTimeSec > 0.0) &&
                           (telemetry.width >= 300 || telemetry.height >= 250)

        var playbackVerified = false
        if (!telemetry.paused && isRealPlayer) {
            val lastTime = playbackSnapshots[url] ?: 0.0
            if (telemetry.currentTimeSec > lastTime + 0.5) {
                // Time has materially progressed since the last snapshot. We have verified continuous playback!
                playbackVerified = true
            }
            playbackSnapshots[url] = telemetry.currentTimeSec
        }

        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.telemetry = telemetry
            if (telemetry.durationSec > 0) candidate.durationSec = telemetry.durationSec.toInt()
            if (playbackVerified) candidate.isActivePlayer = true
        }

        // Propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations.
        if (url.startsWith("blob:")) {
            candidates.values.filter { it.isManifest || it.isSegmentGroup || it.hasMSEActivity }.forEach { relatedCandidate ->
                // Only propagate if the network activity happened around the same time or it has MSE activity
                if (relatedCandidate.hasMSEActivity || relatedCandidate.startedAfterPlayback || relatedCandidate.requestCount > 2) {
                    Log.d(TAG, "Propagating Blob Telemetry to network presentation: ${relatedCandidate.url}")
                    relatedCandidate.telemetry = telemetry
                    if (telemetry.durationSec > 0) relatedCandidate.durationSec = telemetry.durationSec.toInt()
                    if (playbackVerified) relatedCandidate.isActivePlayer = true
                }
            }
        }
    }"""

content = content.replace(old_tel, new_tel)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Added verified playback snapshots to MediaDetectionEngine.")
