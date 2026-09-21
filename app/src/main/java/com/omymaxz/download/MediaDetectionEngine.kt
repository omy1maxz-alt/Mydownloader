package com.omymaxz.download

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.net.URL

class MediaDetectionEngine(private val context: Context) {

    val candidates = java.util.concurrent.ConcurrentHashMap<String, MediaCandidate>()
    private val TAG = "MediaDetectionEngine"

    // Playback state tracker
    var isPlaybackActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                Log.d(TAG, "Playback active signal received. Bootstrapping relevant candidates.")
            }
        }

    fun clear() {
        candidates.clear()
        isPlaybackActive = false
    }

    fun processRequest(url: String, referer: String?, userAgent: String?, contentType: String? = null): MediaCandidate? {

        val cleanUrl = url.substringBefore('?')
        val lowerUrl = cleanUrl.lowercase()

        // Deepen ad keyword detection
        val isLikelyAd = isAdUrl(lowerUrl)

        // Categorize
        val isHlsManifest = lowerUrl.endsWith(".m3u8")
        val isDashManifest = lowerUrl.endsWith(".mpd")
        val isProgressive = lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".mkv")

        val isHlsSegment = lowerUrl.endsWith(".ts")
        val isDashSegment = lowerUrl.endsWith(".m4s") || lowerUrl.endsWith(".m4f")
        val ctLower = contentType?.lowercase()
        var isManifest = isHlsManifest || isDashManifest || (lowerUrl.contains("manifest") && !isHlsSegment && !isDashSegment) || (ctLower?.contains("mpegurl") == true) || (ctLower?.contains("dash+xml") == true)
        var isSegment = isHlsSegment || isDashSegment
        var isProgressiveFinal = isProgressive || (ctLower?.startsWith("video/") == true)

        // Extensionless endpoint path heuristics
        val hasEvidencePath = lowerUrl.contains("/video") || lowerUrl.contains("/stream") || lowerUrl.contains("/play") ||
                              lowerUrl.contains("/vod") || lowerUrl.contains("/media") || lowerUrl.contains("/movie") ||
                              lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || lowerUrl.contains("/segment") ||
                              lowerUrl.contains("?sub=") || lowerUrl.contains("/subtitle") || lowerUrl.contains("/caption")

        if (!isManifest && !isSegment && !isProgressiveFinal && !url.contains("videoplayback")) {
            // Might be an extensionless video. We track it if it has strong path evidence or content-type evidence.
            if (!hasEvidencePath && ctLower == null) {
               return null
            }
        }

        // Try to associate segments with their parent manifest if they share a path
        // Do NOT group obvious ads/images
        if (!isLikelyAd && (isSegment || (hasEvidencePath && ctLower == null && !isProgressiveFinal && !isManifest))) { // Group orphan segments and extensionless unproven chunks
            val parentCandidate = findParentManifestForSegment(url)
            if (parentCandidate != null) {
                parentCandidate.requestCount++
                parentCandidate.lastSeenTime = System.currentTimeMillis()
                if (isPlaybackActive) {
                    parentCandidate.startedAfterPlayback = true
                    parentCandidate.playbackScore += 5 // Reward active segments
                }
                Log.d(TAG, "Correlated segment to manifest: ${parentCandidate.url}")
                return parentCandidate
            } else {
                // No manifest found. Try to find an existing Segment Group or create one.
                val groupCand = findOrCreateSegmentGroup(url, referer, userAgent)
                if (groupCand != null) {
                    groupCand.requestCount++
                    groupCand.lastSeenTime = System.currentTimeMillis()
                    groupCand.segmentUrls.add(url)
                    if (isPlaybackActive) {
                        groupCand.startedAfterPlayback = true
                        groupCand.playbackScore += 5
                    }
                    Log.d(TAG, "Correlated segment to group: ${groupCand.url}")
                    return groupCand
                }
            }
        }

        // It's a new media entity or a standalone segment without a known manifest
        val existing = candidates[url]
        if (existing != null) {
            existing.requestCount++
            existing.lastSeenTime = System.currentTimeMillis()
            if (isPlaybackActive) {
                existing.startedAfterPlayback = true
            }
            return existing
        }

        val type = when {
            isManifest -> "manifest"
            isSegment -> "segment"
            isProgressiveFinal -> "video"
            ctLower?.contains("subtitle") == true || ctLower?.contains("vtt") == true -> "subtitle"
            else -> "unknown"
        }

        val cookie = CookieManager.getInstance().getCookie(url)

        val candidate = MediaCandidate(
            url = url,
            type = type,
            isManifest = isManifest,
            isSegment = isSegment,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            contentType = contentType,
            isProgressiveFinal = isProgressiveFinal
        )

        // Ad tracking
        if (isLikelyAd) {
            candidate.adScore += 50
            Log.d(TAG, "Candidate marked as AD: $url")
        }

        if (isPlaybackActive) {
            candidate.startedAfterPlayback = true
            candidate.playbackScore += 10
        }

        candidates[url] = candidate
        Log.d(TAG, "New Candidate Tracking: $url | type=$type | manifest=$isManifest")
        return candidate
    }

    private fun findParentManifestForSegment(segmentUrl: String): MediaCandidate? {
        // Advanced heuristic: Correlate segments to a parent manifest using host, path, referer, or existing tokens.
        try {
            val segUrlObj = URL(segmentUrl)
            val segPath = segUrlObj.path.substringBeforeLast("/")
            val segHost = segUrlObj.host

            var bestMatch: MediaCandidate? = null
            var bestScore = -1

            for ((candUrl, candidate) in candidates) {
                if (candidate.isManifest && candidate.adScore == 0) {
                    var matchScore = 0
                    val candUrlObj = URL(candUrl)
                    val candPath = candUrlObj.path.substringBeforeLast("/")

                    // Host + Path match
                    if (segHost == candUrlObj.host && segPath == candPath) {
                        matchScore += 10
                    } else if (segHost == candUrlObj.host) {
                        // Same host, maybe different path structure but same token/query?
                        matchScore += 5
                    }

                    if (matchScore > 0) {
                        if (matchScore > bestScore) {
                            bestScore = matchScore
                            bestMatch = candidate
                        }
                    }
                }
            }
            return bestMatch
        } catch (e: Exception) {
            // Ignore malformed URLs
        }
        return null
    }

    fun updateCandidateMSEActivity(url: String) {
        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.hasMSEActivity = true
            candidate.requestCount++
            Log.d(TAG, "MSE activity logged for: ${candidate.url}")
        } else {
            val c = processRequest(url, null, null)
            c?.hasMSEActivity = true
        }
    }

    fun markCandidateDRM(url: String) {
        if (url == "ACTIVE_PLAYER_DRM") {
            var candidate = candidates[url]
            if (candidate == null) {
                candidate = MediaCandidate(url = url, type = "drm_signal")
                candidates[url] = candidate
            }
            candidate.isDRMProtected = true
            Log.d(TAG, "DRM active signal detected for main player")
            return
        }
        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.isDRMProtected = true
            Log.d(TAG, "DRM detected for: ${candidate.url}")
        }
    }

    private fun findOrCreateSegmentGroup(url: String, referer: String?, userAgent: String?): MediaCandidate? {
        try {
            val urlObj = java.net.URL(url)
            val path = urlObj.path
            if (path.isEmpty()) return null

            val basePath = path.substringBeforeLast("/")
            val queryParams = urlObj.query?.split("&")?.map { it.substringBefore("=") }?.sorted()?.joinToString(",") ?: ""
            val groupingKey = "${urlObj.host}:$basePath?$queryParams"

            // Look for existing group
            val existingGroup = candidates.values.find { it.isSegmentGroup && it.pathBase == groupingKey }
            if (existingGroup != null) {
                return existingGroup
            }

            // Create new group
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            val groupCand = MediaCandidate(
                url = url, // Represents the group, though it's just the first segment's URL
                type = "segment_group",
                isSegment = true,
                isSegmentGroup = true,
                pathBase = groupingKey,
                referer = referer,
                userAgent = userAgent,
                cookie = cookie
            )
            groupCand.segmentUrls.add(url)
            candidates[groupCand.url] = groupCand // Using the first segment URL as the dictionary key for the group
            return groupCand
        } catch (e: Exception) {
            return null
        }
    }

    fun getBestCandidate(): MediaCandidate? {
        if (candidates.isEmpty()) return null

        // Filter out standalone segments and segment groups if we have actual manifests or progressive videos
        // Also strictly filter out blob: URLs from being considered valid playables
        val playables = candidates.values.filter {
            !it.url.startsWith("blob:") &&
            ((!it.isSegment && !it.isSegmentGroup) || ((it.isSegment || it.isSegmentGroup) && candidates.values.none { c -> c.isManifest || (c.type == "video" && !c.isSegmentGroup) }))
        }

        // Check for strict DMM false-positive avoidance: if we have DRM streams, filter out short low-req non-DRM streams
        val hasDRMPlayables = playables.any { it.isDRMProtected }
        if (hasDRMPlayables) {
            val drmFiltered = playables.filter { it.isDRMProtected || it.requestCount > 10 }
            val safePlayables = drmFiltered.filter { it.adScore == 0 || it.finalScore > 0 }
            if (safePlayables.isNotEmpty()) return safePlayables.maxByOrNull { it.finalScore }
        }

        // Remove high probability ads from the final playable selection entirely unless they are the ONLY thing available
        val safePlayables = playables.filter { it.adScore == 0 || it.finalScore > 0 }

        if (safePlayables.isEmpty()) return candidates.values.maxByOrNull { it.finalScore }

        return safePlayables.maxByOrNull { it.finalScore }
    }

    fun getCandidate(url: String): MediaCandidate? = candidates[url]

    fun logCandidatesState() {
        Log.d(TAG, "=== Current Candidates ===")
        candidates.values.forEach {
            Log.d(TAG, "Candidate: type=${it.type} manifest=${it.isManifest} reqs=${it.requestCount} afterPlay=${it.startedAfterPlayback} ad=${it.adScore} playScore=${it.playbackScore} FINAL=${it.finalScore} CONF=${it.confidence}\n URL: ${it.url}")
        }
        Log.d(TAG, "==========================")
    }

    private fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver",
            "scorecardresearch", "criteo", "outbrain", "taboola", "moatads", "advertising",
            "tiktokcdn", "ad-site"
        )
        val isAdKeyword = adKeywords.any { lowerUrl.contains(it) }

        val isImage = lowerUrl.endsWith(".image") || lowerUrl.endsWith(".jpg") ||
                      lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") ||
                      lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".webp")

        return isAdKeyword || isImage
    }
}
