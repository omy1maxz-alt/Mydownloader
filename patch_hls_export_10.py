with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# We need to make sure we also protect `muxToMp4WithTransformer(mediaItem, title)` from being called in PATH_C_DOWNLOAD_ID if it's a YouTube HLS.
# Let's see:
# if (download.state == Download.STATE_COMPLETED) {
#   ...
#   val url = download.request.uri.toString()
#   val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
#   val isYouTubeHls = url.contains("googlevideo.com") && url.contains(".m3u8", ignoreCase = true)
#   val hasSplitStreamKeys = streamKeysStr.size > 1
#   if (isYouTubeHls && hasSplitStreamKeys) {
#       // Fall directly to muxToMp4FromCache
#   } else {
#       muxToMp4WithTransformer...
#   }

path_c_original = """            try {
                muxToMp4WithTransformer(mediaItem, title)
            } catch (e: Exception) {
                writeExportLog("Transformer failed on downloaded item, falling back to muxToMp4FromCache: ${e.message}")
                val url = download.request.uri.toString()
                val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }

                try {
                    if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                        copyMp4FromCache(url, title)
                    } else {
                        muxToMp4FromCache(url, streamKeysStr, title)
                    }"""

path_c_new = """            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            val isYouTubeHls = url.contains("googlevideo.com") && url.contains(".m3u8", ignoreCase = true)
            val hasSplitStreamKeys = streamKeysStr.size > 1

            try {
                if (isYouTubeHls && hasSplitStreamKeys) {
                    writeExportLog("Transformer skipped for split YouTube HLS from PATH_C. Falling back to FFmpeg cache export.")
                    throw Exception("Skipping Transformer for YouTube split stream")
                }
                muxToMp4WithTransformer(mediaItem, title)
            } catch (e: Exception) {
                writeExportLog("Transformer failed or skipped on downloaded item, falling back to muxToMp4FromCache: ${e.message}")

                try {
                    if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                        copyMp4FromCache(url, title)
                    } else {
                        muxToMp4FromCache(url, streamKeysStr, title)
                    }"""

content = content.replace(path_c_original, path_c_new)

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "w") as f:
    f.write(content)
