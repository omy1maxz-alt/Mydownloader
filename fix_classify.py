import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_classify = """    private fun classifyMedia(rawUrl: String, contentType: String?): MediaKind {
        val lowerUrl = rawUrl.lowercase()
        val mime = contentType?.substringBefore(";")?.trim()?.lowercase().orEmpty()

        val isHls = lowerUrl.contains(".m3u8") || lowerUrl.contains("format=m3u8") || lowerUrl.contains("type=hls") || mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl"
        val isDash = lowerUrl.contains(".mpd") || lowerUrl.contains("format=dash") || lowerUrl.contains("type=dash") || mime == "application/dash+xml"
        val isVideoMime = mime.startsWith("video/")
        val isProgressive = lowerUrl.matches(Regex(".*\\.(mp4|webm|mkv|mov|avi)(\\?.*)?$")) || isVideoMime
        val isSegment = lowerUrl.matches(Regex(".*\\.(ts|m4s|cmfv|cmfa|aac|mp4)(\\?.*)?$")) && !isProgressive

        return when {"""

new_classify = """    private fun classifyMedia(rawUrl: String, contentType: String?): MediaKind {
        val lowerUrl = rawUrl.lowercase()
        val mime = contentType?.substringBefore(";")?.trim()?.lowercase().orEmpty()

        // Exclude trackers and images masquerading as manifests
        if (lowerUrl.contains(".gif") || lowerUrl.contains(".png") || lowerUrl.contains("/ping") ||
            lowerUrl.contains("/pixel/") || lowerUrl.contains("/analytics/")) {
            return MediaKind.UNKNOWN
        }

        val isHls = lowerUrl.contains(".m3u8") || lowerUrl.contains("format=m3u8") || lowerUrl.contains("type=hls") ||
                    mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl" ||
                    lowerUrl.contains("/master.txt") || lowerUrl.contains("/hls/") || lowerUrl.contains("/hls3/")

        val isDash = lowerUrl.contains(".mpd") || lowerUrl.contains("format=dash") || lowerUrl.contains("type=dash") || mime == "application/dash+xml"
        val isVideoMime = mime.startsWith("video/")
        val isProgressive = lowerUrl.matches(Regex(".*\\.(mp4|webm|mkv|mov|avi)(\\?.*)?$")) || isVideoMime
        val isSegment = lowerUrl.matches(Regex(".*\\.(ts|m4s|cmfv|cmfa|aac|mp4)(\\?.*)?$")) && !isProgressive

        return when {"""

content = content.replace(old_classify, new_classify)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
