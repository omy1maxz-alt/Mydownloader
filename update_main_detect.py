import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

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

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
