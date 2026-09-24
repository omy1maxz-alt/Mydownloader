import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_telemetry = """    fun updatePlayerTelemetry(url: String, telemetry: PlayerTelemetry) {
        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.telemetry = telemetry
            if (telemetry.durationSec > 0) candidate.durationSec = telemetry.durationSec.toInt()
            if (!telemetry.paused) candidate.isActivePlayer = true
        }

        // Propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations.
        if (url.startsWith("blob:")) {
            candidates.values.filter { it.isManifest || it.isSegmentGroup || it.hasMSEActivity }.forEach { relatedCandidate ->
                // Only propagate if the network activity happened around the same time or it has MSE activity
                if (relatedCandidate.hasMSEActivity || relatedCandidate.startedAfterPlayback || relatedCandidate.requestCount > 2) {
                    Log.d(TAG, "Propagating Blob Telemetry to network presentation: ${relatedCandidate.url}")
                    relatedCandidate.telemetry = telemetry
                    if (telemetry.durationSec > 0) relatedCandidate.durationSec = telemetry.durationSec.toInt()
                    if (!telemetry.paused) relatedCandidate.isActivePlayer = true
                }
            }
        }
    }"""

new_telemetry = """    fun updatePlayerTelemetry(url: String, telemetry: PlayerTelemetry) {
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

content = content.replace(old_telemetry, new_telemetry)


old_active = """    fun markCandidateAsActivePlayer(url: String, duration: Int) {
        var candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate == null) {
            candidate = processRequest(url, null, null)

            // Force tracking for extensionless URLs that are explicitly playing in the active player
            if (candidate == null) {
                candidate = MediaCandidate(
                    url = url,
                    type = "video",
                    mediaKind = MediaKind.PROGRESSIVE,
                    isProgressiveFinal = true
                )
                candidates[url] = candidate
            }
        }

        candidate.isActivePlayer = true
        if (duration > 0) candidate.durationSec = duration
        Log.d(TAG, "Active player associated with candidate: ${candidate.url} (duration: $duration)")

        // Propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations.
        if (url.startsWith("blob:")) {
            candidates.values.filter { it.isManifest || it.isSegmentGroup || it.hasMSEActivity }.forEach { relatedCandidate ->
                if (relatedCandidate.hasMSEActivity || relatedCandidate.startedAfterPlayback || relatedCandidate.requestCount > 2) {
                    Log.d(TAG, "Propagating Blob Active State to network presentation: ${relatedCandidate.url}")
                    relatedCandidate.isActivePlayer = true
                    if (duration > 0) relatedCandidate.durationSec = duration
                }
            }
        }
    }"""

new_active = """    fun markCandidateAsActivePlayer(url: String, duration: Int) {
        // Sanitize: Ignore 0 duration outstream tracking injections if we just use duration signal alone here
        if (duration == 0) return

        var candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate == null) {
            candidate = processRequest(url, null, null)

            // Force tracking for extensionless URLs that are explicitly playing in the active player
            if (candidate == null) {
                candidate = MediaCandidate(
                    url = url,
                    type = "video",
                    mediaKind = MediaKind.PROGRESSIVE,
                    isProgressiveFinal = true
                )
                candidates[url] = candidate
            }
        }

        candidate.isActivePlayer = true
        if (duration > 0) candidate.durationSec = duration
        Log.d(TAG, "Active player associated with candidate: ${candidate.url} (duration: $duration)")

        // Propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations.
        if (url.startsWith("blob:")) {
            candidates.values.filter { it.isManifest || it.isSegmentGroup || it.hasMSEActivity }.forEach { relatedCandidate ->
                if (relatedCandidate.hasMSEActivity || relatedCandidate.startedAfterPlayback || relatedCandidate.requestCount > 2) {
                    Log.d(TAG, "Propagating Blob Active State to network presentation: ${relatedCandidate.url}")
                    relatedCandidate.isActivePlayer = true
                    if (duration > 0) relatedCandidate.durationSec = duration
                }
            }
        }
    }"""

content = content.replace(old_active, new_active)


with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
