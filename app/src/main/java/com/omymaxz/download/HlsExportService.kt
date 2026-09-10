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
                    extraDownloadId != null -> exportFromDownloadId(extraDownloadId, title)
                    bundledMediaItem != null -> {
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

    private suspend fun exportFromDownloadId(extraDownloadId: String, title: String) {
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
            val mediaItem = rawMediaItem.buildUpon().setStreamKeys(emptyList()).build()
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
            val out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$safeTitle.mp4"
            )
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
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        if (out.exists()) out.delete()

        val cacheOnlyFactory = cacheOnlyDataSource()
        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val dataSpec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(uri)
            .setKey(cacheKey)
            .build()

        try {
            writeExportLog("Reading MP4 directly from cache for: $url")
            cacheOnlyFactory.open(dataSpec)
            out.outputStream().use { fos ->
                val buffer = ByteArray(1024 * 256)
                var bytesRead: Int
                while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache: ${e.message}")
            throw Exception("Failed to copy MP4 from cache", e)
        } finally {
            try {
                cacheOnlyFactory.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private suspend fun muxToMp4FromCache(masterUrl: String, streamKeyStrings: List<String>?, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        if (out.exists()) out.delete()

        val tmpDir = File(applicationContext.filesDir, "tmp_export_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        try {
            val cacheOnlyFactory = cacheOnlyDataSource()
            val networkFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = false).createDataSource()

            val masterText = HlsDownloadHelper.httpGetString(masterUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentReferer, HlsDownloadHelper.currentCookie) ?: throw Exception("Failed to fetch master playlist")
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
                            if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php")) {
                                ext = "mp4" // Assuming fMP4 init chunks shouldn't be fake images either
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
                        var ext = segmentUrl.substringAfterLast(".", "ts").substringBefore("?")
                        // FFmpeg strictly blocks non-media extensions (like .PNG obfuscation) in LOCAL playlists for security.
                        // We must normalize image/fake extensions back to .ts, while preserving real fMP4 extensions.
                        if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php") || !segmentUrl.contains(".")) {
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
                            // Cache miss. ExoPlayer might have cached this segment under a redirected domain.
                            // We will scan the unified cache keys for any key that ends with the same path structure.
                            try {
                                val uriPath = android.net.Uri.parse(segmentUrl).path
                                if (uriPath != null) {
                                    val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                                    val keys = cache.keys
                                    val matchedKey = keys.firstOrNull { it.endsWith(uriPath) }
                                    if (matchedKey != null) {
                                        writeExportLog("Domain mismatch detected. Found segment in cache using path fallback: $matchedKey")
                                        cacheKey = matchedKey
                                        segmentSpec = androidx.media3.datasource.DataSpec.Builder()
                                            .setUri(android.net.Uri.parse(matchedKey))
                                            .setKey(cacheKey)
                                            .build()

                                        cacheOnlyFactory.close() // ensure clean state
                                        cacheOnlyFactory.open(segmentSpec)
                                        val fos = java.io.FileOutputStream(localSegment)
                                        val buffer = ByteArray(1024 * 64)
                                        var bytesRead: Int
                                        while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                                            fos.write(buffer, 0, bytesRead)
                                        }
                                        fos.close()
                                        success = true
                                    }
                                }
                            } catch (fallbackEx: Exception) {
                                writeExportLog("Fallback cache lookup failed for: $segmentUrl")
                            }
                        } finally {
                            cacheOnlyFactory.close()
                        }

                        if (!success) {
                            writeExportLog("Failed to read segment from cache: $segmentUrl")
                            throw Exception("Incomplete cache for segment: $segmentUrl")
                        }

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
        val out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )

        if (out.exists()) out.delete()

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
