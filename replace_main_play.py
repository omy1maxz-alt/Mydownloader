import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# Let's add the launchCustomPlayer method to MainActivity

replacement = """
    fun launchCustomPlayer(mediaFile: MediaFile) {
        val finalName = mediaFile.title
        if (mediaFile.url.contains("googlevideo.com") && (mediaFile.mimeType == "application/dash+xml" || mediaFile.mimeType == "application/x-mpegURL")) {
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (mediaFile.mimeType != null) {
                    putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                }
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                val refererToUse = mediaFile.referer ?: webView.url
                putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
            }
            startActivity(intent)
            return
        }

        if (mediaFile.url.contains("youtube.com") || mediaFile.url.contains("youtu.be") || mediaFile.url.contains("googlevideo.com") || mediaFile.url.contains("manifest/dash") || mediaFile.mimeType == "application/dash+xml") {
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (mediaFile.mimeType != null) {
                    putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                }
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                val refererToUse = mediaFile.referer ?: webView.url
                putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
            }
            startActivity(intent)
            return
        }

        val exactCandidate = mediaEngine.candidates[mediaFile.url]
        if (exactCandidate != null) {
            launchPlayerWithCandidate(exactCandidate, finalName, mediaFile.referer)
        } else {
            launchLegacyPlayer(mediaFile.url, finalName, mediaFile.referer, mediaFile.mimeType)
        }
    }
"""

# Insert it before private fun showRenameDialog
insert_idx = content.find("private fun showRenameDialog")
if insert_idx != -1:
    content = content[:insert_idx] + replacement + "\n" + content[insert_idx:]
    with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
        f.write(content)
    print("Added launchCustomPlayer.")
else:
    print("Failed to find injection point.")
