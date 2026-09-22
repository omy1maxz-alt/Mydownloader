package com.omymaxz.download

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.net.URL

class MediaDetectionEngine(private val context: Context) {

    private fun classifyMedia(rawUrl: String, contentType: String?): MediaKind {
        val lowerUrl = rawUrl.lowercase()
        val mime = contentType?.substringBefore(";")?.trim()?.lowercase().orEmpty()

        val isHls = lowerUrl.contains(".m3u8") || lowerUrl.contains("format=m3u8") || lowerUrl.contains("type=hls") || mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl"
        val isDash = lowerUrl.contains(".mpd") || lowerUrl.contains("format=dash") || lowerUrl.contains("type=dash") || mime == "application/dash+xml"
        val isVideoMime = mime.startsWith("video/")
        val isProgressive = lowerUrl.matches(Regex(".*\\.(mp4|webm|mkv|mov|avi)(\\?.*)?$")) || isVideoMime
        val isSegment = lowerUrl.matches(Regex(".*\\.(ts|m4s|cmfv|cmfa|aac|mp4)(\\?.*)?$")) && !isProgressive

        return when {
            isHls -> MediaKind.HLS_MANIFEST
            isDash -> MediaKind.DASH_MANIFEST
            isSegment -> MediaKind.SEGMENT
            isProgressive -> MediaKind.PROGRESSIVE
            else -> MediaKind.UNKNOWN
        }
    }


    private fun buildMediaGroupingKey(rawUrl: String): String {
        return try {
            val uri = java.net.URL(rawUrl)
            val path = uri.path.lowercase().substringBeforeLast("/", "")

            val queryKeys = uri.query
                ?.split("&")
                ?.mapNotNull { part ->
                    val key = part.substringBefore("=").lowercase()
                    if (key.isNotBlank()) key else null
                }
                ?.filterNot {
                    it in setOf("token", "signature", "sig", "expires", "expires_at", "hdntl", "auth_key", "hash")
                }
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()

            "${uri.host.lowercase()}|$path|$queryKeys"
        } catch (_: Exception) {
            rawUrl
        }
    }


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

        val mediaKind = classifyMedia(url, contentType)

        if (mediaKind == MediaKind.UNKNOWN) {
            val lowerUrl = url.lowercase()
            val hasEvidencePath = lowerUrl.contains("/video") || lowerUrl.contains("/stream") || lowerUrl.contains("/play") ||
                                  lowerUrl.contains("/vod") || lowerUrl.contains("/media") || lowerUrl.contains("/movie") ||
                                  lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || lowerUrl.contains("/segment") ||
                                  lowerUrl.contains("?sub=") || lowerUrl.contains("/subtitle") || lowerUrl.contains("/caption")
            if (!hasEvidencePath && contentType == null && !url.contains("videoplayback")) {
                return null
            }
        }

        var isManifest = mediaKind == MediaKind.HLS_MANIFEST || mediaKind == MediaKind.DASH_MANIFEST
        var isSegment = mediaKind == MediaKind.SEGMENT
        var isProgressiveFinal = mediaKind == MediaKind.PROGRESSIVE

        // Check for ad URL signals
        val isLikelyAd = isAdUrl(url)

        // Image blocking specifically for thumbnail segments masquerading as media
        if (!isManifest && !isProgressiveFinal && isLikelyAd) {
             isSegment = false
        }

        if (!isManifest && !isProgressiveFinal && !isSegment && mediaKind == MediaKind.UNKNOWN) return null

        val type = when (mediaKind) {
            MediaKind.HLS_MANIFEST -> "hls"
            MediaKind.DASH_MANIFEST -> "dash"
            MediaKind.PROGRESSIVE -> "video"
            MediaKind.SEGMENT -> "segment"
            else -> "unknown"
        }

