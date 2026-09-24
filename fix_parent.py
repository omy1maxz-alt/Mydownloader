import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_parent_req = """        // Try to associate segments with their parent manifest if they share a path
        if (!isLikelyAd && (isSegment || (mediaKind == MediaKind.UNKNOWN && !isProgressiveFinal && !isManifest))) {
            val parentCandidate = findParentManifestForSegment(url)
            if (parentCandidate != null) {
                parentCandidate.requestCount++
                parentCandidate.lastSeenTime = System.currentTimeMillis()
                if (isPlaybackActive) {
                    parentCandidate.startedAfterPlayback = true
                    parentCandidate.playbackScore += 5
                }
                Log.d(TAG, "Correlated segment to manifest: ${parentCandidate.url}")
                return parentCandidate
            } else {"""

new_parent_req = """        // Try to associate segments with their parent manifest if they share a path
        if (!isLikelyAd && (isSegment || (mediaKind == MediaKind.UNKNOWN && !isProgressiveFinal && !isManifest))) {
            val parentCandidate = findParentManifestForSegment(url)
            if (parentCandidate != null) {
                parentCandidate.requestCount++
                parentCandidate.lastSeenTime = System.currentTimeMillis()

                // Uncapped boost so dynamic chunk loading overwhelms static MP4 ads
                parentCandidate.playbackScore += 5

                if (isPlaybackActive) {
                    parentCandidate.startedAfterPlayback = true
                }
                Log.d(TAG, "Correlated segment to manifest: ${parentCandidate.url} (Score: ${parentCandidate.playbackScore})")
                return parentCandidate
            } else {"""

content = content.replace(old_parent_req, new_parent_req)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
