import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Let's check `muxToMp4FromCache(masterUrl: String, streamKeyStrings: List<String>?, title: String)`
# Inside it does: `val filteredPlaylist = parsedPlaylist.copy(streamKeys) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist`
# `videoVariantUrl = filteredPlaylist.variants.firstOrNull()?.url?.toString() ?: masterUrl`
# `audioVariantUrl = filteredPlaylist.audios.firstOrNull()?.url?.toString()`
# Then it passes them to `processPlaylist`.
# Then FFmpeg merges them:
#             if (audioPlaylistFile != null) {
#                 ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", audioPlaylistFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0"))
#             } else {
#                 ffmpegArgs.addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
#             }

# The user issue statement says:
# Fix 2: Explicitly Map the Preferred Audio Track in the FFmpeg Command
# When running the FFmpeg muxer with cached segments, include the audio stream index matching your selected language rather than defaulting to 0:a:0:
# # If selected stream is Korean (ko) or secondary audio track
# ffmpeg -i video_cached.m3u8 -i audio_selected_lang.m3u8 -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 output.mp4

# But wait! If we extract `audioVariantUrl` via HlsPlaylistParser with `streamKeys`, we only extract the specific audio track we want!
# So the `audioPlaylistFile` is a single audio track playlist.
# Thus `-map 1:a:0` IS mapping exactly the track we want.
# So the only bug here is that it bypassed FFmpeg!

# Let's fix the bypass logic in `HlsExportService.kt`.

old_logic = """                            val isYouTubeHls = videoUrl != null && videoUrl.contains("googlevideo.com") && videoUrl.contains(".m3u8", ignoreCase = true)
                            val hasSplitStreamKeys = !streamKeyStrings.isNullOrEmpty() && streamKeyStrings.size > 1
                            if (forceTransformer && splitAudioUrl.isNullOrEmpty() && !(isYouTubeHls && hasSplitStreamKeys)) {
                                writeExportLog("User forced Transformer (Save to device), executing Transformer path directly.")
                                muxToMp4WithTransformer(bundledMediaItem, title)
                                return@launch
                            }
                            if (forceTransformer && (isYouTubeHls && hasSplitStreamKeys)) {
                                writeExportLog("Transformer forced but source is split YouTube HLS. Falling back to FFmpeg cache export to preserve video+audio muxing.")
                            }"""

new_logic = """                            val isSplitHls = videoUrl != null && (videoUrl.contains("manifest/hls_variant") || videoUrl.contains("googlevideo.com")) && videoUrl.contains(".m3u8", ignoreCase = true)
                            val hasSplitStreamKeys = !streamKeyStrings.isNullOrEmpty() && streamKeyStrings.size > 1
                            if (forceTransformer && splitAudioUrl.isNullOrEmpty() && !(isSplitHls && hasSplitStreamKeys)) {
                                writeExportLog("User forced Transformer (Save to device), executing Transformer path directly.")
                                muxToMp4WithTransformer(bundledMediaItem, title)
                                return@launch
                            }
                            if (forceTransformer && (isSplitHls && hasSplitStreamKeys)) {
                                writeExportLog("Transformer forced but source is split YouTube HLS. Falling back to FFmpeg cache export to preserve video+audio muxing.")
                            }"""

content = content.replace(old_logic, new_logic)

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "w") as f:
    f.write(content)
