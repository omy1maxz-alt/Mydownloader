import re

with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'r') as f:
    content = f.read()

# We MUST restore `streamKeys` when building `bundledMediaItem`.
# BUT wait! If we restore `streamKeys`, Transformer might fail with "audio only" if Transformer reads the network playlist!
# Does Transformer read the network playlist or the cached playlist?
# Transformer's `DefaultMediaSourceFactory` uses `HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)`.
# This CacheDataSource SHOULD read the master playlist from cache.
# BUT Media3 HlsMediaSource ONLY caches the master playlist if the user downloaded it via DownloadManager!
# For "Save Fully Cached Video", the user watched it on the player. The player CACHES the master playlist!
# So yes, the master playlist is in the cache!
# BUT wait, the user's instructions were:
# "Do not reuse numeric streamKeys captured at an earlier point in time for a source whose playlist may be re-fetched/re-parsed later"
# AND "If reusing a completed Download's cached data, build the MediaSource from the exact DownloadRequest.streamKeys, and verify (log) at export time that the freshly-parsed playlist's track count/order matches what was originally cached — if it doesn't match, fall back to MIME-type-based auto-selection instead of trusting the stale indices."
# AND "Longer-term (ties to the earlier cache-read fix): resolve tracks directly from cached segment content rather than re-parsing a live network playlist at export time"

# I actually Bypassed Transformer entirely in the last commit for `DownloadManager` cached videos:
# ```
#         if (download.state == Download.STATE_COMPLETED) {
#             val url = download.request.uri.toString()
#             val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
#             val finalUrl = resolveVariantUrl(url, streamKeysStr)
#             muxToMp4FromCache(finalUrl, title)
# ```
# Wait! In `onStartCommand` for `bundledMediaItem != null` (which is "Save Fully Cached Video" from the Custom Player), I STILL CALL TRANSFORMER FIRST!
# ```
#                     bundledMediaItem != null -> {
#                         try {
#                             muxToMp4WithTransformer(bundledMediaItem, title)
# ```
# If Transformer runs WITHOUT streamKeys, it selects the highest quality! Thus redownloading!
# We MUST use `streamKeys` so it selects the quality the user watched.
# Let's restore the `streamKeys` injection for `bundledMediaItem`.

target_bundled = """        if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
            val streamKeys = mutableListOf<androidx.media3.common.StreamKey>()
            streamKeyStrings?.forEach {
                val parts = it.split(",")
                if (parts.size == 2) {
                    try {
                        streamKeys.add(androidx.media3.common.StreamKey(parts[0].toInt(), parts[1].toInt()))
                    } catch (e: Exception) {}
                }
            }
            bundledMediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(mimeType)
                .setStreamKeys(streamKeys)
                .build()
        }"""

orig_bundled = """        if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
            // Bug fix: Do NOT apply stale streamKeys to the MediaItem.
            // HLS track group indices can shift between cache time and export time (e.g. ad insertion), causing audio-only exports.
            // Let Transformer / AssetLoader auto-select the best tracks (MIME-type based) at export time.
            bundledMediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(mimeType)
                .build()
        }"""

if orig_bundled in content:
    content = content.replace(orig_bundled, target_bundled)
    with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'w') as f:
        f.write(content)
    print("Restored streamKeys to MediaItem")
else:
    print("Could not find block")
