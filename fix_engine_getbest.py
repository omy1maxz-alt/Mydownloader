import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_getbest = """        // Check for strict DMM false-positive avoidance: if we have DRM streams, filter out short low-req non-DRM streams
        val hasDRMPlayables = playables.any { it.isDRMProtected }
        if (hasDRMPlayables) {
            val drmFiltered = playables.filter { it.isDRMProtected || it.requestCount > 10 }
            val safePlayables = drmFiltered.filter { it.adScore == 0 || it.finalScore > 0 }
            if (safePlayables.isNotEmpty()) return safePlayables.maxByOrNull { it.finalScore }
        }

        // Remove high probability ads from the final playable selection entirely unless they are the ONLY thing available
        val safePlayables = playables.filter { it.adScore == 0 || it.finalScore > 0 }

        if (safePlayables.isEmpty()) return candidates.values.maxByOrNull { it.finalScore }

        // We want the best presentation.
        // If there are multiple manifests with high confidence, prefer the one fetched FIRST (which is typically the master manifest).
        // If a variant manifest outscores the master because segments attached to it, the master will be lost if we only use `maxByOrNull`.
        val manifests = safePlayables.filter { it.isManifest }
        if (manifests.isNotEmpty()) {
            // Find an explicit master playlist first
            val explicitMaster = manifests.find { (it.url.lowercase().contains("master") || it.url.lowercase().contains("index")) && it.finalScore > 0 }
            if (explicitMaster != null) return explicitMaster

            // Otherwise, get the active/high-scoring manifests.
            // If multiple related manifests exist, the oldest one is the master.
            val activeManifests = manifests.filter { it.isActivePlayer || it.finalScore >= 45 || it.startedAfterPlayback }
            if (activeManifests.isNotEmpty()) {
                return activeManifests.minByOrNull { it.firstSeenTime }
            }

            // Fallback to highest scored manifest
            return manifests.maxByOrNull { it.finalScore }
        }

        return safePlayables.maxByOrNull { it.finalScore }"""

new_getbest = """        // Check for strict DMM false-positive avoidance: if we have DRM streams, filter out short low-req non-DRM streams
        val hasDRMPlayables = playables.any { it.isDRMProtected }
        if (hasDRMPlayables) {
            val drmFiltered = playables.filter { it.isDRMProtected || it.requestCount > 10 }
            val safePlayables = drmFiltered.filter { it.adScore == 0 || it.finalScore > 0 }
            if (safePlayables.isNotEmpty()) return safePlayables.maxByOrNull { it.finalScore }
        }

        // Group into presentations logically before scoring them against each other.
        // We do not want an isolated MP4 ad url to beat an underlying HLS manifest just because of raw score.
        // We filter out high-probability ads.
        val safePlayables = playables.filter { it.adScore == 0 || it.finalScore > 0 }

        if (safePlayables.isEmpty()) return candidates.values.maxByOrNull { it.finalScore }

        // Isolate presentations
        val manifests = safePlayables.filter { it.isManifest }
        val progressives = safePlayables.filter { !it.isManifest && it.isProgressiveFinal }
        val segmentGroups = safePlayables.filter { it.isSegmentGroup }

        // If we have an actively playing manifest with strong evidence, it wins.
        if (manifests.isNotEmpty()) {
            val activeManifests = manifests.filter { it.isActivePlayer || it.hasMSEActivity || it.requestCount > 2 }
            if (activeManifests.isNotEmpty()) {
                val explicitMaster = activeManifests.find { (it.url.lowercase().contains("master") || it.url.lowercase().contains("index")) }
                if (explicitMaster != null) return explicitMaster
                return activeManifests.minByOrNull { it.firstSeenTime } // Oldest active manifest is likely master
            }
        }

        // If no active manifests, check if we have a strong progressive presentation.
        if (progressives.isNotEmpty()) {
            // Find progressive streams that are actually playing and have decent duration/size, separating them from short pre-rolls
            val strongProgressives = progressives.filter { it.isActivePlayer && (it.durationSec == 0 || it.durationSec > 45) && it.adScore == 0 }
            if (strongProgressives.isNotEmpty()) {
                return strongProgressives.maxByOrNull { it.finalScore }
            }
        }

        // Fallback to segment groups if no manifests or progressives are strong
        if (segmentGroups.isNotEmpty()) {
            val activeGroups = segmentGroups.filter { it.isActivePlayer || it.hasMSEActivity || it.requestCount > 5 }
            if (activeGroups.isNotEmpty()) return activeGroups.maxByOrNull { it.finalScore }
        }

        // Ultimate fallback: return the highest scored safe playable (which might be an inactive manifest or decent progressive)
        return safePlayables.maxByOrNull { it.finalScore }"""

content = content.replace(old_getbest, new_getbest)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
