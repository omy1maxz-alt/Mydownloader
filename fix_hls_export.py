# If streamKeyStrings is `[0,2, 1,0, 2,0, 4,0]`, what are these keys?
# ExoPlayer orders stream keys by GroupIndex.
# Typically, Group 0 is Video, Group 1 is Audio, Group 2 is Subtitles, etc.
# BUT wait! If Group 0 is Video, `0,2` means `streamIndex 2` (the 3rd video variant).
# If we apply `targetVariantIndex = 2` to `#EXT-X-STREAM-INF` lines, we get the 3rd variant.
# WHY is that variant AUDIO ONLY in the FFmpeg log?
# Let's look at the FFmpeg log:
# `Stream #0:0[0x0](und): Audio: aac ... 1494 kb/s ...`
# Wait, 1494 kb/s AAC audio?? That's huge for audio!
# Actually, HLS can carry video and audio multiplexed in one stream. Why does FFmpeg think it's Audio only?
# `Input #0, hls ... bitrate: 0 kb/s`
# Maybe FFmpeg is failing to probe the video stream inside that segment because it's obfuscated or missing?
# Wait! "Chinese TS streams work... kisskh-style streams... error at the video that has several quality."
# On KissKH, multiple qualities are offered.
# `val targetKey = streamKeyStrings.firstOrNull()`
# Is it possible that `groupIndex 0` is the AUDIO group on KissKH, and `groupIndex 1` is the VIDEO group?
# If so, `0,2` means the 3rd audio track. Then we apply `2` to `#EXT-X-STREAM-INF` and pick a random video variant.
# But wait, if `0,2` is the audio track, and we use it to find the video variant, we might be picking a video variant we didn't cache!
# If we didn't cache it, `CacheDataSource` throws! But it DIDN'T throw!
# Wait! "Successfully exported cached segments to tmp dir. Muxing to MP4 using FFmpeg."
# That means ALL segments for that variant WERE in the cache!
# If `targetVariantIndex = 2` (from `0,2`) happens to match the cached video variant perfectly, then the video variant is correctly downloaded.
# Why does FFmpeg say "Stream map '0:v:0' matches no streams"?
# Because `video_playlist.m3u8` has NO VIDEO!
# Why would a cached variant have no video?

# Let's reconsider `streamKeyStrings`: `[0,2, 1,0, 2,0, 4,0]`
# When downloading/caching, ExoPlayer downloads the tracks defined in `DownloadRequest.streamKeys`.
# If `video_playlist.m3u8` corresponds to one of these stream keys, and it has no video...
# Wait! If the user selected `480p`, the streamKey might be `1,0` (if video is group 1) or `0,2` (if video is group 0).
# We MUST know which group is video and which is audio.
# How does `resolveVariantUrl` in `HlsExportService` know which `streamKey` is video?
# It doesn't! It just blindly picks `streamKeyStrings.firstOrNull()` for video, and `startsWith("1,")` for audio!
# `val targetKey = streamKeyStrings.firstOrNull()`
# `val audioKey = streamKeyStrings.find { it.startsWith("1,") }`

# THIS is the bug! If `streamKeyStrings` is `[0,2, 1,0, 2,0, 4,0]`, `firstOrNull()` is `0,2`.
# What if video is group 1 and audio is group 0? Or what if there are MULTIPLE `#EXT-X-STREAM-INF` variants but we should NOT use the first stream key?
# Actually, ExoPlayer's HlsParser assigns `groupIndex` as follows:
# It parses all `#EXT-X-MEDIA` lines first (Audio, Subtitles, Closed Captions). Each type + group ID combination gets a `groupIndex`.
# THEN it creates ONE group for all `#EXT-X-STREAM-INF` variants!
# So if there are Audio tracks (e.g. `TYPE=AUDIO,GROUP-ID="audio"`), they become `groupIndex = 0`.
# Then Video variants become `groupIndex = 1`.
# Then Subtitles become `groupIndex = 2`.
# THIS is why `0,2` is an AUDIO track, and `1,0` is the VIDEO track on KissKH!
# Because there is an AUDIO group!
# When there is NO separate audio group (like on Chinese sites), the Video variants become `groupIndex = 0`.
# This perfectly explains why my code works on Chinese sites (video is group 0) but fails on KissKH (video is group 1)!

# So `videoKey` is the one that points to the video variants.
# How do we know which group is the video group?
# Video group is the one that corresponds to `#EXT-X-STREAM-INF` lines.
# If we read the master playlist, we can COUNT the number of `#EXT-X-MEDIA` groups to find the video `groupIndex`!
# According to ExoPlayer HlsMasterPlaylist parser:
# 1. Groups `#EXT-X-MEDIA` by type (AUDIO, SUBTITLES, CLOSED-CAPTIONS).
# 2. Assigns a group index to each unique (type, group-id).
# 3. Assigns ONE group index for ALL `#EXT-X-STREAM-INF` variants.
# Actually, the simplest way is to look at the master playlist.
# Audio group: `#EXT-X-MEDIA:TYPE=AUDIO`. If there's 1 group of audio, video is group 1.
# Or, even better: we can just find which `StreamKey` corresponds to video by looking at the `groupIndex` that has the most streams? No.
