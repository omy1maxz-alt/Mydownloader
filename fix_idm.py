import sys

# The user is telling me to "Upgrade Mydownloader Media Detection Based on IDM+ Forensic Findings"
# "A 30-second MP4 advertisement can become an "active player" and currently receive a very high candidate score."
# "Do not solve this with a Sextb-specific hostname rule."
# "The real movie can be playing through a cross-origin iframe/MSE pipeline while our detector fails to connect the browser playback evidence to the underlying HLS presentation."
# "When the user switches: TB -> SW -> DD -> another server the detector must not let stale candidates from the previous player continue dominating the new session."

# Wait, the code I JUST wrote in the last 4 iterations (which is currently physically written into `MediaDetectionEngine.kt` right now on my local workspace) literally DOES exactly what the user is asking.
# I wrote `propagate evidence: if this is a blob URL (MSE player), associate its active telemetry with the actual underlying network presentations`.
# I wrote `val activeManifests = manifests.filter { it.isActivePlayer || it.hasMSEActivity || it.requestCount > 2 }` in `getBestCandidate` which forces active presentations to win over short ad mp4s.
# I wrote `if (telemetry.durationSec > 0.0 || telemetry.currentTimeSec > 0.0) && (telemetry.width >= 300 || telemetry.height >= 250)` which forces small ads out of active player status.
# I wrote `val explicitMaster = activeManifests.find { it.url.lowercase().contains("master") || it.url.lowercase().contains("index") }`.

# The only thing I HAVEN'T explicitly solved from this specific new prompt is the "Stale Server Switch" issue properly.
# The user explicitly says:
# "When the user switches: Server 1 -> Server 2, determine whether old candidates remain after the server switch... The detector should preserve useful history where necessary, but current-media selection must strongly distinguish the currently active frame/player/session from stale server candidates."

# Right now `getBestCandidate` looks at `candidates.values`. There is no expiration of stale candidates, so if Server 1 had a master manifest, it stays there forever and might continue to outscore Server 2 if Server 2 is slower or slightly weaker on evidence.

# We need to add an expiration / stale penalty to `finalScore` or `getBestCandidate` based on `lastSeenTime`.

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_get_best = """        // Isolate presentations
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
        }"""

new_get_best = """        // Isolate presentations
        val now = System.currentTimeMillis()
        val recentPlayables = safePlayables.filter { (now - it.lastSeenTime) < 45000 || it.isActivePlayer } // Ignore stale candidates from old servers unless actively playing

        val manifests = recentPlayables.filter { it.isManifest }
        val progressives = recentPlayables.filter { !it.isManifest && it.isProgressiveFinal }
        val segmentGroups = recentPlayables.filter { it.isSegmentGroup }

        // If we have an actively playing manifest with strong evidence, it wins.
        if (manifests.isNotEmpty()) {
            // Actively playing or recently requested (e.g. streaming segments right now)
            val activeManifests = manifests.filter { it.isActivePlayer || it.hasMSEActivity || (now - it.lastSeenTime < 15000 && it.requestCount > 2) }
            if (activeManifests.isNotEmpty()) {
                val explicitMaster = activeManifests.find { (it.url.lowercase().contains("master") || it.url.lowercase().contains("index")) }
                if (explicitMaster != null) return explicitMaster
                // If multiple active manifests, pick the one most recently seen (current server), breaking ties by oldest firstSeen (master vs variant)
                return activeManifests.sortedWith(compareByDescending<MediaCandidate> { it.lastSeenTime }.thenBy { it.firstSeenTime }).first()
            }
        }"""

content = content.replace(old_get_best, new_get_best)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
