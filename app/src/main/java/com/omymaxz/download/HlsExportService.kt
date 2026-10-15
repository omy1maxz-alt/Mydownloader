package com.omymaxz.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.common.util.Util
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.datasource.cache.CacheDataSource
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HlsExportService : Service() {

    companion object {
        const val EXTRA_VIDEO_URL   = "com.omymaxz.download.extra.VIDEO_URL"
        const val EXTRA_TITLE       = "com.omymaxz.download.extra.TITLE"
        const val EXTRA_MIME_TYPE   = "com.omymaxz.download.extra.MIME_TYPE"
        const val EXTRA_STREAM_KEYS = "com.omymaxz.download.extra.STREAM_KEYS"
        const val EXTRA_DOWNLOAD_ID = "com.omymaxz.download.extra.DOWNLOAD_ID"
        const val EXTRA_USER_AGENT  = "com.omymaxz.download.extra.USER_AGENT"
        const val EXTRA_REFERER     = "com.omymaxz.download.extra.REFERER"
        const val EXTRA_COOKIE      = "com.omymaxz.download.extra.COOKIE"
        const val EXTRA_TRACK_ID    = "com.omymaxz.download.extra.TRACK_ID"
        const val EXTRA_TRACK_WIDTH = "com.omymaxz.download.extra.TRACK_WIDTH"
        const val EXTRA_TRACK_HEIGHT= "com.omymaxz.download.extra.TRACK_HEIGHT"
        const val EXTRA_TRACK_BITRATE="com.omymaxz.download.extra.TRACK_BITRATE"
        const val EXTRA_FORCE_TRANSFORMER="com.omymaxz.download.extra.FORCE_TRANSFORMER"
        const val EXTRA_SELECTED_AUDIO_LANGUAGE = "com.omymaxz.download.extra.SELECTED_AUDIO_LANGUAGE"
        const val EXTRA_SELECTED_AUDIO_LABEL = "com.omymaxz.download.extra.SELECTED_AUDIO_LABEL"
        const val EXTRA_SELECTED_AUDIO_ID = "com.omymaxz.download.extra.SELECTED_AUDIO_ID"
        const val EXTRA_MEDIA_ITEM_BUNDLE = "com.omymaxz.download.extra.MEDIA_ITEM_BUNDLE"

        const val CHANNEL_ID = "hls_export_channel"
        const val NOTIFICATION_ID = 3000
        private const val TAG = "HlsExportService"
    }


    private fun isValidMediaSegment(file: File, isFmp4: Boolean): Boolean {
        return try {
            java.io.FileInputStream(file).use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 8) return@use false
                val isPng = header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
                val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
                val isGif = header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()
                if (isPng || isJpeg || isGif) return@use false
                if (isFmp4) {
                    val boxType = String(header, 4, 4, Charsets.US_ASCII)
                    boxType in setOf("ftyp", "styp", "moof", "mdat", "sidx", "free", "emsg", "prft", "moov")
                } else {
                    header[0] == 0x47.toByte() ||
                        (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte())
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun redactExportLogMessage(message: String): String {
        // Signed media URLs often carry bearer-like tokens in query parameters.
        // Keep the host/path useful for diagnosis, but never persist query or fragment values.
        val urlPattern = Regex("""(?i)\bhttps?://[^\s"'<>]+""")
        return urlPattern.replace(message) { match ->
            val rawUrl = match.value.trimEnd(',', ')', ']', ';')
            val suffix = match.value.removePrefix(rawUrl)
            val safe = try {
                val uri = android.net.Uri.parse(rawUrl)
                val builder = uri.buildUpon().clearQuery().fragment(null)
                val redacted = builder.build().toString()
                if (uri.encodedQuery != null) "$redacted?<redacted>" else redacted
            } catch (_: Exception) {
                rawUrl.substringBefore('?').substringBefore('#') + if (rawUrl.contains('?') || rawUrl.contains('#')) "?<redacted>" else ""
            }
            safe + suffix
        }
    }

    private fun writeExportLog(message: String) {
        try {
            val logFile = File(applicationContext.filesDir, "export_logs.txt")
            val maxBytes = 512 * 1024L
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val safeMessage = redactExportLogMessage(message)
            val line = "[$timestamp] $safeMessage\n"
            synchronized(exportLogLock) {
                if (logFile.exists() && logFile.length() + line.toByteArray(Charsets.UTF_8).size > maxBytes) {
                    val backup = File(applicationContext.filesDir, "export_logs.previous.txt")
                    if (backup.exists()) backup.delete()
                    if (!logFile.renameTo(backup)) {
                        // A failed rotation must not grow the primary log without bound.
                        logFile.writeText("")
                    }
                }
                logFile.appendText(line)
                if (logFile.length() > maxBytes) {
                    // A single exceptionally large message must not defeat the size cap.
                    val tail = logFile.readText().takeLast((maxBytes / 2).toInt())
                    logFile.writeText(tail)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to write export diagnostic log", e)
        }
    }

    private val exportLogLock = Any()

    private val activeExports = AtomicInteger(0)
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        val extraDownloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown_Video"
        val splitAudioUrl = intent.getStringExtra("com.omymaxz.download.extra.AUDIO_URL")
        val mimeType   = intent.getStringExtra(EXTRA_MIME_TYPE) ?: androidx.media3.common.MimeTypes.APPLICATION_M3U8
        val exportRequestHeaders = HlsDownloadHelper.RequestHeaders(
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT),
            referer = intent.getStringExtra(EXTRA_REFERER),
            cookie = intent.getStringExtra(EXTRA_COOKIE)
        )


        android.util.Log.d("HLS_EXPORT_DEBUG", "[HLS_EXPORT_DEBUG] intentVideoUrl=$videoUrl, intentMimeType=$mimeType")
        val streamKeyStrings = intent.getStringArrayListExtra(EXTRA_STREAM_KEYS)
        val forceTransformer = intent.getBooleanExtra(EXTRA_FORCE_TRANSFORMER, false)
        val selectedAudioLanguage = intent.getStringExtra(EXTRA_SELECTED_AUDIO_LANGUAGE)
        val selectedAudioLabel = intent.getStringExtra(EXTRA_SELECTED_AUDIO_LABEL)
        val selectedAudioId = intent.getStringExtra(EXTRA_SELECTED_AUDIO_ID)
        val mediaItemBundle = intent.getBundleExtra(EXTRA_MEDIA_ITEM_BUNDLE)
        var bundledMediaItem = if (mediaItemBundle != null) androidx.media3.common.MediaItem.fromBundle(mediaItemBundle) else null

        // Fix for NullPointerException in Transformer:
        // MediaItem.fromBundle often loses its localConfiguration/Uri across IPC.
        // We explicitly reconstruct the MediaItem using primitive strings passed in the intent to ensure it's valid.
        if (bundledMediaItem != null && bundledMediaItem.localConfiguration == null && videoUrl != null) {
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
        }


        if (videoUrl == null && extraDownloadId == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        writeExportLog("Starting export for: $title, URL: $videoUrl, StreamKeys: $streamKeyStrings")
        startForeground(NOTIFICATION_ID, buildNotification(title))
        activeExports.incrementAndGet()

        serviceScope.launch {
            try {
                when {
                    extraDownloadId != null -> {
                        writeExportLog("source=PATH_C_DOWNLOAD_ID\ndownload_id=$extraDownloadId\nhas_media_item_bundle=${bundledMediaItem != null}\nhas_video_url=${videoUrl != null}\ninput_uri=null\nmimeType=$mimeType\nexport_method=exportFromDownloadId")
                        exportFromDownloadId(extraDownloadId, title, mimeType, splitAudioUrl, exportRequestHeaders)
                    }
                    bundledMediaItem != null -> {
                        val isDirectMp4 = videoUrl != null &&
                            videoUrl.contains(".mp4", ignoreCase = true) &&
                            !videoUrl.contains(".m3u8", ignoreCase = true)
                        val selectedExportMethod = if (isDirectMp4) "copyMp4FromCache" else "muxToMp4FromCache"
                        writeExportLog("source=PATH_A_CACHE\ndownload_id=null\nhas_media_item_bundle=true\nhas_video_url=" + (videoUrl != null) + "\ninput_uri=" + videoUrl + "\nmimeType=" + mimeType + "\nexport_method=" + selectedExportMethod + "\ncache_export=true\nforceTransformer=" + forceTransformer + "\nselectedAudioLanguage=" + selectedAudioLanguage + "\nselectedAudioLabel=" + selectedAudioLabel + "\nselectedAudioId=" + selectedAudioId)

                        try {
                            val isHlsManifest = videoUrl?.contains(".m3u8", ignoreCase = true) == true ||
                                mimeType == MimeTypes.APPLICATION_M3U8
                            val isSplitHls = videoUrl != null &&
                                (videoUrl.contains("manifest/hls_variant") || videoUrl.contains("googlevideo.com")) &&
                                videoUrl.contains(".m3u8", ignoreCase = true)
                            val hasSplitStreamKeys = !streamKeyStrings.isNullOrEmpty() && streamKeyStrings.size > 1

                            // A direct MP4 already in the unified cache should be copied from cache.
                            // Do this before the user-requested Transformer override: Transformer can
                            // reopen the original URI and fetch missing bytes, defeating cache-only save.
                            if (isDirectMp4) {
                                writeExportLog("Direct MP4 detected; bypassing Transformer and exporting from cache.")
                            } else if (forceTransformer && !isHlsManifest && splitAudioUrl.isNullOrEmpty()) {
                                writeExportLog("User forced Transformer for progressive/non-HLS source; executing Transformer path.")
                                muxToMp4WithTransformer(bundledMediaItem, title, exportRequestHeaders)
                                return@launch
                            }
                            if (forceTransformer && isHlsManifest) {
                                writeExportLog("HLS source uses FFmpeg cache muxing to preserve selected video and audio renditions; Transformer path skipped.")
                            }
                            if (videoUrl != null) {
                                if (videoUrl.contains(".mp4", ignoreCase = true) && !videoUrl.contains(".m3u8", ignoreCase = true)) {
                                    copyMp4FromCache(videoUrl, title, splitAudioUrl, exportRequestHeaders)
                                } else {
                                    muxToMp4FromCache(videoUrl, streamKeyStrings, title, selectedAudioLanguage, selectedAudioLabel, selectedAudioId, exportRequestHeaders)
                                }
                            } else {
                                throw Exception("videoUrl is null")
                            }
                        } catch (e: Exception) {
                            writeExportLog("Cache export failed, falling back to network FFmpeg: ${e.message}")
                            if (videoUrl != null) {
                                if (videoUrl.contains(".mp4", ignoreCase = true) && !videoUrl.contains(".m3u8", ignoreCase = true)) {
                                    withContext(Dispatchers.Main) { android.widget.Toast.makeText(applicationContext, "Starting background download...", android.widget.Toast.LENGTH_SHORT).show() }
                                    val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(title, android.net.Uri.parse(videoUrl)).build()
                                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, request, false)
                                } else {
                                    val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings, exportRequestHeaders)
                                    muxToMp4(finalUrl, title, splitAudioUrl, streamKeyStrings, exportRequestHeaders)
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    videoUrl != null -> {
                        writeExportLog("source=PATH_B_NETWORK_FFMPEG\ndownload_id=null\nhas_media_item_bundle=false\nhas_video_url=true\ninput_uri=$videoUrl\nmimeType=$mimeType\nexport_method=muxToMp4\ncache_export=false")
                        val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings, exportRequestHeaders)
                        muxToMp4(finalUrl, title, splitAudioUrl, streamKeyStrings, exportRequestHeaders) // Fallback to FFmpeg
                    }
                }
            } catch (t: Throwable) {
                writeExportLog("Export crashed: ${t.message} | ${android.util.Log.getStackTraceString(t)}")
                Log.e(TAG, "Export failed", t)
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Export failed: ${t.message}", Toast.LENGTH_LONG).show() }
            } finally {
                if (activeExports.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun exportFromDownloadId(
        extraDownloadId: String,
        title: String,
        mimeType: String?,
        splitAudioUrl: String? = null,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)
        val download = dm.downloadIndex.getDownload(extraDownloadId)
            ?: run {
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Video cache not found", Toast.LENGTH_SHORT).show() }
                return
            }

        // Check if fully cached. If yes, use Transformer. If not, fallback to FFmpeg network download.
        if (download.state == Download.STATE_COMPLETED) {
            val downloadRequestMimeType = download.request.mimeType ?: mimeType
            // Strip streamKeys from the download mediaItem to prevent track index mismatch during export
            val rawMediaItem = download.request.toMediaItem()
            // Ensure we use the exact MIME type stored in the DownloadRequest, with a fallback if needed
            val mediaItem = rawMediaItem.buildUpon()
                .setMimeType(downloadRequestMimeType)

                .build()
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            val isYouTubeHls = url.contains("googlevideo.com") && url.contains(".m3u8", ignoreCase = true)
            val hasSplitStreamKeys = streamKeysStr.size > 1

            try {
                if (isYouTubeHls && hasSplitStreamKeys) {
                    writeExportLog("Transformer skipped for split YouTube HLS from PATH_C. Falling back to FFmpeg cache export.")
                    throw Exception("Skipping Transformer for YouTube split stream")
                }
                muxToMp4WithTransformer(mediaItem, title, requestHeaders)
            } catch (e: Exception) {
                writeExportLog("Transformer failed or skipped on downloaded item, falling back to muxToMp4FromCache: ${e.message}")

                try {
                    if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                        copyMp4FromCache(url, title, requestHeaders = requestHeaders)
                    } else {
                        muxToMp4FromCache(url, streamKeysStr, title, requestHeaders = requestHeaders)
                    }
                } catch (cacheEx: Exception) {
                    writeExportLog("Cache export failed, falling back to network FFmpeg: ${cacheEx.message}")
                    // CRITICAL FIX: We are already inside exportFromDownloadId, meaning DownloadManager finished.
                    // DO NOT call sendAddDownload() here, it causes an infinite recursion bug where Download completes,
                    // Export fails, and it asks to Download again using a cached URI.
                    // Instead, fallback directly to FFmpeg muxToMp4 using the original URI from the request.
                    val finalUrl = resolveVariantUrl(url, streamKeysStr, requestHeaders)
                    muxToMp4(finalUrl, title, splitAudioUrl, streamKeysStr, requestHeaders)
                }
            }
        } else {
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            // CRITICAL FIX: Same as above. Do not call sendAddDownload if the download failed or was interrupted.
            // Just fallback to FFmpeg network download.
            val finalUrl = resolveVariantUrl(url, streamKeysStr, requestHeaders)
            muxToMp4(finalUrl, title, splitAudioUrl, streamKeysStr, requestHeaders)
        }
    }

    private suspend fun muxToMp4WithTransformer(
        mediaItem: MediaItem,
        title: String,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ) =
        suspendCancellableCoroutine<Unit> { cont ->
            android.util.Log.d("HLS_EXPORT_DEBUG", "[HLS_EXPORT_DEBUG] muxToMp4WithTransformer MediaItem URI=${mediaItem.localConfiguration?.uri} MIME=${mediaItem.localConfiguration?.mimeType}")
            val cacheFactory: CacheDataSource.Factory =
                HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true, requestHeaders = requestHeaders)

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


                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                        writeExportLog("Transformer Export complete: $title")
                        Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        result: ExportResult, ex: ExportException
                    ) {
                        writeExportLog("Transformer error on $title: ${ex.message} | ${android.util.Log.getStackTraceString(ex)}")
                        Log.e(TAG, "Transformer error", ex)
                        // Do not show Toast here if we are falling back, the fallback will handle success/failure Toast.
                        if (cont.isActive) cont.resumeWithException(ex)
                    }
                })
                .build()

            val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            var out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$safeTitle.mp4"
            )
            var counter = 1
            while (out.exists()) {
                out = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "${safeTitle}_$counter.mp4"
                )
                counter++
            }
            transformer.start(mediaItem, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }


    private fun cacheOnlyDataSource(): androidx.media3.datasource.DataSource =
        cacheOnlyDataSource(HlsDownloadHelper.RequestHeaders.fromLegacyState())

    private fun cacheOnlyDataSource(
        requestHeaders: HlsDownloadHelper.RequestHeaders
    ): androidx.media3.datasource.DataSource {
        // This source must never fall back to network. Header values are accepted to make the
        // export-call context explicit; they are intentionally not applied to this cache-only path.
        @Suppress("UNUSED_VARIABLE")
        val scopedHeaders = requestHeaders
        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
        val failingUpstream = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.BaseDataSource(false) {
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long =
                    throw java.io.IOException("CACHE_MISS: ${dataSpec.uri}")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw java.io.IOException("CACHE_MISS")
                override fun getUri(): android.net.Uri? = null
                override fun close() {}
            }
        }
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(failingUpstream)
            .setCacheKeyFactory(HlsDownloadHelper.customCacheKeyFactory)
            .createDataSource()
    }

    private suspend fun copyMp4FromCache(
        url: String,
        title: String,
        splitAudioUrl: String? = null,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)

        fun extractSpansToFile(targetUrl: String, destFile: File) {
            val uri = android.net.Uri.parse(targetUrl)
            val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
            val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
            val metadata = cache.getContentMetadata(cacheKey)
            val expectedLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(metadata)

            if (spans.isEmpty() || expectedLength <= 0) throw Exception("CACHE_MP4_INCOMPLETE: $targetUrl")

            destFile.outputStream().use { fos ->
                var expectedPos = 0L
                for (span in spans) {
                    if (span.position != expectedPos) throw Exception("CACHE_GAP: Unexpected span position")
                    if (span.file != null && span.file!!.exists()) {
                        span.file!!.inputStream().use { fis ->
                            val bytesCopied = fis.copyTo(fos)
                            if (bytesCopied != span.length) throw Exception("CACHE_GAP: Copied length mismatch")
                        }
                    } else {
                        throw Exception("CACHE_GAP: Missing physical span file")
                    }
                    expectedPos += span.length
                }
                if (expectedPos < expectedLength) throw Exception("CACHE_MP4_INCOMPLETE: Not fully cached")
            }
        }

        if (splitAudioUrl.isNullOrEmpty()) {
            // Direct copy for single streams
            try {
                extractSpansToFile(url, out)
                notifyMediaStore(out, "video/mp4")
                writeExportLog("MP4 cache copy complete: $title")
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                if (out.exists()) out.delete()
                throw e
            }
        } else {
            // Extract both to temp dir and mux with FFmpeg
            val tmpDir = File(applicationContext.filesDir, "tmp_export_${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            val videoTmp = File(tmpDir, "video.mp4")
            val audioTmp = File(tmpDir, "audio.m4a")

            try {
                writeExportLog("Extracting split cached MP4s for muxing...")
                extractSpansToFile(url, videoTmp)
                extractSpansToFile(splitAudioUrl, audioTmp)

                val ffmpegArgs = listOf("-y", "-i", videoTmp.absolutePath, "-i", audioTmp.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", out.absolutePath)
                val session = FFmpegKit.executeWithArguments(ffmpegArgs.toTypedArray())
                if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                    notifyMediaStore(out, "video/mp4")
                    writeExportLog("Split MP4 cache muxing complete: $title")
                    withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show() }
                } else {
                    if (out.exists()) out.delete()
                    throw Exception("FFmpeg muxing failed for split MP4 cache.")
                }
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    }

    private fun notifyMediaStore(file: File, mimeType: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = applicationContext.contentResolver
            val targetUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (targetUri != null) {
                resolver.openOutputStream(targetUri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                file.delete()
            }
        }
    }

    private suspend fun muxToMp4FromCache(
        masterUrl: String,
        streamKeyStrings: List<String>?,
        title: String,
        selectedAudioLanguage: String? = null,
        selectedAudioLabel: String? = null,
        selectedAudioId: String? = null,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val tmpDir = File(applicationContext.filesDir, "tmp_export_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        try {
            val cacheOnlyFactory = cacheOnlyDataSource()
            val networkFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = false, requestHeaders = requestHeaders).createDataSource()

            var masterText = ""
            val masterDataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(masterUrl))
            try {
                // 1. Try to read the master playlist strictly from the cache
                cacheOnlyFactory.open(masterDataSpec)
                val buffer = ByteArray(1024 * 64)
                var bytesRead: Int
                val outputStream = java.io.ByteArrayOutputStream()
                while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                masterText = outputStream.toString("UTF-8")
                writeExportLog("Successfully read master playlist from cache.")
            } catch (e: Exception) {
                // 2. Fallback to network (with headers) if not in cache
                writeExportLog("Master playlist not in cache, fetching from network...")
                try {
                    networkFactory.open(masterDataSpec)
                    val buffer = ByteArray(1024 * 64)
                    var bytesRead: Int
                    val outputStream = java.io.ByteArrayOutputStream()
                    while (networkFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    masterText = outputStream.toString("UTF-8")
                } finally {
                    networkFactory.close()
                }
            } finally {
                cacheOnlyFactory.close()
            }

            if (masterText.isEmpty() || !masterText.contains("#EXTM3U")) {
                throw Exception("Failed to fetch or parse master playlist")
            }
            val masterLines = masterText.lines()

            // Parse the master playlist using Media3's official parser to guarantee track group index alignment
            var videoVariantUrl = masterUrl
            var audioVariantUrl: String? = null

            if (masterText.contains(".m3u8", true) && !streamKeyStrings.isNullOrEmpty()) {
                try {
                    val streamKeys = streamKeyStrings.mapNotNull {
                        val parts = it.split(",")
                        if (parts.size == 2) androidx.media3.common.StreamKey(parts[0].toInt(), parts[1].toInt()) else null
                    }
                    val parser = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser()
                    val masterInputStream = masterText.byteInputStream(Charsets.UTF_8)
                    val parsedPlaylist = parser.parse(android.net.Uri.parse(masterUrl), masterInputStream)

                    if (parsedPlaylist is androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist) {
                        // Scan for actual cached variants instead of blind streamKey matching to avoid Media3 track group mismatch bugs
                        val videoKey = streamKeys.firstOrNull { it.groupIndex == 0 }
                        if (videoKey != null && videoKey.streamIndex < parsedPlaylist.variants.size) {
                            videoVariantUrl = parsedPlaylist.variants[videoKey.streamIndex].url.toString()
                        } else {
                            videoVariantUrl = parsedPlaylist.variants.firstOrNull()?.url?.toString() ?: masterUrl
                        }

                        // Match the rendition selected in the player. Cached-byte count is only a fallback
                        // when selected-audio metadata was unavailable.
                        var bestAudioUrl: String? = null
                        var maxCachedBytes: Long = 0
                        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                        val cacheKeyFactory = HlsDownloadHelper.customCacheKeyFactory
                        fun normalizeAudioToken(value: String?): String =
                            value.orEmpty().trim().lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
                        data class AudioCandidate(val url: String, val name: String?, val language: String?, val label: String?, val id: String?, val cachedBytes: Long, val score: Int)
                        val selectedLang = normalizeAudioToken(selectedAudioLanguage)
                        val selectedLangPrimary = normalizeAudioToken(selectedAudioLanguage?.substringBefore("-"))
                        val selectedLabel = normalizeAudioToken(selectedAudioLabel)
                        val selectedId = normalizeAudioToken(selectedAudioId)
                        val audioCandidates = parsedPlaylist.audios.map { audio ->
                            val url = audio.url.toString()
                            val name = audio.name
                            val language = audio.format.language
                            val label = audio.format.label
                            val id = audio.format.id
                            val nName = normalizeAudioToken(name)
                            val nLanguage = normalizeAudioToken(language)
                            val nLabel = normalizeAudioToken(label)
                            val nId = normalizeAudioToken(id)
                            val score = when {
                                selectedId.isNotEmpty() && (selectedId == nId || selectedId == nName || selectedId == nLabel) -> 100
                                selectedLabel.isNotEmpty() && (selectedLabel == nName || selectedLabel == nLabel || selectedLabel == nId) -> 90
                                selectedLang.isNotEmpty() && selectedLang == nLanguage -> 80
                                selectedLangPrimary.isNotEmpty() && selectedLangPrimary == nLanguage -> 70
                                else -> 0
                            }
                            var cachedBytes = 0L
                            try {
                                val spec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(url))
                                val cacheKey = cacheKeyFactory.buildCacheKey(spec)
                                cachedBytes = cache.getCachedBytes(cacheKey, 0L, androidx.media3.common.C.LENGTH_UNSET.toLong())
                            } catch (_: Exception) {}
                            AudioCandidate(url, name, language, label, id, cachedBytes, score)
                        }
                        val hasSelectedHint = selectedId.isNotEmpty() || selectedLabel.isNotEmpty() || selectedLang.isNotEmpty()
                        if (audioCandidates.isNotEmpty()) {
                            val match = audioCandidates.filter { it.score > 0 }
                                .sortedWith(compareByDescending<AudioCandidate> { it.score }.thenByDescending { it.cachedBytes })
                                .firstOrNull()
                            if (match != null) {
                                bestAudioUrl = match.url
                                maxCachedBytes = match.cachedBytes
                                writeExportLog("Selected audio rendition: name=" + match.name + ", language=" + match.language + ", label=" + match.label + ", id=" + match.id + ", score=" + match.score + ", cachedBytes=" + match.cachedBytes)
                            } else if (hasSelectedHint) {
                                writeExportLog("ERROR: selected audio did not match manifest. language=" + selectedAudioLanguage + ", label=" + selectedAudioLabel + ", id=" + selectedAudioId + "; candidates=" + audioCandidates.joinToString { it.name.toString() + ":" + it.language + ":" + it.label + ":" + it.id })
                                throw IllegalStateException("SELECTED_AUDIO_VARIANT_NOT_FOUND: selected audio did not match manifest")
                            } else {
                                val fallback = audioCandidates.maxByOrNull { it.cachedBytes }
                                bestAudioUrl = fallback?.url
                                maxCachedBytes = fallback?.cachedBytes ?: 0L
                                writeExportLog("No selected-audio metadata; using rendition with most cached bytes: " + fallback?.name + "/" + fallback?.language)
                            }
                        } else {
                            writeExportLog("Master playlist has no separate audio renditions; audio should be muxed with selected video rendition.")
                        }
                        audioVariantUrl = bestAudioUrl

                        writeExportLog("Parsed Master. Video Variant: $videoVariantUrl, Audio: $audioVariantUrl (Cached bytes: $maxCachedBytes)")
                    }
                } catch (e: Exception) {
                    writeExportLog("Failed to parse master playlist with HlsPlaylistParser: " + e.message)
                    if (e.message?.contains("SELECTED_AUDIO_VARIANT_NOT_FOUND") == true) throw e
                    // Fallback to videoVariantUrl = masterUrl if parsing fails
                }
            }

            suspend fun processPlaylist(playlistUrl: String, outputFileName: String): File {
                val processedSegments = mutableMapOf<String, String>() // Normalized URL -> Local File Name
                val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(playlistUrl))
                var playlistContent = ""
                try {
                    cacheOnlyFactory.open(dataSpec)
                    val buffer = ByteArray(1024 * 64)
                    var bytesRead: Int
                    val outputStream = java.io.ByteArrayOutputStream()
                    while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    playlistContent = outputStream.toString("UTF-8")
                } catch (e: Exception) {
                    // Fallback to network for playlist
                    try {
                        networkFactory.open(dataSpec)
                        val buffer = ByteArray(1024 * 64)
                        var bytesRead: Int
                        val outputStream = java.io.ByteArrayOutputStream()
                        while (networkFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        playlistContent = outputStream.toString("UTF-8")
                    } finally {
                        networkFactory.close()
                    }
                } finally {
                    cacheOnlyFactory.close()
                }

                if (playlistContent.isEmpty() || !playlistContent.contains("#EXTM3U")) {
                    throw Exception("Failed to read valid M3U8 for $playlistUrl")
                }

                val isFmp4 = playlistContent.contains("#EXT-X-MAP")
                val lines = playlistContent.lines()
                val newLines = mutableListOf<String>()
                var segmentIndex = 0

                for (line in lines) {
                    if (line.isBlank()) continue

                    if (line.startsWith("#EXT-X-MAP:URI=")) {
                        val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                        if (uriMatch != null) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            var ext = fullUrl.substringAfterLast(".", "mp4").substringBefore("?")
                            val validExtensions = listOf("mp4", "m4s", "m4f", "m4a", "m4v", "aac", "ts")
                            if (ext.lowercase() !in validExtensions || !fullUrl.contains(".")) {
                                ext = "mp4" // Force proper init extension if obfuscated
                            }
                            val localFile = File(tmpDir, "init_${outputFileName}_$segmentIndex.$ext")

                            val mapSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(mapSpec)
                            } catch (e: Exception) {
                                networkFactory.open(mapSpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.name))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (line.startsWith("#EXT-X-KEY:URI=")) {
                        val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                        if (uriMatch != null && !uriMatch.groupValues[1].startsWith("data:")) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            val localFile = File(tmpDir, "key_${outputFileName}_$segmentIndex.bin")

                            val keySpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(keySpec)
                            } catch (e: Exception) {
                                networkFactory.open(keySpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.absolutePath))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (!line.startsWith("#")) {
                        val segmentUrl = if (line.startsWith("http")) line else java.net.URI(playlistUrl).resolve(line).toString()

                        val normalizedKey = segmentUrl.substringBefore("?")
                        val existingLocalName = processedSegments[normalizedKey]

                        if (existingLocalName != null) {
                            writeExportLog("Skipping duplicate segment write, reusing: $existingLocalName")
                            newLines.add(existingLocalName)
                            segmentIndex++
                            continue
                        }

                        var ext = segmentUrl.substringAfterLast(".", "ts").substringBefore("?")
                        val validExtensions = listOf("ts", "m4s", "mp4", "m4f", "m4a", "aac", "mp3", "webm", "m4v")
                        if (ext.lowercase() !in validExtensions || !segmentUrl.contains(".")) {
                            ext = if (isFmp4) "m4s" else "ts"
                        }
                        val localSegment = File(tmpDir, "seg_${outputFileName}_%05d.$ext".format(segmentIndex))

                        val baseSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(segmentUrl))
                        var cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(baseSpec)
                        var segmentSpec = baseSpec.buildUpon().setKey(cacheKey).build()

                        var success = false
                        var localSegmentWritten = false
                        try {
                            cacheOnlyFactory.open(segmentSpec)
                            val fos = java.io.FileOutputStream(localSegment)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                            }
                            fos.close()
                            localSegmentWritten = true
                        } catch (e: Exception) {
                            val msg = e.message ?: e.toString()
                            if (msg.contains("ENOSPC") || msg.contains("No space left")) {
                                throw java.io.IOException("TERMINAL_ENOSPC")
                            }
                            // Cache miss on primary URI. Fallback to direct SimpleCache file reading.
                            try {
                                val segUri = android.net.Uri.parse(segmentUrl)
                                val uriPath = segUri.path
                                if (uriPath != null) {
                                    val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                                    val keys = cache.keys

                                    // Match against the stripped path to handle domain changes safely
                                    val strippedUriPath = uriPath.substringBefore("?")
                                    val pathMatches = keys.filter { key ->
                                        runCatching { android.net.Uri.parse(key).path?.substringBefore("?") == strippedUriPath }.getOrDefault(false)
                                    }

                                    var matchedKey: String? = null
                                    if (pathMatches.isNotEmpty()) {
                                        // Prefer exact host match first
                                        matchedKey = pathMatches.firstOrNull { runCatching { android.net.Uri.parse(it).host == segUri.host }.getOrDefault(false) }
                                        // If no exact host match, and only one path match exists, assume it's a domain redirect and take it
                                        if (matchedKey == null && pathMatches.size == 1) {
                                            matchedKey = pathMatches.first()
                                        } else if (matchedKey == null && pathMatches.size > 1) {
                                            writeExportLog("AMBIGUOUS CACHE COLLISION: Multiple keys match path $strippedUriPath but none match host ${segUri.host}. Rejecting fallback.")
                                        }
                                    }

                                    if (matchedKey != null) {
                                        writeExportLog("Domain mismatch detected. Found segment in cache using path fallback: $matchedKey")

                                        val spans = cache.getCachedSpans(matchedKey)
                                            .filter { it.isCached && it.file != null && it.file!!.exists() }
                                            .sortedBy { it.position }

                                        if (spans.isNotEmpty()) {
                                            java.io.FileOutputStream(localSegment).use { output ->
                                                var expectedPosition = 0L
                                                var hasGap = false
                                                var gapPosition = 0L
                                                for (span in spans) {
                                                    if (span.position != expectedPosition) {
                                                        hasGap = true
                                                        gapPosition = expectedPosition
                                                        break
                                                    }
                                                    expectedPosition = span.position + span.length
                                                }

                                                if (hasGap) {
                                                    writeExportLog("ERROR: Cache gap detected at $gapPosition. Refusing to stitch corrupted segment silently.")
                                                    throw java.io.IOException("CACHE_GAP")
                                                } else {
                                                    for (span in spans) {
                                                        if (span.file != null && span.file!!.exists()) {
                                                            java.io.FileInputStream(span.file).use { input ->
                                                                val buffer = ByteArray(64 * 1024)
                                                                var read: Int
                                                                while (input.read(buffer).also { read = it } != -1) {
                                                                    output.write(buffer, 0, read)
                                                                }
                                                            }
                                                        } else {
                                                            throw java.io.IOException("CACHE_INCOMPLETE: Span file missing or null.")
                                                        }
                                                    }
                                                }
                                                output.flush()
                                            }
                                            localSegmentWritten = true
                                        } else {
                                            writeExportLog("DIRECT CACHE MISS: No spans found for $matchedKey")
                                        }
                                    }
                                }
                            } catch (fallbackEx: Exception) {
                                val fallbackMsg = fallbackEx.message ?: fallbackEx.toString()
                                if (fallbackMsg.contains("TERMINAL_ENOSPC") || fallbackMsg.contains("ENOSPC") || fallbackMsg.contains("No space left")) {
                                    throw java.io.IOException("TERMINAL_ENOSPC")
                                }
                                throw java.io.IOException("CACHE_GAP_OR_MISS")
                            }
                        } finally {
                            try { cacheOnlyFactory.close() } catch (ex: Exception) {}
                        }

                                                // Validate bytes instead of trusting URL suffix. KissKH names media chunks .png.
                        if (localSegmentWritten) {
                            if (isValidMediaSegment(localSegment, isFmp4)) {
                                success = true
                            } else {
                                writeExportLog("SIGNATURE REJECTION: Cached payload is not a recognized media segment; checking network recovery.")
                                localSegment.delete()
                                localSegmentWritten = false
                            }
                        }

                        // Targetted network recovery if cache miss or signature rejected
                        if (!success) {
                            try {
                                writeExportLog("Attempting targeted network recovery for missing or rejected segment: $segmentUrl")
                                networkFactory.open(segmentSpec)
                                val fos = java.io.FileOutputStream(localSegment)
                                val buffer = ByteArray(1024 * 64)
                                var bytesRead: Int
                                while (networkFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                }
                                fos.close()

                                val isNetworkValid = isValidMediaSegment(localSegment, isFmp4)
                                if (isNetworkValid) {
                                    success = true
                                    writeExportLog("Successfully recovered validated media segment from network (host=${android.net.Uri.parse(segmentUrl).host ?: "unknown"}).")
                                } else {
                                    writeExportLog("NETWORK REJECTION: Fallback response is not a recognized media segment; rejecting it.")
                                    localSegment.delete()
                                    success = false
                                }

                            } catch (recEx: Exception) {
                                val recMsg = recEx.message ?: recEx.toString()
                                if (recMsg.contains("ENOSPC") || recMsg.contains("No space left")) {
                                    throw java.io.IOException("TERMINAL_ENOSPC")
                                }
                                writeExportLog("Targeted network recovery failed: $recMsg")
                                success = false
                            } finally {
                                try { networkFactory.close() } catch (e: Exception) {}
                            }
                        }
if (!success) {
                            writeExportLog("Failed to read segment from cache: $segmentUrl")
                            throw Exception("Incomplete cache for segment: $segmentUrl")
                        }
                        processedSegments[normalizedKey] = localSegment.name

                        newLines.add(localSegment.name)
                        segmentIndex++
                    } else {
                        newLines.add(line)
                    }
                }

                val localPlaylist = File(tmpDir, outputFileName)
                localPlaylist.writeText(newLines.joinToString("\n"))
                return localPlaylist
            }

            writeExportLog(
                "HLS_EXPORT_VARIANTS videoHost=${android.net.Uri.parse(videoVariantUrl).host ?: "unknown"} " +
                    "videoPath=${android.net.Uri.parse(videoVariantUrl).path.orEmpty().substringBeforeLast("/")}/REDACTED.m3u8 " +
                    "separateAudio=${audioVariantUrl != null} selectedKeys=${streamKeyStrings.orEmpty().joinToString(",")}"
            )
            val videoPlaylistFile = processPlaylist(videoVariantUrl, "video_playlist.m3u8")
            var audioPlaylistFile: File? = null
            if (audioVariantUrl != null) {
                try {
                    audioPlaylistFile = processPlaylist(audioVariantUrl, "audio_playlist.m3u8")
                } catch(e: Exception) {
                    writeExportLog("Failed to process audio playlist, continuing without it: ${e.message}")
                }
            }

            writeExportLog("Successfully exported cached segments to tmp dir. Muxing to MP4 using FFmpeg.")

            val ffmpegArgs = mutableListOf("-allowed_extensions", "ALL", "-i", videoPlaylistFile.absolutePath)
            if (audioPlaylistFile != null) {
                ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", audioPlaylistFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0"))
            } else {
                ffmpegArgs.addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
            }
            ffmpegArgs.addAll(listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", out.absolutePath))

            val session = FFmpegKit.executeWithArguments(ffmpegArgs.toTypedArray())
            val returnCode = session.returnCode

            if (returnCode.isValueSuccess) {
                // Require FFmpeg to map a video and audio stream from the produced file.
                // "-show_entries" and "-of" are ffprobe-only flags, not FFmpeg flags.
                val probeSession = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-v", "error",
                        "-i", out.absolutePath,
                        "-map", "0:v:0",
                        "-map", "0:a:0",
                        "-c", "copy",
                        "-f", "null",
                        "-"
                    )
                )
                val probeOutput = probeSession.allLogsAsString
                if (!probeSession.returnCode.isValueSuccess) {
                    writeExportLog(
                        "HLS_EXPORT_INVALID_OUTPUT reason=missing_video_or_audio_stream " +
                            "probeExit=${probeSession.returnCode.value} probeLog=${probeOutput.lines().takeLast(12).joinToString(" | ")}"
                    )
                    throw Exception("Export validation failed: output must contain both video and audio tracks")
                }
                val hasVideo = true
                val hasAudio = true
                val fileSize = out.length()
                if (fileSize <= 0L) {
                    writeExportLog("HLS_EXPORT_INVALID_OUTPUT reason=empty_file sizeBytes=$fileSize")
                    throw Exception("HLS export produced an empty MP4")
                }
                writeExportLog("HLS_EXPORT_FILE_COMPLETE sizeBytes=$fileSize hasVideo=$hasVideo hasAudio=$hasAudio separateAudio=${audioPlaylistFile != null}")
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show() }
                writeExportLog("FFmpeg muxing complete: $title")
            } else {
                val tail = session.allLogsAsString.lines().takeLast(40).joinToString("\n")
                writeExportLog("FFmpeg FAILED rc=${session.returnCode.value} tail:\n$tail")
                throw Exception("FFmpeg failed with return code ${returnCode.value}")
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private suspend fun muxToMp4(
        url: String,
        title: String,
        splitAudioUrl: String? = null,
        streamKeyStrings: List<String>? = null,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val userAgent = requestHeaders.userAgent ?: ""
        val referer = requestHeaders.referer ?: ""
        val cookie = requestHeaders.cookie ?: ""

        val commandArgs = mutableListOf<String>()

        if (userAgent.isNotEmpty()) {
            commandArgs.add("-user_agent")
            commandArgs.add(userAgent)
        }

        val headers = mutableListOf<String>()
        if (referer.isNotEmpty()) headers.add("Referer: $referer")
        if (cookie.isNotEmpty()) headers.add("Cookie: $cookie")

        if (headers.isNotEmpty()) {
            commandArgs.add("-headers")
            commandArgs.add("${headers.joinToString("\r\n")}\r\n")
        }

        commandArgs.add("-allowed_extensions")
        commandArgs.add("ALL")

        commandArgs.add("-i")
        commandArgs.add(url)
        if (!splitAudioUrl.isNullOrEmpty()) {
            commandArgs.add("-i")
            commandArgs.add(splitAudioUrl)
            commandArgs.add("-map")
            commandArgs.add("0:v:0")
            commandArgs.add("-map")
            commandArgs.add("1:a:0")
        }
        commandArgs.add("-c")
        commandArgs.add("copy")

        if (!url.contains(".mp4", ignoreCase = true) && splitAudioUrl.isNullOrEmpty()) {
            commandArgs.add("-bsf:a")
            commandArgs.add("aac_adtstoasc")
        }

        commandArgs.add("-movflags")
        commandArgs.add("+faststart")

        commandArgs.add(out.absolutePath)

        Log.d(TAG, "Executing FFmpeg command with arguments: $commandArgs")

        // Execute with safe arguments list rather than string concatenation to prevent injection
        val session = FFmpegKit.executeWithArguments(commandArgs.toTypedArray())

        withContext(Dispatchers.Main) {
            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                writeExportLog("Transformer Export complete: $title")
                        Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
            } else {
                val errorLog = session.allLogsAsString
                Log.e(TAG, "FFmpeg failed: $errorLog")
                Toast.makeText(applicationContext, "Export error. See logcat.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun resolveVariantUrl(
        masterUrl: String,
        streamKeyStrings: List<String>?,
        requestHeaders: HlsDownloadHelper.RequestHeaders = HlsDownloadHelper.RequestHeaders.fromLegacyState()
    ): String = withContext(Dispatchers.IO) {
        if (!masterUrl.contains(".m3u8", true) || streamKeyStrings.isNullOrEmpty()) {
            return@withContext masterUrl
        }

        try {
            // Bug fix: Read master playlist FROM CACHE first. Live network master playlists drift over time (track group indices change),
            // which causes stale streamKeys to pick the wrong variant (audio-only bug).
            val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true, requestHeaders = requestHeaders)
            val dataSource = cacheFactory.createDataSource()
            val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(masterUrl))

            var masterText = ""
            try {
                dataSource.open(dataSpec)
                val buffer = ByteArray(1024 * 64)
                var bytesRead: Int
                val outputStream = java.io.ByteArrayOutputStream()
                while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                masterText = outputStream.toString("UTF-8")
            } catch (e: Exception) {
                // If not in cache, fallback to network (but risky for drift)
                masterText = HlsDownloadHelper.httpGetString(
                    masterUrl,
                    requestHeaders.userAgent,
                    requestHeaders.referer,
                    requestHeaders.cookie
                ) ?: return@withContext masterUrl
            } finally {
                dataSource.close()
            }

            val lines = masterText.lines()

            var variantIndex = 0
            val targetKey = streamKeyStrings.firstOrNull() ?: return@withContext masterUrl
            val parts = targetKey.split(",")
            if (parts.size < 2) return@withContext masterUrl
            // Parts[1] is the index of the track in the master playlist
            val targetVariantIndex = parts[1].toInt()

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    if (variantIndex == targetVariantIndex && i + 1 < lines.size) {
                        val variantLine = lines[i+1].trim()
                        return@withContext if (variantLine.startsWith("http")) {
                            variantLine
                        } else {
                            try {
                                java.net.URI(masterUrl).resolve(variantLine).toString()
                            } catch (e: Exception) {
                                val base = masterUrl.substringBeforeLast("/")
                                "$base/${variantLine.removePrefix("/")}"
                            }
                        }
                    }
                    variantIndex++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext masterUrl
    }

    private fun buildNotification(title: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading & Exporting Video")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Video Export", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress of exporting downloaded videos"
            }
            notificationManager?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
