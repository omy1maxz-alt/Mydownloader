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
import androidx.media3.exoplayer.offline.DownloadManager

class HlsExportService : Service() {

    companion object {
        const val EXTRA_DOWNLOAD_ID = "com.omymaxz.download.extra.DOWNLOAD_ID"
        const val EXTRA_TITLE = "com.omymaxz.download.extra.TITLE"
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
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown Video"

        if (downloadId == null) {
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
                exportHlsToMp4(downloadId, title)
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
        val cacheDataSourceFactory = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext)

        // Setup MediaSourceFactory to read from Cache
        // Ensure Transformer reads using our cache datasource
        val transformer = Transformer.Builder(applicationContext)
            .setAssetLoaderFactory(androidx.media3.transformer.DefaultAssetLoaderFactory(
                applicationContext,
                androidx.media3.transformer.DefaultDecoderFactory(applicationContext),
                /* forceAudioTrack= */ false,
                androidx.media3.common.util.Clock.DEFAULT,
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(applicationContext)
                    .setDataSourceFactory(cacheDataSourceFactory),
                androidx.media3.datasource.DataSourceBitmapLoader(applicationContext)
            ))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
                    downloadManager.removeDownload(downloadId)
                    continuation.resume(Unit)
                }

                override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "Transformer Error", exportException)
                    Toast.makeText(applicationContext, "Export error: ${exportException.message}", Toast.LENGTH_LONG).show()
                    continuation.resume(Unit)
                }
            })
            .build()

        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val outFile = File(downloadsDir, "$sanitizedTitle.mp4")

        transformer.start(mediaItem, outFile.absolutePath)

        continuation.invokeOnCancellation {
            transformer.cancel()
        }
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
