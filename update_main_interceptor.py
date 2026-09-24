import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

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

print("Done")
