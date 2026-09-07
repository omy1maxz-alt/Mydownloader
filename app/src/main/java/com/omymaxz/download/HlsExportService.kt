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
            // Bug fix: Do NOT apply stale streamKeys to the MediaItem.
            // HLS track group indices can shift between cache time and export time (e.g. ad insertion), causing audio-only exports.
            // Let Transformer / AssetLoader auto-select the best tracks (MIME-type based) at export time.
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
                    extraDownloadId != null -> exportFromDownloadId(extraDownloadId, title)
                    bundledMediaItem != null -> {
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
                val finalUrl = resolveVariantUrl(url, streamKeysStr)
                try {
                    muxToMp4FromCache(finalUrl, title)
                } catch (cacheEx: Exception) {
                    writeExportLog("muxToMp4FromCache failed, falling back to FFmpeg network: ${cacheEx.message}")
                    muxToMp4(finalUrl, title)
                }
            }
        } else {
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            val finalUrl = resolveVariantUrl(url, streamKeysStr)
            muxToMp4(finalUrl, title)
        }
    }

    private suspend fun muxToMp4WithTransformer(mediaItem: MediaItem, title: String) =
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


    private suspend fun muxToMp4FromCache(finalUrl: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        if (out.exists()) out.delete()

        val tmpDir = File(applicationContext.filesDir, "tmp_export_${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        try {
            val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)
            val dataSource = cacheFactory.createDataSource()

            // Read the main playlist
            val dataSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(finalUrl))
            var playlistContent = ""
            try {
                dataSource.open(dataSpec)
                val buffer = ByteArray(1024 * 64)
                var bytesRead: Int
                val outputStream = java.io.ByteArrayOutputStream()
                while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                playlistContent = outputStream.toString("UTF-8")
            } finally {
                dataSource.close()
            }

            if (playlistContent.isEmpty() || !playlistContent.contains("#EXTM3U")) {
                throw Exception("Failed to read valid M3U8 from cache.")
            }

            // Parse and cache segments locally
            val lines = playlistContent.lines()
            val newLines = mutableListOf<String>()
            var segmentIndex = 0

            // Optional ad stripping: Detect short discontinuity blocks
            // First pass: group segments by discontinuity blocks
            data class DiscontinuityBlock(val startIndex: Int, val endIndex: Int, val duration: Double)
            val blocks = mutableListOf<DiscontinuityBlock>()
            var currentBlockDuration = 0.0
            var currentBlockStart = 0

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    blocks.add(DiscontinuityBlock(currentBlockStart, i, currentBlockDuration))
                    currentBlockDuration = 0.0
                    currentBlockStart = i + 1
                } else if (line.startsWith("#EXTINF:")) {
                    try {
                        val durationStr = line.substringAfter("#EXTINF:").substringBefore(",")
                        currentBlockDuration += durationStr.toDouble()
                    } catch (e: Exception) {}
                }
                i++
            }
            blocks.add(DiscontinuityBlock(currentBlockStart, lines.size, currentBlockDuration))

            // Heuristic: If a block is < 90 seconds and surrounded by discontinuities, it MIGHT be an ad.
            // Only strip if there are multiple blocks and one is significantly shorter than the main content.
            val totalDuration = blocks.sumOf { it.duration }
            val mainContentBlock = blocks.maxByOrNull { it.duration }
            val blocksToKeep = blocks.filter { it.duration >= 90.0 || it == mainContentBlock || blocks.size < 3 }.toSet()

            var currentBlockIndex = 0
            var inIgnoredBlock = false

            for (line in lines) {
                if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    currentBlockIndex++
                    inIgnoredBlock = !blocksToKeep.contains(blocks[currentBlockIndex])
                    if (!inIgnoredBlock) {
                        newLines.add(line)
                    }
                    continue
                }

                if (line.isBlank()) continue

                if (!line.startsWith("#")) {
                    if (inIgnoredBlock) continue

                    // Segment URL
                    val segmentUrl = if (line.startsWith("http")) line else android.net.Uri.parse(finalUrl).let {
                        val path = it.path ?: ""
                        val basePath = path.substringBeforeLast("/")
                        "${it.scheme}://${it.host}$basePath/$line"
                    }

                    val segmentSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(segmentUrl))
                    val localSegment = File(tmpDir, String.format("seg_%05d.ts", segmentIndex))
                    try {
                        dataSource.open(segmentSpec)
                        val fos = java.io.FileOutputStream(localSegment)
                        val buffer = ByteArray(1024 * 64)
                        var bytesRead: Int
                        while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.close()
                    } catch (e: Exception) {
                        writeExportLog("Failed to read segment from cache: $segmentUrl")
                        // If we fail to read a segment, we might have an incomplete cache. Throw to trigger network fallback.
                        throw Exception("Incomplete cache for segment: $segmentUrl", e)
                    } finally {
                        dataSource.close()
                    }

                    newLines.add(localSegment.name)
                    segmentIndex++
                } else {
                    if (!inIgnoredBlock || line.startsWith("#EXT-X-VERSION") || line.startsWith("#EXT-X-TARGETDURATION") || line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-PLAYLIST-TYPE") || line.startsWith("#EXT-X-ENDLIST")) {
                        newLines.add(line)
                    }
                }
            }

            val localPlaylist = File(tmpDir, "playlist.m3u8")
            localPlaylist.writeText(newLines.joinToString("\n"))

            writeExportLog("Successfully exported cached segments to tmp dir. Muxing to MP4 using FFmpeg.")

            val ffmpegArgs = mutableListOf(
                "-allowed_extensions", "ALL",
                "-i", localPlaylist.absolutePath,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                out.absolutePath
            )

            val session = FFmpegKit.executeWithArguments(ffmpegArgs.toTypedArray())
            val returnCode = session.returnCode

            if (returnCode.isValueSuccess) {
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show() }
                writeExportLog("FFmpeg muxing complete: $title")
            } else {
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

        commandArgs.add("-bsf:a")
        commandArgs.add("aac_adtstoasc")

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
            val userAgent = HlsDownloadHelper.currentUserAgent
            val cookie = HlsDownloadHelper.currentCookie
            val referer = HlsDownloadHelper.currentReferer

            val masterText = HlsDownloadHelper.httpGetString(masterUrl, userAgent, referer, cookie) ?: return@withContext masterUrl
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
