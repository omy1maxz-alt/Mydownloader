import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Make sure the fullscreenDownloadButton logic is indeed there.
old_show = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.addView(fullscreenView)



                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior"""

new_show = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.addView(fullscreenView)

                    // Add floating buttons back for WebMedia Fullscreen
                    fullscreenDownloadButton = android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_save)
                        setBackgroundResource(R.drawable.rounded_background)
                        setPadding(20, 20, 20, 20)
                        setOnClickListener {
                            showMediaListDialog()
                        }
                    }
                    val btnParams = android.widget.FrameLayout.LayoutParams(140, 140).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = 150
                        marginEnd = 50
                    }
                    decorView.addView(fullscreenDownloadButton, btnParams)

                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior"""

if old_show in content:
    content = content.replace(old_show, new_show)

old_hide = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""

new_hide = """                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    if (fullscreenDownloadButton != null) {
                        decorView.removeView(fullscreenDownloadButton)
                        fullscreenDownloadButton = null
                    }

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)"""

if old_hide in content:
    content = content.replace(old_hide, new_hide)

# Fix onDownloadActiveMedia
old_onDownloadActiveMedia = """        @JavascriptInterface
        fun onDownloadActiveMedia(url: String, type: String, title: String, subtitleUrl: String) {
            activity.runOnUiThread {
                var finalUrl = url
                if (url.startsWith("blob:")) {
                    val mainMedia = synchronized(activity.detectedMediaFiles) {
                        activity.detectedMediaFiles.firstOrNull { it.isMainContent && !it.url.startsWith("blob:") }
                            ?: activity.detectedMediaFiles.firstOrNull { !it.url.startsWith("blob:") && it.category == MediaCategory.VIDEO }
                    }
                    if (mainMedia != null) {
                        finalUrl = mainMedia.url
                    }
                }"""

new_onDownloadActiveMedia = """        @JavascriptInterface
        fun onDownloadActiveMedia(url: String, type: String, title: String, subtitleUrl: String) {
            activity.runOnUiThread {
                var finalUrl = url
                if (url.startsWith("blob:")) {
                    val bestCand = activity.mediaEngine.getBestCandidate()
                    if (bestCand != null && !bestCand.url.startsWith("blob:")) {
                        finalUrl = bestCand.url
                    } else {
                        val mainMedia = synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.firstOrNull { it.isMainContent && !it.url.startsWith("blob:") }
                                ?: activity.detectedMediaFiles.firstOrNull { !it.url.startsWith("blob:") && it.category == MediaCategory.VIDEO }
                        }
                        if (mainMedia != null) {
                            finalUrl = mainMedia.url
                        }
                    }
                }"""
content = content.replace(old_onDownloadActiveMedia, new_onDownloadActiveMedia)

old_onActiveMediaFound = """        @JavascriptInterface
        fun onActiveMediaFound(url: String, type: String, isBlob: Boolean) {
            activity.runOnUiThread {
                var bestPlayable = activity.mediaEngine.getBestCandidate()

                // If it's not a blob and we have a direct active URL, let's process it heavily
                if (!isBlob && url.isNotEmpty() && !url.startsWith("data:")) {
                    activity.mediaEngine.markCandidateAsActivePlayer(url, 0)
                    val activeCand = activity.mediaEngine.getCandidate(url)
                    if (activeCand != null) {
                        activeCand.playbackScore += 100 // Absolute highest priority
                        activeCand.referer = activity.lastUsedUrl
                        activeCand.userAgent = activity.cachedUserAgent
                        bestPlayable = activity.mediaEngine.getBestCandidate()
                    }
                }

                if (bestPlayable != null) {"""

new_onActiveMediaFound = """        @JavascriptInterface
        fun onActiveMediaFound(url: String, type: String, isBlob: Boolean) {
            activity.runOnUiThread {
                var bestPlayable = activity.mediaEngine.getBestCandidate()

                // If it's not a blob and we have a direct active URL, let's process it heavily
                if (!isBlob && url.isNotEmpty() && !url.startsWith("data:")) {
                    activity.mediaEngine.markCandidateAsActivePlayer(url, 0)
                    val activeCand = activity.mediaEngine.getCandidate(url)
                    if (activeCand != null) {
                        activeCand.playbackScore += 100 // Absolute highest priority
                        activeCand.referer = activity.lastUsedUrl
                        activeCand.userAgent = activity.cachedUserAgent
                        bestPlayable = activity.mediaEngine.getBestCandidate()
                    }
                } else if (isBlob) {
                     // If it IS a blob, we just trust the MediaDetectionEngine to have mapped the blob active state to the manifest
                     bestPlayable = activity.mediaEngine.getBestCandidate()
                }

                if (bestPlayable != null) {"""
content = content.replace(old_onActiveMediaFound, new_onActiveMediaFound)

old_onMediaDetected = """                        // Hook for detecting upstream network requests bypassing JS blobs.
                        // We register this internally so `CustomPlayerActivity` can use it when playing active streams.
                        if (url.contains(".m3u8", ignoreCase = true) || url.endsWith(".mp4") || url.contains("videoplayback")) {
                            currentVideoUrl = url
                            runOnUiThread {
                                // DO NOT CALL GETURL() HERE. It will crash. Just run JS eval.
                                webView.evaluateJavascript("if (window.AndroidMediaState && window.AndroidMediaState.onMediaDetected) { window.AndroidMediaState.onMediaDetected('$url', 'video'); }", null)
                            }
                        }"""

new_onMediaDetected = """                        // Hook for detecting upstream network requests bypassing JS blobs.
                        // We register this internally so `CustomPlayerActivity` can use it when playing active streams.
                        if (url.contains(".m3u8", ignoreCase = true) || url.endsWith(".mp4") || url.contains("videoplayback")) {
                            currentVideoUrl = url
                            runOnUiThread {
                                // Map back through our advanced MediaDetectionEngine to check if we should actually show this in UI
                                val candidate = mediaEngine.getCandidate(url)
                                if (candidate != null && (candidate.adScore > 0 || candidate.finalScore < 0)) {
                                    // Skip adding to UI if engine strongly thinks it's an ad
                                } else {
                                    webView.evaluateJavascript("if (window.AndroidMediaState && window.AndroidMediaState.onMediaDetected) { window.AndroidMediaState.onMediaDetected('$url', 'video'); }", null)
                                }
                            }
                        }"""
content = content.replace(old_onMediaDetected, new_onMediaDetected)

old_dedupe = """                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url }
                            }"""

new_dedupe = """                            val candidate = mediaEngine.getCandidate(url)
                            if (candidate != null && (candidate.adScore > 0 || candidate.finalScore < 0)) {
                                return super.shouldInterceptRequest(view, request)
                            }

                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                            }"""
content = content.replace(old_dedupe, new_dedupe)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Reapplied fullscreenDownloadButton!")
