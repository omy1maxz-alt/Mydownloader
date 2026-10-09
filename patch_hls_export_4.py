import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Notice that in the logic:
# if (forceTransformer && splitAudioUrl.isNullOrEmpty()) {
#     muxToMp4WithTransformer(bundledMediaItem, title)
#     return@launch
# }

# BUT, the runtime evidence says:
# URL is a YouTube googlevideo HLS variant ending in /file/index.m3u8.
# StreamKeys=[0,3, 1,0, 3,0]
# "Log says 'User forced Transformer (Save to device)'."

# This means `splitAudioUrl.isNullOrEmpty()` MUST HAVE BEEN TRUE.
# Why? Because YouTube HLS streams (from NewPipe DASH conversion) DO NOT USE `splitAudioUrl`.
# They are a single HLS master playlist with embedded video and audio variants.
# So `splitAudioUrl` is null.
# Therefore, `forceTransformer && splitAudioUrl.isNullOrEmpty()` is TRUE.
# It calls `muxToMp4WithTransformer`.

# Inside `muxToMp4WithTransformer`:
# The `mediaItem` contains `StreamKeys`.
# `Transformer` tries to read the cache.
# But `Transformer` has a bug where if it's fed an HLS playlist with separate audio and video tracks, and no explicit TrackSelector, or if the cache is missing segments, it might default to the first track or fail to mux video.
# Wait, look at `muxToMp4WithTransformer`.
