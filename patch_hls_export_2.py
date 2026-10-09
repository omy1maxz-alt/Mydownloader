import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Wait, if `splitAudioUrl.isNullOrEmpty()` is false, `forceTransformer && splitAudioUrl.isNullOrEmpty()` is FALSE.
# So it falls through to: `muxToMp4FromCache(videoUrl, streamKeyStrings, title)`.
# Let's check what `muxToMp4FromCache` does.