        // Try to associate segments with their parent manifest if they share a path
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
            } else {
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

        val existing = candidates[url]
        if (existing != null) {
            existing.requestCount++
            existing.lastSeenTime = System.currentTimeMillis()
            if (isPlaybackActive) {
                existing.startedAfterPlayback = true
            }
            return existing
        }

        val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
        val candidate = MediaCandidate(
            url = url,
            type = type,
            mediaKind = mediaKind,
            isManifest = isManifest,
            isSegment = isSegment,
            isProgressiveFinal = isProgressiveFinal,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            contentType = contentType
        )

        applyAdPenalty(candidate)
        candidates[url] = candidate

        if (isPlaybackActive) {
            candidate.startedAfterPlayback = true
        }

        Log.d(TAG, "New Candidate Tracking: $url | type=$type | manifest=$isManifest")
        return candidate
    }

    private fun findParentManifestForSegment(segmentUrl: String): MediaCandidate? {
        // Advanced heuristic: Correlate segments to a parent manifest using host, path, referer, or existing tokens.
        try {
            val segKey = buildMediaGroupingKey(segmentUrl)
            val segUrlObj = URL(segmentUrl)
            val segHost = segUrlObj.host

            var bestMatch: MediaCandidate? = null
            var bestScore = -1

            for ((candUrl, candidate) in candidates) {
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
            }
            return bestMatch
        } catch (e: Exception) {
            // Ignore malformed URLs
        }
        return null
    }

    fun updatePlayerTelemetry(url: String, telemetry: PlayerTelemetry) {
        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.telemetry = telemetry
            if (telemetry.durationSec > 0) candidate.durationSec = telemetry.durationSec.toInt()
            if (!telemetry.paused) candidate.isActivePlayer = true
        }
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

    fun markCandidateAsActivePlayer(url: String, duration: Int) {
        val candidate = candidates[url] ?: findParentManifestForSegment(url)
        if (candidate != null) {
            candidate.isActivePlayer = true
            if (duration > 0) candidate.durationSec = duration
            Log.d(TAG, "Active player associated with candidate: ${candidate.url} (duration: $duration)")
        } else {
            val c = processRequest(url, null, null)
            c?.isActivePlayer = true
            if (duration > 0) c?.durationSec = duration
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

            val groupingKey = buildMediaGroupingKey(url)

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
            groupCand.mediaKind = MediaKind.SEGMENT
            candidates[groupCand.url] = groupCand // Using the first segment URL as the dictionary key for the group
            return groupCand
        } catch (e: Exception) {
            return null
        }
    }

    fun getBestCandidate(): MediaCandidate? {
        logCandidatesState()
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

        return safePlayables.maxByOrNull { it.finalScore }
    }

    fun getCandidate(url: String): MediaCandidate? = candidates[url]

    fun logCandidatesState() {
        Log.d(TAG, "[MEDIA_SELECTION] === Current Candidates ===")
        var i = 1
        candidates.values.sortedByDescending { it.finalScore }.forEach {
            Log.d(TAG, "[MEDIA_SELECTION] $i.\nurl=${it.url}\ntype=${it.type}\nmanifest=${it.isManifest}\nduration=${it.durationSec}s\nrequestCount=${it.requestCount}\nactivePlayer=${it.isActivePlayer}\nafterPlayback=${it.startedAfterPlayback}\nmse=${it.hasMSEActivity}\nadScore=${it.adScore}\nplaybackScore=${it.playbackScore}\nfinalScore=${it.finalScore}\nconfidence=${it.confidence}\n")
            i++
        }
        Log.d(TAG, "[MEDIA_SELECTION] ==========================")
    }

    fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()

        val isImage = lowerUrl.endsWith(".image") || lowerUrl.endsWith(".jpg") ||
                      lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") ||
                      lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".webp")
        if (isImage) return true

        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver",
            "scorecardresearch", "criteo", "outbrain", "taboola", "moatads", "advertising",
            "tiktokcdn", "ad-site"
        )

        return adKeywords.any { lowerUrl.contains(it) }
    }

    private fun applyAdPenalty(candidate: MediaCandidate) {
        if (isAdUrl(candidate.url)) {
            candidate.adScore += 50
        }
    }

}
