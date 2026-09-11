package com.omymaxz.download

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.net.URL

class MediaDetectionEngine(private val context: Context) {

    private val candidates = mutableMapOf<String, MediaCandidate>()
    private val TAG = "MediaDetectionEngine"

    // Playback state tracker
    var isPlaybackActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                Log.d(TAG, "Playback active signal received.")
            }
        }

    fun clear() {
        candidates.clear()
        isPlaybackActive = false
    }

    fun processRequest(url: String, referer: String?, userAgent: String?, isFromAdBlocker: Boolean = false): MediaCandidate? {
        val cleanUrl = url.substringBefore('?')
        val lowerUrl = cleanUrl.lowercase()

        // Ad detection (just a signal, not a hard block unless absolutely certain, handled outside)
        val isLikelyAd = isAdUrl(lowerUrl)

        // Categorize
        val isHlsManifest = lowerUrl.endsWith(".m3u8")
        val isDashManifest = lowerUrl.endsWith(".mpd")
        val isProgressive = lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".mkv")

        val isHlsSegment = lowerUrl.endsWith(".ts")
        val isDashSegment = lowerUrl.endsWith(".m4s") || lowerUrl.endsWith(".m4f")
        val isManifest = isHlsManifest || isDashManifest || (lowerUrl.contains("manifest") && !isHlsSegment && !isDashSegment)
        val isSegment = isHlsSegment || isDashSegment

        if (!isManifest && !isSegment && !isProgressive && !url.contains("videoplayback")) {
            // Might be an extensionless video, but we need more evidence. We'll track it if it comes through `onMediaDetected`.
            // For now, if we are just looking at raw intercepted network traffic, we only track obvious media types
            // to avoid tracking thousands of useless image/json requests.
            if (!lowerUrl.contains("video") && !lowerUrl.contains("stream")) {
               return null
            }
        }

        // Try to associate segments with their parent manifest if they share a path
        if (isSegment) {
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
            }
        }

        // It's a new media entity or a standalone segment without a known manifest
        val existing = candidates[url]
        if (existing != null) {
            existing.requestCount++
            existing.lastSeenTime = System.currentTimeMillis()
            if (isPlaybackActive) existing.startedAfterPlayback = true
            return existing
        }

        val type = when {
            isManifest -> "manifest"
            isSegment -> "segment"
            isProgressive -> "video"
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
            cookie = cookie
        )

        if (isLikelyAd) candidate.adScore += 50
        if (isPlaybackActive) {
            candidate.startedAfterPlayback = true
            candidate.playbackScore += 10
        }

        candidates[url] = candidate
        Log.d(TAG, "New Candidate Tracking: $url | type=$type | manifest=$isManifest")
        return candidate
    }

    private fun findParentManifestForSegment(segmentUrl: String): MediaCandidate? {
        // Simple heuristic: Does the segment share a directory path with a known manifest?
        try {
            val segUrlObj = URL(segmentUrl)
            val segPath = segUrlObj.path.substringBeforeLast("/")

            for ((candUrl, candidate) in candidates) {
                if (candidate.isManifest) {
                    val candUrlObj = URL(candUrl)
                    val candPath = candUrlObj.path.substringBeforeLast("/")
                    if (segUrlObj.host == candUrlObj.host && segPath == candPath) {
                        return candidate
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore malformed URLs
        }
        return null
    }

    fun getBestCandidate(): MediaCandidate? {
        if (candidates.isEmpty()) return null

        // Filter out standalone segments if we have actual manifests or progressive videos
        val playables = candidates.values.filter { !it.isSegment || (it.isSegment && candidates.values.none { c -> c.isManifest }) }

        if (playables.isEmpty()) return candidates.values.maxByOrNull { it.finalScore }

        return playables.maxByOrNull { it.finalScore }
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
            "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver"
        )
        return adKeywords.any { lowerUrl.contains(it) }
    }
}
