import re

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# 1. Update classifyMedia to use strict segment matching and reject non-media
strict_segment_search = """val isSegment = lowerUrl.matches(Regex(".*\\\\.(ts|m4s|cmfv|cmfa|aac|mp4)(\\\\?.*)?$")) && !isProgressive"""
strict_segment_replace = """val cleanUrl = lowerUrl.substringBefore('?')
        val ext = cleanUrl.substringAfterLast('.', "").lowercase()
        val nonMediaExtensions = setOf("css", "js", "gif", "jpg", "jpeg", "png", "svg", "webp", "woff", "woff2", "ttf", "ico", "html", "htm")
        if (nonMediaExtensions.contains(ext)) return MediaKind.UNKNOWN

        val isSegmentRegex = cleanUrl.endsWith(".ts", ignoreCase = true) ||
                             cleanUrl.endsWith(".m4s", ignoreCase = true) ||
                             cleanUrl.endsWith(".mp4", ignoreCase = true) ||
                             cleanUrl.matches(Regex(".*/(seg|segment|chunk|fragment)[-_]?\\\\d+(\\\\.[a-z0-9]+)?$"))

        val isSegment = isSegmentRegex && !isProgressive && !lowerUrl.contains("/tracking/") && !lowerUrl.contains("/analytics/")"""

content = content.replace(strict_segment_search, strict_segment_replace)

# 2. Add strict ad/tracking domain rejection
ad_url_search = """val isImage = lowerUrl.endsWith(".image") || lowerUrl.endsWith(".jpg") ||"""
ad_url_replace = """if (lowerUrl.contains("/tracking/") || lowerUrl.contains("/analytics/") || lowerUrl.contains("/pixel") ||
            lowerUrl.contains("/beacon") || lowerUrl.contains("/event?") || lowerUrl.contains("/count?") ||
            lowerUrl.contains("google-analytics") || lowerUrl.contains("doubleclick") ||
            lowerUrl.contains("newshinyd.com") || lowerUrl.contains("yetansd.com") ||
            lowerUrl.contains("playhubconnect.com") || lowerUrl.contains("bkcdn.net") ||
            lowerUrl.contains("5fll5qac.xyz") || lowerUrl.contains("trailerhg.xyz")) {
            return true
        }

        val isImage = lowerUrl.endsWith(".image") || lowerUrl.endsWith(".jpg") ||"""

content = content.replace(ad_url_search, ad_url_replace)

# 3. Prevent hasEvidencePath from matching trackers
evidence_search = """lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || lowerUrl.contains("/segment") ||"""
evidence_replace = """lowerUrl.contains("/hls") || lowerUrl.contains("/dash") || (lowerUrl.contains("/segment") && !lowerUrl.contains("/tracking/")) ||"""

content = content.replace(evidence_search, evidence_replace)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)
