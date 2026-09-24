import re

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Replace startYouTubeDownload
old_start = """    private fun startYouTubeDownload(title: String, option: YouTubeOption) {
        val intent = Intent(this, YouTubeDownloadService::class.java).apply {
            action = YouTubeDownloadService.ACTION_START_DOWNLOAD
            putExtra(YouTubeDownloadService.EXTRA_TITLE, title)

            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(webView.url)
            putExtra(YouTubeDownloadService.EXTRA_USER_AGENT, userAgent)
            if (cookie != null) putExtra(YouTubeDownloadService.EXTRA_COOKIE, cookie)
            putExtra(YouTubeDownloadService.EXTRA_REFERER, webView.url)"""

new_start = """    private fun startYouTubeDownload(title: String, option: YouTubeOption) {
        val intent = Intent(this, YouTubeDownloadService::class.java).apply {
            action = YouTubeDownloadService.ACTION_START_DOWNLOAD
            putExtra(YouTubeDownloadService.EXTRA_TITLE, title)

            val userAgent = webView.settings.userAgentString
            putExtra(YouTubeDownloadService.EXTRA_USER_AGENT, userAgent)
            putExtra(YouTubeDownloadService.EXTRA_REFERER, "https://www.youtube.com/")"""

content = content.replace(old_start, new_start)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
