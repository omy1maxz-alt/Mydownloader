import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# We need to make sure that segments that DO NOT have an existing manifest presentation
# are correctly clustered into a SegmentGroup based on referer and grouping key.
# But we must NOT randomly assign a segment to an unrelated non-manifest file (like a JS or CSS file).
# Currently `findParentManifestForSegment` looks specifically for `candidate.isManifest`.

# But we also need to address the /api/v1/player issue.
# We should ignore obvious non-media URLs before they even enter candidate parsing.

old_process = """        if (mediaKind == MediaKind.UNKNOWN) {
            val lowerUrl = url.lowercase()
            val hasEvidencePath = lowerUrl.contains("/video") || lowerUrl.contains("/stream") || lowerUrl.contains("/play") ||
                                  lowerUrl.contains("/vod") || lowerUrl.contains("/media") || lowerUrl.contains("/movie") ||
                                  lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || lowerUrl.contains("/segment") ||
                                  lowerUrl.contains("?sub=") || lowerUrl.contains("/subtitle") || lowerUrl.contains("/caption")
            if (!hasEvidencePath && contentType == null && !url.contains("videoplayback")) {
                return null
            }
        }"""

new_process = """        if (mediaKind == MediaKind.UNKNOWN) {
            val lowerUrl = url.lowercase()

            // Explicitly reject API and JSON endpoints from being treated as media endpoints,
            // even if they contain the word 'player' or 'stream'
            if (lowerUrl.contains("/api/") || lowerUrl.endsWith(".json") || lowerUrl.endsWith(".js") || lowerUrl.endsWith(".css")) {
                return null
            }

            val hasEvidencePath = lowerUrl.contains("/video") || lowerUrl.contains("/stream") || lowerUrl.contains("/play") ||
                                  lowerUrl.contains("/vod") || lowerUrl.contains("/media") || lowerUrl.contains("/movie") ||
                                  lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || lowerUrl.contains("/segment") ||
                                  lowerUrl.contains("?sub=") || lowerUrl.contains("/subtitle") || lowerUrl.contains("/caption")
            if (!hasEvidencePath && contentType == null && !url.contains("videoplayback")) {
                return null
            }
        }"""

content = content.replace(old_process, new_process)

# Update findParentManifestForSegment to also correlate by referer if host mismatch, but strongly require the parent to actually BE a manifest or segment group.
old_parent = """            for ((candUrl, candidate) in candidates) {
                if (candidate.isManifest && candidate.adScore == 0) {
                    var matchScore = 0
                    val candKey = buildMediaGroupingKey(candUrl)
                    val candUrlObj = URL(candUrl)

                    if (segKey == candKey) {
                        matchScore += 15
                    } else if (segHost == candUrlObj.host) {
                        matchScore += 5
                    }

                    if (matchScore > 0) {
                        // If it's an explicit master or the oldest manifest on the same path, give it a massive priority boost
                        // so segments attach to the presentation master, not just the variant!
                        if (candidate.url.lowercase().contains("master") || candidate.url.lowercase().contains("index")) {
                            matchScore += 20
                        }

                        if (matchScore > bestScore) {
                            bestScore = matchScore
                            bestMatch = candidate
                        } else if (matchScore == bestScore && bestMatch != null) {
                            // Tie-breaker: oldest manifest wins (master is requested before variant)
                            if (candidate.firstSeenTime < bestMatch!!.firstSeenTime) {
                                bestMatch = candidate
                            }
                        }
                    }
                }
            }"""

new_parent = """            for ((candUrl, candidate) in candidates) {
                if ((candidate.isManifest || candidate.isSegmentGroup) && candidate.adScore == 0) {
                    var matchScore = 0
                    val candKey = buildMediaGroupingKey(candUrl)
                    val candUrlObj = try { URL(candUrl) } catch (e: Exception) { null }

                    if (segKey == candKey) {
                        matchScore += 15
                    } else if (candUrlObj != null && segHost == candUrlObj.host) {
                        matchScore += 5
                    }

                    if (matchScore > 0) {
                        // If it's an explicit master or the oldest manifest on the same path, give it a massive priority boost
                        // so segments attach to the presentation master, not just the variant!
                        if (candidate.url.lowercase().contains("master") || candidate.url.lowercase().contains("index")) {
                            matchScore += 20
                        }

                        if (matchScore > bestScore) {
                            bestScore = matchScore
                            bestMatch = candidate
                        } else if (matchScore == bestScore && bestMatch != null) {
                            // Tie-breaker: oldest manifest wins (master is requested before variant)
                            if (candidate.firstSeenTime < bestMatch!!.firstSeenTime) {
                                bestMatch = candidate
                            }
                        }
                    }
                }
            }"""

content = content.replace(old_parent, new_parent)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
