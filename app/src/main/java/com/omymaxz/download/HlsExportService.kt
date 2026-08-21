package com.omymaxz.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class HlsExportService : Service() {

    companion object {
        const val EXTRA_DOWNLOAD_ID = "com.omymaxz.download.extra.DOWNLOAD_ID"
        const val EXTRA_TITLE = "com.omymaxz.download.extra.TITLE"
        const val EXTRA_URL = "com.omymaxz.download.extra.URL"
        // NEW: lets the caller choose "just export what's cached" vs "complete the missing
        // parts and export the full video" instead of the service always doing the latter.
        const val EXTRA_CACHED_ONLY = "com.omymaxz.download.extra.CACHED_ONLY"
        const val CHANNEL_ID = "hls_export_channel"
        const val NOTIFICATION_ID = 3000
        private const val TAG = "HlsExportService"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var notificationManager: NotificationManager? = null
    private var activeExports = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown Video"
        val cachedOnly = intent.getBooleanExtra(EXTRA_CACHED_ONLY, false)

        if (downloadId == null && url == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Video to Downloads Folder")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        activeExports.incrementAndGet()

        serviceScope.launch {
            try {
                if (downloadId != null) {
                    exportHlsToMp4(downloadId, title)
                } else if (url != null) {
                    exportHlsToMp4FromUrl(url, title, cachedOnly)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                Toast.makeText(applicationContext, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (activeExports.decrementAndGet() == 0) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    // Unchanged: this path exports a DownloadManager-completed (fully downloaded) item, so
    // there's nothing partial to reason about.
    private suspend fun exportHlsToMp4(downloadId: String, title: String) = suspendCancellableCoroutine { continuation ->
        val downloadManager = HlsDownloadHelper.getDownloadManager(applicationContext)
        val download = downloadManager.downloadIndex.getDownload(downloadId)

        if (download == null) {
            Log.e(TAG, "Download not found in index for id: $downloadId")
            Toast.makeText(applicationContext, "Video cache not found", Toast.LENGTH_SHORT).show()
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val mediaItem = download.request.toMediaItem()
        val cacheDataSourceFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

        val transformer = buildTransformer(cacheDataSourceFactory, title) { continuation.resume(Unit) }

        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(downloadsDir, "$sanitizedTitle.mp4")

        transformer.start(mediaItem, outFile.absolutePath)
        continuation.invokeOnCancellation { transformer.cancel() }
    }

    /**
     * REWRITTEN. Previously this always ran Transformer over the full, unclipped manifest
     * duration — meaning every export behaved like a fresh full download regardless of how
     * much was actually cached. Now it explicitly branches:
     *
     *  - cachedOnly = true  -> exports only the contiguous run of already-cached segments,
     *    clipped with MediaItem.ClippingConfiguration. No network touched, no filler duration.
     *  - cachedOnly = false -> first fetches ONLY the segments that are missing (through a
     *    writable cache factory, so the fetch benefits future playback too), then runs the
     *    read-only Transformer pass over the now-complete cache.
     */
    private suspend fun exportHlsToMp4FromUrl(url: String, title: String, cachedOnly: Boolean) {
        val userAgent = HlsDownloadHelper.currentUserAgent
        val cookie = HlsDownloadHelper.currentCookie

        // Subtitle sidecar wasn't being fetched at all for this path before — now it is,
        // so an exported MP4 has a matching .vtt/.srt file CustomPlayerActivity can attach.
        HlsDownloadHelper.fetchAndCacheSubtitles(applicationContext, url, title, userAgent, cookie)

        val segments = HlsDownloadHelper.inspectSegments(applicationContext, url, userAgent, cookie)
        if (segments.isEmpty()) {
            Toast.makeText(applicationContext, "Could not read playlist segments", Toast.LENGTH_LONG).show()
            return
        }

        val clipEndMs: Long
        if (cachedOnly) {
            val firstMissingIndex = segments.indexOfFirst { !it.isCached }
            if (firstMissingIndex == 0) {
                Toast.makeText(applicationContext, "Nothing cached yet — play more of the video first", Toast.LENGTH_LONG).show()
                return
            }
            clipEndMs = if (firstMissingIndex == -1) {
                segments.last().let { it.startMs + it.durationMs }
            } else {
                segments[firstMissingIndex - 1].let { it.startMs + it.durationMs }
            }
        } else {
            val missing = segments.filterNot { it.isCached }
            if (missing.isNotEmpty()) {
                fetchMissingSegments(missing)
            }
            clipEndMs = segments.last().let { it.startMs + it.durationMs }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(clipEndMs)
                    .build()
            )
            .build()

        val cacheDataSourceFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = buildTransformer(cacheDataSourceFactory, title) { continuation.resume(Unit) }
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(downloadsDir, "$sanitizedTitle.mp4")
            transformer.start(mediaItem, outFile.absolutePath)
            continuation.invokeOnCancellation { transformer.cancel() }
        }
    }

    /**
     * Downloads exactly the segments that are missing, through a WRITABLE cache factory (so
     * the bytes land in the shared cache and benefit future playback/export too) — nothing
     * that's already cached is touched or re-fetched.
     */
    private suspend fun fetchMissingSegments(missing: List<HlsDownloadHelper.SegmentCacheInfo>) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val writableFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = false)
            val dataSource = writableFactory.createDataSource()
            missing.forEach { segment ->
                try {
                    val dataSpec = DataSpec.Builder()
                        .setUri(Uri.parse(segment.uri))
                        .setKey(segment.cacheKey)
                        .build()
                    dataSource.open(dataSpec)
                    val buffer = ByteArray(64 * 1024)
                    while (dataSource.read(buffer, 0, buffer.size) != -1) {
                        // Bytes are written into the shared cache as they're read; nothing else to do.
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch missing segment ${segment.uri}", e)
                } finally {
                    dataSource.close()
                }
            }
        }
    }

    private fun buildTransformer(
        cacheDataSourceFactory: CacheDataSource.Factory,
        title: String,
        onDone: () -> Unit
    ): Transformer {
        return Transformer.Builder(applicationContext)
            .setAssetLoaderFactory(
                androidx.media3.transformer.DefaultAssetLoaderFactory(
                    applicationContext,
                    androidx.media3.transformer.DefaultDecoderFactory(applicationContext),
                    /* forceAudioTrack= */ false,
                    androidx.media3.common.util.Clock.DEFAULT,
                    DefaultMediaSourceFactory(applicationContext).setDataSourceFactory(cacheDataSourceFactory),
                    androidx.media3.datasource.DataSourceBitmapLoader(applicationContext)
                )
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
                    onDone()
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Transformer Error", exportException)
                    Toast.makeText(applicationContext, "Export error: ${exportException.message}", Toast.LENGTH_LONG).show()
                    onDone()
                }
            })
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of exporting downloaded videos"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}