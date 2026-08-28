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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Util
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

class HlsExportService : Service() {

    companion object {
        const val EXTRA_DOWNLOAD_ID    = "com.omymaxz.download.extra.DOWNLOAD_ID"
        const val EXTRA_TITLE          = "com.omymaxz.download.extra.TITLE"
        const val EXTRA_URL            = "com.omymaxz.download.extra.URL"
        const val EXTRA_USER_AGENT     = "com.omymaxz.download.extra.USER_AGENT"
        const val EXTRA_REFERER        = "com.omymaxz.download.extra.REFERER"
        const val EXTRA_COOKIE         = "com.omymaxz.download.extra.COOKIE"
        const val EXTRA_VARIANT_INDEX  = "com.omymaxz.download.extra.VARIANT_INDEX"
        const val CHANNEL_ID           = "hls_export_channel"
        const val NOTIFICATION_ID      = 3000
        private const val TAG          = "HlsExportService"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var notificationManager: NotificationManager? = null
    private val activeExports = java.util.concurrent.atomic.AtomicInteger(0)

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
        val url        = intent.getStringExtra(EXTRA_URL)
        val title      = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown_Video"
        val variantIndex = intent.getIntExtra(EXTRA_VARIANT_INDEX, -1).let { if (it >= 0) it else null }

        intent.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

        if (downloadId == null && url == null) {
            if (activeExports.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(title))
        activeExports.incrementAndGet()

        serviceScope.launch {
            try {
                when {
                    downloadId != null -> exportFromDownloadId(downloadId, title)
                    url != null        -> exportFromUrl(url, title, variantIndex)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Export failed", t)
                Toast.makeText(applicationContext, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (activeExports.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun exportFromDownloadId(downloadId: String, title: String) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)
        val download = dm.downloadIndex.getDownload(downloadId)
            ?: run {
                Toast.makeText(applicationContext, "Video cache not found", Toast.LENGTH_SHORT).show()
                return
            }

        ensureFullyCached(dm, download)
        val mediaItem = download.request.toMediaItem()
        muxToMp4(mediaItem, title)
    }

    private suspend fun exportFromUrl(url: String, title: String, variantIndex: Int?) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)
        val isHls = url.contains(".m3u8", ignoreCase = true)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .setTag(title)

        // If a specific variant (quality) was selected, restrict the MediaItem to ONLY that stream
        if (isHls && variantIndex != null && variantIndex >= 0) {
            val streamKey = androidx.media3.common.StreamKey(
                androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.GROUP_INDEX_VARIANT,
                variantIndex
            )
            mediaItemBuilder.setStreamKeys(listOf(streamKey))
        }

        val mediaItem = mediaItemBuilder.build()

        // Prepare a DownloadRequest.
        // NO complex TrackSelectionOverride needed! The StreamKey automatically restricts the helper.
        val helper = DownloadHelper.forMediaItem(
            applicationContext, mediaItem,
            null,
            HlsDownloadHelper.getDataSourceFactory(applicationContext)
        )

        val req = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<DownloadRequest> { cont ->
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(h: DownloadHelper) {
                        val downloadRequest = h.getDownloadRequest(Util.getUtf8Bytes(title))
                        h.release()
                        cont.resume(downloadRequest)
                    }

                    override fun onPrepareError(h: DownloadHelper, e: java.io.IOException) {
                        h.release()
                        cont.cancel(e)
                    }
                })
            }
        }

        DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, req, true)

        val completed = waitForCompletion(dm, req.id)
        if (!completed) {
            Toast.makeText(applicationContext, "Download did not complete", Toast.LENGTH_LONG).show()
            return
        }

        // Pass req.toMediaItem() so StreamKeys enforce the 480p variant in Transformer
        muxToMp4(req.toMediaItem(), title)
    }

    private suspend fun ensureFullyCached(dm: DownloadManager, download: Download) {
        if (download.state == Download.STATE_COMPLETED) return

        Log.i(TAG, "Download ${download.request.id} incomplete. Sending to DownloadManager to resume.")
        DownloadService.sendAddDownload(
            applicationContext, HlsDownloadService::class.java, download.request, true
        )
        waitForCompletion(dm, download.request.id)
    }

    private suspend fun waitForCompletion(dm: DownloadManager, id: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val listener = object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        dm: DownloadManager, d: Download, finalException: Exception?
                    ) {
                        if (d.request.id != id) return
                        when (d.state) {
                            Download.STATE_COMPLETED -> {
                                dm.removeListener(this)
                                if (cont.isActive) cont.resume(true)
                            }
                            Download.STATE_FAILED -> {
                                dm.removeListener(this)
                                if (cont.isActive) cont.resume(false)
                            }
                            else -> Unit
                        }
                    }
                }
                dm.addListener(listener)
                runCatching {
                    val cur = dm.downloadIndex.getDownload(id)
                    if (cur?.state == Download.STATE_COMPLETED) {
                        dm.removeListener(listener)
                        if (cont.isActive) cont.resume(true)
                    }
                }
                cont.invokeOnCancellation { dm.removeListener(listener) }
            }
        }

    private suspend fun muxToMp4(mediaItem: MediaItem, title: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val cacheFactory: CacheDataSource.Factory =
                HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

            val transformer = Transformer.Builder(applicationContext)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        applicationContext,
                        DefaultDecoderFactory(applicationContext),
                        false,
                        androidx.media3.common.util.Clock.DEFAULT,
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(applicationContext)
                            .setDataSourceFactory(cacheFactory),
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

    private fun buildNotification(title: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Video to Downloads")
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
