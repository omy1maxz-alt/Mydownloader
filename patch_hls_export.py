import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Replace the forceTransformer cache logic. We want to check if splitAudioUrl is present.
# If splitAudioUrl is present, Media3 Transformer will drop video because the cache represents only one chunk usually.
# Wait, let's look at the original code.

original_cache_logic = """                            if (forceTransformer && splitAudioUrl.isNullOrEmpty()) {
                                writeExportLog("User forced Transformer (Save to device), executing Transformer path directly.")
                                muxToMp4WithTransformer(bundledMediaItem, title)
                                return@launch
                            }
                            if (videoUrl != null) {
                                if (videoUrl.contains(".mp4", ignoreCase = true) && !videoUrl.contains(".m3u8", ignoreCase = true)) {
                                    copyMp4FromCache(videoUrl, title, splitAudioUrl)
                                } else {
                                    muxToMp4FromCache(videoUrl, streamKeyStrings, title)
                                }"""

# Wait, the current code ALREADY says: if (forceTransformer && splitAudioUrl.isNullOrEmpty())
# Ah, the user's log says "User forced Transformer (Save to device)" ... wait, let's check the grep output earlier.
# The user's log said: "Log says 'User forced Transformer (Save to device)'."
# Let's check what is actually in HlsExportService.kt right now.
