with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# I see the problem. `splitAudioUrl.isNullOrEmpty()` is TRUE in the YouTube DASH->HLS conversion case.
# Wait, the runtime evidence says:
# URL is a YouTube googlevideo HLS variant ending in /file/index.m3u8.
# MIME type: application/x-mpegURL
# source=PATH_A_CACHE
# has_media_item_bundle=true
# has_video_url=true
# export_method=muxToMp4FromCache
# cache_export=true
# forceTransformer=true
# StreamKeys=[0,3, 1,0, 3,0]
# Log says "User forced Transformer (Save to device)".
# Log reports "Transformer Export complete", but the saved file contains audio only.

# Why does Transformer produce audio only?
# Because for this YouTube URL, Media3 Transformer natively drops the video track if it gets confused by the StreamKeys or the multiplexing.
# Wait! YouTube HLS (DASH converted) has separate video and audio playlists in the Master playlist.
# The StreamKeys represent the tracks chosen: group 0, track 3 (video); group 1, track 0 (audio); group 3, track 0 (text).
# When we pass `bundledMediaItem` (which includes these StreamKeys) to Transformer, it seems to drop the video track for some reason.
# But wait, look at what happens inside `muxToMp4WithTransformer`!
