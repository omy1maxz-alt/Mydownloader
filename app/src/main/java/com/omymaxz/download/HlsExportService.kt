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

        const val CHANNEL_ID = "hls_export_channel"
        const val NOTIFICATION_ID = 3000
        private const val TAG = "HlsExportService"
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

        intent.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

        if (videoUrl == null && extraDownloadId == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(title))
        activeExports.incrementAndGet()

        serviceScope.launch {
            try {
                when {
                    extraDownloadId != null -> exportFromDownloadId(extraDownloadId, title)
                    videoUrl != null -> {
                        val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)

                        // Check if the exact variant is already cached. If so, use Transformer to instantly export without network usage!
                        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                        val cacheKey = androidx.media3.datasource.cache.CacheKeyFactory.DEFAULT.buildCacheKey(androidx.media3.datasource.DataSpec(android.net.Uri.parse(finalUrl)))

                        // A rough check: If there's any data cached for this key, attempt Transformer export.
                        val isCached = cache.getCachedSpans(cacheKey).isNotEmpty()

                        if (isCached) {
                            val mediaItem = MediaItem.Builder()
                                .setUri(android.net.Uri.parse(finalUrl))
                                .setMimeType(mimeType)
                                .build()
                            muxToMp4WithTransformer(mediaItem, title)
                        } else {
                            muxToMp4(finalUrl, title)
                        }
                    }
                }
            } catch (t: Throwable) {
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
            val url = download.request.uri.toString()
            val streamKeysStr = download.request.streamKeys.map { "${it.groupIndex},${it.streamIndex}" }
            val finalUrl = resolveVariantUrl(url, streamKeysStr)

            val mediaItem = MediaItem.Builder()
                .setUri(android.net.Uri.parse(finalUrl))
                .setMimeType(download.request.mimeType)
                .build()

            muxToMp4WithTransformer(mediaItem, title)
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

            @Suppress("DEPRECATION")
            val transformer = Transformer.Builder(applicationContext)
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
                        Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        result: ExportResult, ex: ExportException
                    ) {
                        Log.e(TAG, "Transformer error", ex)
                        Toast.makeText(applicationContext, "Export error: ${ex.message}", Toast.LENGTH_LONG).show()
                        if (cont.isActive) cont.resume(Unit)
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

        val sb = StringBuilder()
        if (userAgent.isNotEmpty()) {
            sb.append("-user_agent \"$userAgent\" ")
        }
        val headers = mutableListOf<String>()
        if (referer.isNotEmpty()) headers.add("Referer: $referer")
        if (cookie.isNotEmpty()) headers.add("Cookie: $cookie")

        if (headers.isNotEmpty()) {
            sb.append("-headers \"${headers.joinToString("\r\n")}\r\n\" ")
        }

        // Fast copy, no re-encoding
        sb.append("-i \"$url\" -c copy -bsf:a aac_adtstoasc \"${out.absolutePath}\"")

        val command = sb.toString()
        Log.d(TAG, "Executing FFmpeg command: $command")

        val session = FFmpegKit.execute(command)

        withContext(Dispatchers.Main) {
            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
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
