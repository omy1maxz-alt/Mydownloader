import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# We need to ensure that when launching the custom player, it does not spawn duplicates.
# We will explicitly add Intent.FLAG_ACTIVITY_SINGLE_TOP to all intents launched in launchCustomPlayer.
replacement = """    fun launchCustomPlayer(mediaFile: MediaFile) {
        val finalName = mediaFile.title
        if (mediaFile.url.contains("googlevideo.com") && (mediaFile.mimeType == "application/dash+xml" || mediaFile.mimeType == "application/x-mpegURL")) {
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
            // Need to fix launchPlayerWithCandidate inside MainActivity to also append SINGLE_TOP
            launchPlayerWithCandidate(exactCandidate, finalName, mediaFile.referer)
        } else {
            launchLegacyPlayer(mediaFile.url, finalName, mediaFile.referer, mediaFile.mimeType)
        }
    }"""

pattern = r'fun launchCustomPlayer\(mediaFile: MediaFile\).*?\n    \}'
match = re.search(pattern, content, re.DOTALL)
if match:
    new_content = content.replace(match.group(0), replacement.strip())
    with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
        f.write(new_content)
    print("Fixed launchCustomPlayer flags.")
else:
    print("Could not find launchCustomPlayer.")

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# Fix launchPlayerWithCandidate to include SINGLE_TOP
pattern2 = r'val intent = Intent\(this, CustomPlayerActivity::class\.java\)\.apply \{\n.*?addFlags\(Intent\.FLAG_ACTIVITY_CLEAR_TOP or Intent\.FLAG_ACTIVITY_NEW_TASK\)'
match2 = re.search(pattern2, content, re.DOTALL)
if match2:
    rep = match2.group(0).replace('addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)', 'addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)')
    content = content.replace(match2.group(0), rep)

# Fix launchLegacyPlayer to include SINGLE_TOP
pattern3 = r'val intent = Intent\(this@MainActivity, CustomPlayerActivity::class\.java\)\.apply \{\n.*?addFlags\(Intent\.FLAG_ACTIVITY_CLEAR_TOP or Intent\.FLAG_ACTIVITY_NEW_TASK\)'
match3 = re.search(pattern3, content, re.DOTALL)
if match3:
    rep = match3.group(0).replace('addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)', 'addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)')
    content = content.replace(match3.group(0), rep)

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
    f.write(content)

print("Added SINGLE_TOP to helper launchers.")
