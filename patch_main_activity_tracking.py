import re

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Make sure isMediaUrl drops css, gif, js
is_media_url_search = "return cleanUrl.endsWith(\".mp4\") || cleanUrl.endsWith(\".mkv\") || cleanUrl.endsWith(\".webm\") || cleanUrl.endsWith(\".vtt\") || cleanUrl.endsWith(\".srt\") || lower.contains(\"videoplayback\")"
is_media_url_replace = """val ext = cleanUrl.substringAfterLast('.', "")
        val nonMediaExtensions = setOf("css", "js", "gif", "jpg", "jpeg", "png", "svg", "webp", "woff", "woff2", "ttf", "ico", "html", "htm")
        if (nonMediaExtensions.contains(ext)) return false

        return cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".vtt") || cleanUrl.endsWith(".srt") || lower.contains("videoplayback")"""

content = content.replace(is_media_url_search, is_media_url_replace)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
