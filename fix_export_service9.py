import re

with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'r') as f:
    content = f.read()

# Let's completely rework the `onStartCommand` and `exportFromDownloadId` to USE TRANSFORMER again but with TrackSelectionParameters to select by MIME type!
# Because `muxToMp4FromCache` only handles SINGLE variant playlists, it BREAKS split-audio HLS streams (which KissKH uses, hence the missing group 3).
# If we use Transformer, Transformer CAN handle split audio/video HLS streams perfectly! The ONLY problem was the `IllegalStateException: Format changes are not supported` for ad segments!
# Wait, the user said:
# "Different bug this time — no crash, but the log shows Transformer Export complete with a clean success. That tells you it's not the format-change/discontinuity issue from before — it's a track-selection mismatch."
# "Why this produces "audio only" instead of a crash: Transformer succeeds because a valid audio track was selected and muxed — it just wasn't paired with the video track you meant to select."
# "Fix — stop relying on stale numeric stream keys entirely... let ExoPlayer's DefaultTrackSelector auto-select first video track + first audio track fresh at export time, based on MIME type"

# YES! The ad-insertion crash was ALREADY fixed in a prior commit (maybe by removing setVideoMimeType). The CURRENT issue is purely track selection.
# Wait, let's look at `muxToMp4WithTransformer`. We need to ADD TrackSelectionParameters to it so it forces video + audio selection by MIME type!

target_transformer = """    private suspend fun muxToMp4WithTransformer(mediaItem: MediaItem, title: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val cacheFactory: CacheDataSource.Factory =
                HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

            // Force track selection by MIME type to avoid stale streamKey drift
            val trackSelectionParameters = androidx.media3.common.TrackSelectionParameters.Builder(applicationContext)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true) // Ignore subs for muxing
                .build()

            val defaultMediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(applicationContext)
                .setDataSourceFactory(cacheFactory)

            val transformer = Transformer.Builder(applicationContext)
                // Removed forced MimeTypes to allow direct Remuxing instead of Transcoding
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        applicationContext,
                        DefaultDecoderFactory(applicationContext),
                        androidx.media3.common.util.Clock.DEFAULT,
                        defaultMediaSourceFactory,
                        androidx.media3.datasource.DataSourceBitmapLoader(applicationContext)
                    )
                )


                .addListener(object : Transformer.Listener {"""

orig_transformer = """    private suspend fun muxToMp4WithTransformer(mediaItem: MediaItem, title: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val cacheFactory: CacheDataSource.Factory =
                HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

            val defaultMediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(applicationContext)
                .setDataSourceFactory(cacheFactory)

            val transformer = Transformer.Builder(applicationContext)
                // Removed forced MimeTypes to allow direct Remuxing instead of Transcoding
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        applicationContext,
                        DefaultDecoderFactory(applicationContext),
                        androidx.media3.common.util.Clock.DEFAULT,
                        defaultMediaSourceFactory,
                        androidx.media3.datasource.DataSourceBitmapLoader(applicationContext)
                    )
                )


                .addListener(object : Transformer.Listener {"""

content = content.replace(orig_transformer, target_transformer)

# We also need to strip streamKeys from the bundledMediaItem and downloadMediaItem before passing them to Transformer.
target_bundled = """        if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
            // Bug fix: Do NOT apply stale streamKeys to the MediaItem.
            // HLS track group indices can shift between cache time and export time (e.g. ad insertion), causing audio-only exports.
            bundledMediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(mimeType)
                .build()
        }"""

orig_bundled = """        if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
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

content = content.replace(orig_bundled, target_bundled)

target_dl_id = """        // Check if fully cached. If yes, use Transformer. If not, fallback to FFmpeg network download.
        if (download.state == Download.STATE_COMPLETED) {
            // Strip streamKeys from the download mediaItem to prevent track index mismatch during export
            val rawMediaItem = download.request.toMediaItem()
            val mediaItem = rawMediaItem.buildUpon().setStreamKeys(emptyList()).build()
            try {
                muxToMp4WithTransformer(mediaItem, title)
            } catch (e: Exception) {
                writeExportLog("Transformer failed on downloaded item, falling back to muxToMp4FromCache: ${e.message}")
                val url = download.request.uri.toString()
                val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
                val finalUrl = resolveVariantUrl(url, streamKeysStr)
                try {
                    muxToMp4FromCache(finalUrl, title)
                } catch (cacheEx: Exception) {
                    writeExportLog("muxToMp4FromCache failed, falling back to network FFmpeg: ${cacheEx.message}")
                    muxToMp4(finalUrl, title)
                }
            }
        } else {"""

orig_dl_id = """        // Check if fully cached. If yes, use the new muxToMp4FromCache method which reads the exact cached segments.
        if (download.state == Download.STATE_COMPLETED) {
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            val finalUrl = resolveVariantUrl(url, streamKeysStr)
            try {
                writeExportLog("Using muxToMp4FromCache for fully downloaded item to avoid network playlist re-parsing drift.")
                muxToMp4FromCache(finalUrl, title)
            } catch (cacheEx: Exception) {
                writeExportLog("muxToMp4FromCache failed, falling back to network FFmpeg: ${cacheEx.message}")
                muxToMp4(finalUrl, title)
            }
        } else {"""

content = content.replace(orig_dl_id, target_dl_id)

target_bundled2 = """                    bundledMediaItem != null -> {
                        // Use the bundled MediaItem directly if provided (from CustomPlayerActivity 'Play in App' export)
                        try {
                            muxToMp4WithTransformer(bundledMediaItem, title)
                        } catch (e: Exception) {
                            writeExportLog("Transformer failed, falling back to muxToMp4FromCache: ${e.message}")
                            if (videoUrl != null) {
                                val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                try {
                                    muxToMp4FromCache(finalUrl, title)
                                } catch (cacheEx: Exception) {
                                    writeExportLog("muxToMp4FromCache failed (likely incomplete cache), falling back to network FFmpeg: ${cacheEx.message}")
                                    muxToMp4(finalUrl, title)
                                }
                            } else {
                                throw e
                            }
                        }
                    }"""

orig_bundled2 = """                    bundledMediaItem != null -> {
                        // Use the new muxToMp4FromCache method which reads the exact cached segments.
                        try {
                            if (videoUrl != null) {
                                val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                muxToMp4FromCache(finalUrl, title)
                            } else {
                                throw Exception("videoUrl is null")
                            }
                        } catch (e: Exception) {
                            writeExportLog("muxToMp4FromCache failed, falling back to network FFmpeg: ${e.message}")
                            if (videoUrl != null) {
                                val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                muxToMp4(finalUrl, title)
                            } else {
                                throw e
                            }
                        }
                    }"""

content = content.replace(orig_bundled2, target_bundled2)

with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'w') as f:
    f.write(content)
