import re

filepath = 'app/src/main/java/com/omymaxz/download/MainActivity.kt'

with open(filepath, 'r') as f:
    content = f.read()

# Replace onBase64IframeFound content
search_str = """        @JavascriptInterface
        fun onBase64IframeFound(base64Str: String) {
            try {
                val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                val decodedString = String(decodedBytes)
                // Match <iframe src="URL" ...>
                val regex = Regex("<iframe[^>]+src=[\\"']([^\\"']+)[\"'][^>]*>")
                val match = regex.find(decodedString)
                if (match != null) {
                    val iframeUrl = match.groupValues[1]
                    activity.runOnUiThread {
                        activity.binding.webView.loadUrl(iframeUrl)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaStateInterface", "Error decoding Base64 iframe: ${e.message}")
            }
        }"""

replace_str = """        @JavascriptInterface
        fun onBase64IframeFound(base64Str: String) {
            try {
                val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                val decodedString = String(decodedBytes)
                // Match <iframe src="URL" ...>
                val regex = Regex("<iframe[^>]+src=[\\"']([^\\"']+)[\"'][^>]*>")
                val match = regex.find(decodedString)
                if (match != null) {
                    val iframeUrl = match.groupValues[1]
                    activity.runOnUiThread {
                        IframeSniffer(activity) { url ->
                            // Process the found media URL exactly like the main interceptor
                            activity.runOnUiThread {
                                try {
                                    val category = MediaCategory.fromUrl(url)
                                    val isMainContent = isMainVideoContent(url)
                                    if (category == MediaCategory.VIDEO && isMainContent) {
                                        currentVideoUrl = url
                                    }
                                    val detectedFormat = detectVideoFormat(url)
                                    val quality = extractQualityFromUrl(url)
                                    val enhancedTitle = generateSmartFileName(url, detectedFormat.extension, quality, category)
                                    val fileSize = estimateFileSize(url, category)
                                    val language = extractLanguageFromUrl(url)
                                    val mediaFile = MediaFile(
                                        url = url,
                                        title = enhancedTitle,
                                        mimeType = detectedFormat.mimeType,
                                        fileSize = fileSize,
                                        language = language,
                                        isMainContent = isMainContent
                                    )
                                    val existsAlready = synchronized(detectedMediaFiles) {
                                        detectedMediaFiles.any { it.url == url }
                                    }
                                    if (!existsAlready) {
                                        synchronized(detectedMediaFiles) {
                                            detectedMediaFiles.add(mediaFile)
                                        }
                                        updateFabVisibility()
                                        if (category == MediaCategory.SUBTITLE) {
                                            fetchSubtitleSnippet(mediaFile)
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error processing sniffed media URL: ${e.message}")
                                }
                            }
                        }.sniff(iframeUrl)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaStateInterface", "Error decoding Base64 iframe: ${e.message}")
            }
        }"""

if search_str in content:
    content = content.replace(search_str, replace_str)
    with open(filepath, 'w') as f:
        f.write(content)
    print("Success")
else:
    print("Search string not found")
