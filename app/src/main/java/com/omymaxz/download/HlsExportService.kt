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
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.DashSegmentIndex
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
        const val EXTRA_MEDIA_ITEM_BUNDLE = "com.omymaxz.download.extra.MEDIA_ITEM_BUNDLE"

        const val CHANNEL_ID = "hls_export_channel"
        const val NOTIFICATION_ID = 3000
        private const val TAG = "HlsExportService"
    }


    private fun writeExportLog(message: String) {
        try {
            val logFile = java.io.File(applicationContext.filesDir, "export_logs.txt")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {}
    }

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
        val mimeType   = intent.getStringExtra(EXTRA_MIME_TYPE) ?: androidx.media3.common.MimeTypes.APPLICATION_M3U8

        android.util.Log.d("HLS_EXPORT_DEBUG", "[HLS_EXPORT_DEBUG] intentVideoUrl=$videoUrl, intentMimeType=$mimeType")
        val streamKeyStrings = intent.getStringArrayListExtra(EXTRA_STREAM_KEYS)
        val forceTransformer = intent.getBooleanExtra(EXTRA_FORCE_TRANSFORMER, false)
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
        } else if (bundledMediaItem == null && videoUrl != null) {
            bundledMediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(mimeType)
                .build()
        }

        intent.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

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
                    extraDownloadId != null -> exportFromDownloadId(extraDownloadId, title, mimeType)
                    bundledMediaItem != null -> {
                        // Ensure direct progressive URLs that bypass cache aren't sent to the manual cache-assembly script
                        if (mimeType == androidx.media3.common.MimeTypes.VIDEO_MP4 || mimeType == androidx.media3.common.MimeTypes.VIDEO_WEBM || mimeType == androidx.media3.common.MimeTypes.VIDEO_MATROSKA) {
                             if (videoUrl != null) {
                                 withContext(kotlinx.coroutines.Dispatchers.Main) { android.widget.Toast.makeText(applicationContext, "Starting background download...", android.widget.Toast.LENGTH_SHORT).show() }
                                 val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(title, android.net.Uri.parse(videoUrl)).build()
                                 androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, request, false)
                             }
                             if (activeExports.decrementAndGet() == 0) stopSelf(startId)
                             return@launch
                        }

                        // Use the new muxToMp4FromCache method which reads the exact cached segments based on the exact quality the user chose in the player.
                        try {
                            if (videoUrl != null) {
                                if (videoUrl.contains(".mp4", ignoreCase = true) && !videoUrl.contains(".m3u8", ignoreCase = true)) {
                                    copyMp4FromCache(videoUrl, title)
                                } else {
                                    muxToMp4FromCache(videoUrl, streamKeyStrings, title)
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
                                    val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                    muxToMp4(finalUrl, title)
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    videoUrl != null -> {
                        val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                        muxToMp4(finalUrl, title) // Fallback to FFmpeg
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

    private suspend fun exportFromDownloadId(extraDownloadId: String, title: String, mimeType: String?) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)
        val download = dm.downloadIndex.getDownload(extraDownloadId)
            ?: run {
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Video cache not found", Toast.LENGTH_SHORT).show() }
                return
            }

        // Check if fully cached. If yes, use Transformer. If not, fallback to FFmpeg network download.
        if (download.state == Download.STATE_COMPLETED) {
            // Strip streamKeys from the download mediaItem to prevent track index mismatch during export
            val rawMediaItem = download.request.toMediaItem()
            // CRITICAL FIX: The original download request might not have stored the explicit MIME type,
            // relying on its own extension parser. We MUST re-inject the explicitly passed MIME type
            // into the MediaItem here so Transformer doesn't fallback to ProgressiveMediaPeriod on extensionless URLs.
            val mediaItem = rawMediaItem.buildUpon()
                .setMimeType(mimeType)
                .setStreamKeys(emptyList())
                .build()
            try {
                muxToMp4WithTransformer(mediaItem, title)
            } catch (e: Exception) {
                writeExportLog("Transformer failed on downloaded item, falling back to muxToMp4FromCache: ${e.message}")
                val url = download.request.uri.toString()
                val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }

                try {
                    if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                        copyMp4FromCache(url, title)
                    } else {
                        muxToMp4FromCache(url, streamKeysStr, title)
                    }
                } catch (cacheEx: Exception) {
                    writeExportLog("Cache export failed, falling back to network FFmpeg: ${cacheEx.message}")
                    if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                        withContext(Dispatchers.Main) { android.widget.Toast.makeText(applicationContext, "Starting background download...", android.widget.Toast.LENGTH_SHORT).show() }
                        val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(title, android.net.Uri.parse(url)).build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, request, false)
                    } else {
                        val finalUrl = resolveVariantUrl(url, streamKeysStr)
                        muxToMp4(finalUrl, title)
                    }
                }
            }
        } else {
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            if (url.contains(".mp4", ignoreCase = true) && !url.contains(".m3u8", ignoreCase = true)) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(applicationContext, "Starting background download...", android.widget.Toast.LENGTH_SHORT).show() }
                val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(title, android.net.Uri.parse(url)).build()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, request, false)
            } else {
                val finalUrl = resolveVariantUrl(url, streamKeysStr)
                muxToMp4(finalUrl, title)
            }
        }
    }

    private suspend fun muxToMp4WithTransformer(mediaItem: MediaItem, title: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            android.util.Log.d("HLS_EXPORT_DEBUG", "[HLS_EXPORT_DEBUG] muxToMp4WithTransformer MediaItem URI=${mediaItem.localConfiguration?.uri} MIME=${mediaItem.localConfiguration?.mimeType}")
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


    private fun cacheOnlyDataSource(): androidx.media3.datasource.DataSource {
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

    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
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

        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)

        val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
        if (spans.isEmpty()) {
            writeExportLog("No cache spans found for key: $cacheKey")
            throw Exception("No cache spans found for MP4")
        }

        try {
            writeExportLog("Copying MP4 from cache via direct spans (${spans.size} found) for key: $cacheKey")
            out.outputStream().use { fos ->
                for (span in spans) {
                    if (span.file != null && span.file!!.exists()) {
                        span.file!!.inputStream().use { fis ->
                            fis.copyTo(fos)
                        }
                    }
                }
            }

            // Notify MediaStore
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, out.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = applicationContext.contentResolver
                val targetUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        out.inputStream().use { input -> input.copyTo(output) }
                    }
                    out.delete()
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache spans: ${e.message}")
            throw Exception("Failed to copy MP4 from cache spans", e)
        }
    }

    private suspend fun muxToMp4FromCache(masterUrl: String, streamKeyStrings: List<String>?, title: String) = withContext(Dispatchers.IO) {
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
            val networkFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = false).createDataSource()

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

            if (masterText.isEmpty()) {
                throw Exception("Failed to fetch manifest")
            }

            val isDash = masterText.contains("<MPD")
            val isHls = masterText.contains("#EXTM3U")

            if (!isDash && !isHls) {
                throw Exception("Unrecognized manifest format")
            }

            val ffmpegArgs = mutableListOf<String>()

            if (isDash) {
                writeExportLog("Parsing DASH manifest...")
                val streamKeys = streamKeyStrings?.mapNotNull {
                    val parts = it.split(",")
                    if (parts.size >= 2) androidx.media3.common.StreamKey(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0) else null
                } ?: emptyList()

                val parser = DashManifestParser()
                val masterInputStream = masterText.byteInputStream(Charsets.UTF_8)
                val dashManifest = parser.parse(android.net.Uri.parse(masterUrl), masterInputStream)
                val filteredManifest = dashManifest.copy(streamKeys)

                val videoSegments = mutableListOf<File>()
                val audioSegments = mutableListOf<File>()

                for (periodIndex in 0 until filteredManifest.getPeriodCount()) {
                    val period = filteredManifest.getPeriod(periodIndex)
                    for (adaptationSet in period.adaptationSets) {
                        val isVideo = adaptationSet.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
                        for (representation in adaptationSet.representations) {
                            val repId = representation.format.id ?: "unknown"
                            val index = representation.index
                            if (index == null) continue

                            val repBaseUrl = representation.baseUrls.firstOrNull()?.url ?: masterUrl
                            val localFile = File(tmpDir, "dash_${if (isVideo) "v" else "a"}_${repId}.mp4")
                            val fos = java.io.FileOutputStream(localFile)

                            suspend fun writeRangedUri(rangedUri: androidx.media3.exoplayer.dash.manifest.RangedUri?) {
                                if (rangedUri == null) return
                                val segmentUrl = rangedUri.resolveUri(repBaseUrl).toString()
                                val dataSpecBuilder = androidx.media3.datasource.DataSpec.Builder()
                                    .setUri(android.net.Uri.parse(segmentUrl))

                                if (rangedUri.length != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                                    dataSpecBuilder.setPosition(rangedUri.start)
                                    dataSpecBuilder.setLength(rangedUri.length)
                                }

                                val baseSpec = dataSpecBuilder.build()
                                val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(baseSpec)
                                val segmentSpec = baseSpec.buildUpon().setKey(cacheKey).build()

                                try {
                                    cacheOnlyFactory.open(segmentSpec)
                                    val buffer = ByteArray(1024 * 64)
                                    var bytesRead: Int
                                    while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                        fos.write(buffer, 0, bytesRead)
                                    }
                                } catch (e: Exception) {
                                    try {
                                        networkFactory.open(segmentSpec)
                                        val buffer = ByteArray(1024 * 64)
                                        var bytesRead: Int
                                        while (networkFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                            fos.write(buffer, 0, bytesRead)
                                        }
                                    } finally {
                                        try { networkFactory.close() } catch(e: Exception) {}
                                    }
                                } finally {
                                    try { cacheOnlyFactory.close() } catch(e: Exception) {}
                                }
                            }

                            writeRangedUri(representation.initializationUri)
                            val segmentCount = index.getSegmentCount(androidx.media3.common.C.TIME_UNSET)
                            for (i in 0 until segmentCount) {
                                val segmentNum = index.getFirstSegmentNum() + i
                                writeRangedUri(index.getSegmentUrl(segmentNum))
                            }

                            fos.close()
                            if (isVideo) videoSegments.add(localFile) else audioSegments.add(localFile)
                        }
                    }
                }

                if (videoSegments.isEmpty() && audioSegments.isEmpty()) {
                    throw Exception("No DASH segments found to export")
                }

                val videoFile = videoSegments.firstOrNull()
                val audioFile = audioSegments.firstOrNull()

                if (videoFile != null) {
                    ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", videoFile.absolutePath))
                }
                if (audioFile != null) {
                    ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", audioFile.absolutePath))
                    if (videoFile != null) {
                        ffmpegArgs.addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
                    } else {
                        ffmpegArgs.addAll(listOf("-map", "0:a:0"))
                    }
                } else if (videoFile != null) {
                    ffmpegArgs.addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
                }
            } else {
                val masterLines = masterText.lines()
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
                        val filteredPlaylist = parsedPlaylist.copy(streamKeys) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
                        // After filtering by streamKeys, the remaining variant/audio in the lists are the exact ones the user downloaded!
                        videoVariantUrl = filteredPlaylist.variants.firstOrNull()?.url?.toString() ?: masterUrl
                        audioVariantUrl = filteredPlaylist.audios.firstOrNull()?.url?.toString()
                    }
                } catch (e: Exception) {
                    writeExportLog("Failed to parse master playlist with HlsPlaylistParser: ${e.message}")
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
                        try {
                            cacheOnlyFactory.open(segmentSpec)
                            val fos = java.io.FileOutputStream(localSegment)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                            }
                            fos.close()
                            success = true
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

                                        var targetedRecoveryNeeded = false
                                        // BULLETPROOF FIX: Read directly from SimpleCache spans, bypassing CacheDataSource entirely.
                                        val spans = cache.getCachedSpans(matchedKey).filter { it.length > 0 }.sortedBy { it.position }

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
                                            // --- DEFENSIVE SIGNATURE CHECK ---
                                            var isValidSignature = true
                                            try {
                                                java.io.FileInputStream(localSegment).use { sigInput ->
                                                    val header = ByteArray(12)
                                                    val bytesRead = sigInput.read(header)
                                                    if (bytesRead >= 8) {
                                                        // Check for PNG: 89 50 4E 47 0D 0A 1A 0A
                                                        if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                                                            isValidSignature = false
                                                            writeExportLog("SIGNATURE REJECTION: File contains PNG image data instead of media. Key: $matchedKey")
                                                        }
                                                        // Check for JPEG: FF D8 FF
                                                        else if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                                                            isValidSignature = false
                                                            writeExportLog("SIGNATURE REJECTION: File contains JPEG image data instead of media. Key: $matchedKey")
                                                        }
                                                        // Check for GIF: GIF8
                                                        else if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()) {
                                                            isValidSignature = false
                                                            writeExportLog("SIGNATURE REJECTION: File contains GIF image data instead of media. Key: $matchedKey")
                                                        }
                                                        // Additional heuristic container checking
                                                        else if (!isFmp4 && header[0] != 0x47.toByte() && header[0] != 'I'.code.toByte() && header[0] != 'R'.code.toByte()) {
                                                            // 0x47 is MPEG-TS sync byte. ID3 tags often start with 'ID3'. RIFF (wav/avi) with 'RIFF'.
                                                            // For TS, if it's not starting with 0x47 or an ID3 tag, it might be heavily corrupted or encrypted.
                                                            // We will not strictly block it here unless it's a known bad image type,
                                                            // but we log a warning.
                                                            writeExportLog("WARNING: MPEG-TS segment does not start with 0x47 sync byte or ID3 tag. Header: ${header.take(4).joinToString("") { String.format("%02X", it) }}")
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                writeExportLog("WARNING: Failed to read signature for $localSegment: ${e.message}")
                                            }

                                            if (isValidSignature) {
                                                success = true
                                                writeExportLog("DIRECT CACHE HIT: Copied segment via SimpleCache spans for $matchedKey")
                                            } else {
                                                localSegment.delete()
                                                success = false // Let network fallback take over or fail gracefully
                                            }
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
                                if (fallbackMsg.contains("CACHE_GAP") || fallbackMsg.contains("CACHE_INCOMPLETE") || fallbackMsg.contains("SIGNATURE REJECTION")) {
                                    // Missing data, trigger targeted recovery
                                    try {
                                        writeExportLog("Attempting targeted network recovery for missing segment: $segmentUrl")
                                        networkFactory.open(segmentSpec)
                                        val fos = java.io.FileOutputStream(localSegment)
                                        val buffer = ByteArray(1024 * 64)
                                        var bytesRead: Int
                                        while (networkFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                            fos.write(buffer, 0, bytesRead)
                                        }
                                        fos.close()
                                        success = true
                                        writeExportLog("Successfully recovered segment from network: $segmentUrl")
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
                                } else {
                                    writeExportLog("Fallback cache lookup failed: $fallbackMsg")
                                }
                            }
                        } finally {
                            try { cacheOnlyFactory.close() } catch (ex: Exception) {}
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

                ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", videoPlaylistFile.absolutePath))
                if (audioPlaylistFile != null) {
                    ffmpegArgs.addAll(listOf("-allowed_extensions", "ALL", "-i", audioPlaylistFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0"))
                } else {
                    ffmpegArgs.addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
                }
            } // End of HLS else branch

            ffmpegArgs.addAll(listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", out.absolutePath))

            val session = FFmpegKit.executeWithArguments(ffmpegArgs.toTypedArray())
            val returnCode = session.returnCode

            if (returnCode.isValueSuccess) {
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

    private suspend fun muxToMp4(url: String, title: String) = withContext(Dispatchers.IO) {
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

        val userAgent = HlsDownloadHelper.currentUserAgent ?: ""
        val referer = HlsDownloadHelper.currentReferer ?: ""
        val cookie = HlsDownloadHelper.currentCookie ?: ""

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

        commandArgs.add("-c")
        commandArgs.add("copy")

        if (!url.contains(".mp4", ignoreCase = true)) {
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

    private suspend fun resolveVariantUrl(masterUrl: String, streamKeyStrings: List<String>?): String = withContext(Dispatchers.IO) {
        if (!masterUrl.contains(".m3u8", true) || streamKeyStrings.isNullOrEmpty()) {
            return@withContext masterUrl
        }

        try {
            // Bug fix: Read master playlist FROM CACHE first. Live network master playlists drift over time (track group indices change),
            // which causes stale streamKeys to pick the wrong variant (audio-only bug).
            val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)
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
                val userAgent = HlsDownloadHelper.currentUserAgent
                val cookie = HlsDownloadHelper.currentCookie
                val referer = HlsDownloadHelper.currentReferer
                masterText = HlsDownloadHelper.httpGetString(masterUrl, userAgent, referer, cookie) ?: return@withContext masterUrl
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
