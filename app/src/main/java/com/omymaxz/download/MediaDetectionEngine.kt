package com.omymaxz.download

import android.os.Handler
import android.os.Looper
import android.util.Log

class MediaDetectionEngine(private val callback: (MediaCandidate) -> Unit) {
    private val TAG = "MediaDetectionEngine"
    private val candidates = mutableMapOf<String, MediaCandidate>()
    private var isPlaybackActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectionRunnable: Runnable? = null
    private val DEBOUNCE_MS = 3500L

    // Maintain a map of active segments back to their presumed manifest
    private var currentActiveManifestUrl: String? = null

    fun notifyPlaybackStarted() {
        Log.d(TAG, "User playback initialized.")
        isPlaybackActive = true
    }

    fun observeRequest(
        url: String,
        pageUrl: String,
        mimeType: String? = null,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null
    ) {
        val lowerUrl = url.lowercase()
        val cleanUrl = lowerUrl.substringBefore('?')

        // 1. Identify Ad Trackers (Drop completely or flag)
        val isAd = isAdDomain(lowerUrl)
        if (isAd) {
            Log.d(TAG, "Ignoring obvious ad request: $url")
            return
        }

        // 2. Classify the request type
        val isManifest = cleanUrl.endsWith(".m3u8") || cleanUrl.endsWith(".mpd") || lowerUrl.contains("manifest")
        val isSegment = cleanUrl.endsWith(".ts") || cleanUrl.endsWith(".m4s") || cleanUrl.endsWith(".aac")
        val isDirectFile = cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".mkv") || lowerUrl.contains("videoplayback")

        if (!isManifest && !isSegment && !isDirectFile) {
            // Not a known media format
            return
        }

        // 3. Process Request
        synchronized(candidates) {
            if (isSegment) {
                // If a segment is requested, it proves the CURRENT manifest is the actively playing stream
                currentActiveManifestUrl?.let { manifestUrl ->
                    val manifestCandidate = candidates[manifestUrl]
                    if (manifestCandidate != null) {
                        manifestCandidate.requestCount++
                        manifestCandidate.lastSeen = System.currentTimeMillis()
                        manifestCandidate.score += 2
                        Log.d(TAG, "Segment observed. Boosting active manifest: $manifestUrl (Score: ${manifestCandidate.score})")
                        scheduleSelection()
                    }
                }
                return // We don't want to list the segment itself as a playable candidate
            }

            val existing = candidates[url]
            if (existing != null) {
                existing.requestCount++
                existing.lastSeen = System.currentTimeMillis()
                if (existing.isManifest || existing.type == "video/mp4") {
                    currentActiveManifestUrl = url
                    scheduleSelection()
                }
            } else {
                val candidate = MediaCandidate(
                    url = url,
                    type = mimeType ?: if (isManifest) "manifest" else "video",
                    isManifest = isManifest,
                    isSegment = isSegment,
                    isAd = false,
                    pageUrl = pageUrl,
                    referer = referer,
                    userAgent = userAgent,
                    cookie = cookie,
                    startedAfterPlayback = isPlaybackActive
                )

                // Base Scoring
                if (isManifest) candidate.score += 10
                if (isDirectFile) candidate.score += 5
                if (isPlaybackActive) candidate.score += 15 // High value for things requested exactly when play is pressed
                if (lowerUrl.contains("master") || lowerUrl.contains("index")) candidate.score += 5

                candidates[url] = candidate
                Log.d(TAG, "New Candidate Found: $url (Score: ${candidate.score})")

                if (isManifest || isDirectFile) {
                    currentActiveManifestUrl = url
                    scheduleSelection()
                }
            }
        }
    }

    private fun isAdDomain(url: String): Boolean {
        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "ad.", "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver",
            "googleads.", "doubleclick.net", "adsystem"
        )
        return adKeywords.any { url.contains(it) }
    }

    private fun scheduleSelection() {
        selectionRunnable?.let { mainHandler.removeCallbacks(it) }
        selectionRunnable = Runnable { evaluateAndSelect() }
        mainHandler.postDelayed(selectionRunnable!!, DEBOUNCE_MS)
    }

    private fun evaluateAndSelect() {
        synchronized(candidates) {
            val bestCandidate = candidates.values
                .filter { !it.isAd && !it.isSegment }
                .maxByOrNull { it.score + (it.getConfidence() * 100).toInt() }

            if (bestCandidate != null && bestCandidate.getConfidence() >= 0.3f) {
                Log.d(TAG, "Selected MAIN VIDEO: ${bestCandidate.url} | Score: ${bestCandidate.score} | Confidence: ${bestCandidate.getConfidence()}")
                callback(bestCandidate)
            } else {
                Log.d(TAG, "No candidate reached high confidence yet. Still observing...")
            }
        }
    }

    fun clear() {
        synchronized(candidates) {
            candidates.clear()
            currentActiveManifestUrl = null
            isPlaybackActive = false
            selectionRunnable?.let { mainHandler.removeCallbacks(it) }
        }
    }
}
