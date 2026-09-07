# The user says:
# "Now saved video on custom player.redownloading instead of turn the fully cached video into mp4.on kisskh.co. it download the highest quality,unlike before where it turn the full cache video with the quality i choose."
#
# Wait, why is it redownloading?
# Let's look at `HlsExportService.kt` `onStartCommand` at lines 97-105:
# ```
#         if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
#             bundledMediaItem = androidx.media3.common.MediaItem.Builder()
#                 .setUri(videoUrl)
#                 .setMimeType(mimeType)
#                 .build()
#         }
# ```
# In the prior commit, I STRIPPED the `streamKeys` from the `bundledMediaItem` because the track indices drift when Transformer re-parses the live playlist.
# If I strip the `streamKeys`, Transformer (and `resolveVariantUrl` via `streamKeyStrings` which was passed separately) falls back to the default behavior.
# Wait, `resolveVariantUrl(videoUrl, streamKeyStrings)` uses `streamKeyStrings`, WHICH I DID NOT STRIP!
# But wait, why does it redownload?
# Because `muxToMp4WithTransformer(bundledMediaItem, title)` is called.
# Transformer's `DefaultAssetLoaderFactory` uses `DefaultTrackSelector`.
# If `bundledMediaItem` DOES NOT have `streamKeys`, `DefaultTrackSelector` selects the HIGHEST quality variant by default!
# Since the custom player was playing a lower quality (chosen by the user), the HIGHEST quality is NOT in the cache!
# Because the highest quality is not in the cache, `CacheDataSource` falls back to the network and REDOWNLOADS the highest quality stream!

# How to fix this?
# We MUST tell Transformer/`resolveVariantUrl` to use the EXACT variant that the user played (which is the one in the cache).
# But we established that `streamKeyStrings` from the Custom Player are "numeric streamKeys captured at an earlier point in time" which drift!
# The user explicitly told me:
# "Do not reuse numeric streamKeys captured at an earlier point in time for a source whose playlist may be re-fetched/re-parsed later"
# Wait! IF the playlist is read FROM CACHE, the `streamKeys` DO NOT DRIFT!
# I ALREADY fixed `resolveVariantUrl` to read the master playlist FROM CACHE!
# So if it reads the master playlist from cache, the track indices exactly match the `streamKeys` captured during playback!
# Therefore, we CAN safely use the `streamKeys` if we read the master playlist from the cache!
