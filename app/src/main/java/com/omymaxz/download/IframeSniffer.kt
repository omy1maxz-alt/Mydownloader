package com.omymaxz.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class IframeSniffer(private val context: Context, private val onMediaFound: (String) -> Unit) {

    private var hiddenWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // Candidate tracking
    private val candidates = mutableMapOf<String, Int>() // URL -> Score
    private var selectionRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    private val DEBOUNCE_DELAY_MS = 3000L // Wait 3 seconds after last good candidate
    private val MAX_TIMEOUT_MS = 15000L   // Absolute max time to sniff

    fun sniff(url: String) {
        mainHandler.post {
            // CRITICAL FIX: Reset state and cancel previous timers before starting a new sniff
            cleanupInternal()
            isDestroyed = false
            candidates.clear()
            selectionRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

            hiddenWebView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                        if (isMediaUrl(reqUrl)) {
                            if (isAdUrl(reqUrl)) {
                                Log.d("IframeSniffer", "Rejected AD URL: $reqUrl")
                            } else {
                                Log.d("IframeSniffer", "Candidate found: $reqUrl")
                                addCandidate(reqUrl)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                loadUrl(url)
            }

            // Absolute timeout fallback
            timeoutRunnable = Runnable {
                Log.d("IframeSniffer", "Max timeout reached. Selecting best candidate.")
                selectBestAndFinish()
            }
            mainHandler.postDelayed(timeoutRunnable!!, MAX_TIMEOUT_MS)
        }
    }

    // Call this if the user manually triggers "Play in App" before the debounce finishes
    fun forceSelection() {
        mainHandler.post { selectBestAndFinish() }
    }

    private fun addCandidate(url: String) {
        mainHandler.post {
            if (isDestroyed) return@post

            var score = 0
            val lowerUrl = url.lowercase()

            // Base score for media
            if (lowerUrl.contains(".m3u8")) score += 10
            if (lowerUrl.contains(".mp4")) score += 5

            // Bonus for likely main video patterns
            if (lowerUrl.contains("master") || lowerUrl.contains("index") || lowerUrl.contains("playlist")) score += 5

            candidates[url] = (candidates[url] ?: 0) + score
            Log.d("IframeSniffer", "Scored $url -> ${candidates[url]}")

            // Reset debounce timer
            selectionRunnable?.let { mainHandler.removeCallbacks(it) }
            selectionRunnable = Runnable { selectBestAndFinish() }
            mainHandler.postDelayed(selectionRunnable!!, DEBOUNCE_DELAY_MS)
        }
    }

    private fun selectBestAndFinish() {
        if (isDestroyed) return
        isDestroyed = true // Prevent double-firing

        selectionRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }

        val bestUrl = candidates.maxByOrNull { it.value }?.key
        if (bestUrl != null) {
            Log.d("IframeSniffer", "Selected MAIN video: $bestUrl with score ${candidates[bestUrl]}")
            onMediaFound(bestUrl)
        } else {
            Log.d("IframeSniffer", "No valid candidates found.")
        }
        cleanupInternal()
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".m4s")
    }

    private fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "ad.", "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver"
        )
        return adKeywords.any { lowerUrl.contains(it) }
    }

    private fun cleanupInternal() {
        // Must be called on MainThread
        hiddenWebView?.stopLoading()
        hiddenWebView?.loadUrl("about:blank")
        hiddenWebView?.clearHistory()
        hiddenWebView?.removeAllViews()
        hiddenWebView?.destroy()
        hiddenWebView = null
    }

    fun destroy() {
        mainHandler.post {
            isDestroyed = true
            selectionRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            cleanupInternal()
        }
    }
}
