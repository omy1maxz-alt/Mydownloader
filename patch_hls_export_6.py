with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# YouTube streams from NewPipeExtractor often have StreamKeys provided to select the audio and video tracks out of the master playlist.
# When `forceTransformer=true` (Save to Device from CustomPlayerActivity), it forces `muxToMp4WithTransformer`.
# We need to change the condition so that if it is a YouTube HLS stream with complex StreamKeys (implying separate video and audio tracks in a DASH-converted HLS), it falls back to `muxToMp4FromCache` via FFmpeg which can natively handle merging multiple playlists from the cache if we instruct it to, OR we just use FFmpeg network download.
# But `muxToMp4FromCache` is actually an FFmpeg implementation that extracts the `.ts` files from the cache into a local directory and uses FFmpeg to mux them.
# Let's see what `muxToMp4FromCache` currently does.
