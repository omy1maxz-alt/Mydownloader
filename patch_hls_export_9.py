import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Replace the condition:
# if (forceTransformer && splitAudioUrl.isNullOrEmpty())
# with a smarter condition.
# If `streamKeyStrings` contains an audio track (e.g., groupIndex > 0 in YouTube HLS, or size > 1), it implies a split stream inside the master playlist.
# Wait, a safer condition: if it's YouTube HLS and we have multiple stream keys, DO NOT use Transformer.
# How do we know it's YouTube? `videoUrl.contains("googlevideo.com")`.
# Let's modify the condition to:
# val isYouTubeHls = videoUrl != null && videoUrl.contains("googlevideo.com") && videoUrl.contains(".m3u8")
# val hasSplitStreamKeys = !streamKeyStrings.isNullOrEmpty() && streamKeyStrings.size > 1
# if (forceTransformer && splitAudioUrl.isNullOrEmpty() && !(isYouTubeHls && hasSplitStreamKeys)) {

new_condition = """                            val isYouTubeHls = videoUrl != null && videoUrl.contains("googlevideo.com") && videoUrl.contains(".m3u8", ignoreCase = true)
                            val hasSplitStreamKeys = !streamKeyStrings.isNullOrEmpty() && streamKeyStrings.size > 1
                            if (forceTransformer && splitAudioUrl.isNullOrEmpty() && !(isYouTubeHls && hasSplitStreamKeys)) {
                                writeExportLog("User forced Transformer (Save to device), executing Transformer path directly.")
                                muxToMp4WithTransformer(bundledMediaItem, title)
                                return@launch
                            }
                            if (forceTransformer && (isYouTubeHls && hasSplitStreamKeys)) {
                                writeExportLog("Transformer forced but source is split YouTube HLS. Falling back to FFmpeg cache export to preserve video+audio muxing.")
                            }"""

content = content.replace("""                            if (forceTransformer && splitAudioUrl.isNullOrEmpty()) {
                                writeExportLog("User forced Transformer (Save to device), executing Transformer path directly.")
                                muxToMp4WithTransformer(bundledMediaItem, title)
                                return@launch
                            }""", new_condition)

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "w") as f:
    f.write(content)
