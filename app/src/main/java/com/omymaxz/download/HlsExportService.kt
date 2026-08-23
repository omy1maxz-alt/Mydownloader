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
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

class HlsExportService : Service() {

    companion object {
        const val EXTRA_DOWNLOAD_ID = "com.omymaxz.download.extra.DOWNLOAD_ID"
        const val EXTRA_TITLE      = "com.omymaxz.download.extra.TITLE"
        const val EXTRA_URL        = "com.omymaxz.download.extra.URL"
        const val CHANNEL_ID       = "hls_export_channel"
        const val NOTIFICATION_ID  = 3000
        private const val TAG      = "HlsExportService"
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
        if (intent == null) { if (activeExports.get() == 0) stopSelf(startId); return START_NOT_STICKY }

        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        val url        = intent.getStringExtra(EXTRA_URL)
        val title      = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown_Video"

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
                    url != null        -> exportFromUrl(url, title)
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

    // ---------- Path A: we already have a DownloadManager entry ----------
    private suspend fun exportFromDownloadId(downloadId: String, title: String) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)
        val download = dm.downloadIndex.getDownload(downloadId)
            ?: run {
                Toast.makeText(applicationContext, "Video cache not found", Toast.LENGTH_SHORT).show()
                return
            }

        // Ensure the asset is fully cached before muxing. DownloadManager will resume, not restart.
        ensureFullyCached(dm, download)

        val mediaItem = download.request.toMediaItem()
        muxToMp4(mediaItem, title) {
            // Success: optionally drop the offline entry since we now have a standalone MP4.
            try { dm.removeDownload(downloadId) } catch (_: Throwable) {}
        }
    }

    // ---------- Path B: direct URL (e.g. from the player's "Save" FAB) ----------
    private suspend fun exportFromUrl(url: String, title: String) {
        val dm = HlsDownloadHelper.getDownloadManager(applicationContext)

        // Build a MediaItem the same way the downloader would.
        val isHls = url.contains(".m3u8", ignoreCase = true)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .setTag(title)
            .build()

        // Prepare a DownloadRequest, then push it through DownloadManager so the
        // unified cache is filled (resuming from whatever is already cached).
        val helper = androidx.media3.exoplayer.offline.DownloadHelper.forMediaItem(
            applicationContext, mediaItem,
            null,
            HlsDownloadHelper.getDataSourceFactory(applicationContext)
        )
        val req = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<androidx.media3.exoplayer.offline.DownloadRequest> { cont ->
                helper.prepare(object : androidx.media3.exoplayer.offline.DownloadHelper.Callback {
                    override fun onPrepared(h: androidx.media3.exoplayer.offline.DownloadHelper) {
                        val r = h.getDownloadRequest(androidx.media3.common.util.Util.getUtf8Bytes(title))
                        h.release()
                        cont.resume(r)
                    }
                    override fun onPrepareError(h: androidx.media3.exoplayer.offline.DownloadHelper, e: java.io.IOException) {
                        h.release()
                        cont.cancel(e)
                    }
                })
            }
        }
        DownloadService.sendAddDownload(applicationContext, HlsDownloadService::class.java, req, true)

        // Wait for DownloadManager to finish (it will reuse cached segments).
        val completed = waitForCompletion(dm, req.id)
        if (!completed) {
            Toast.makeText(applicationContext, "Download did not complete", Toast.LENGTH_LONG).show()
            return
        }
        muxToMp4(mediaItem, title, onMuxSuccess = {
            try { HlsDownloadHelper.clearUnifiedCache(applicationContext) } catch (_: Throwable) {}
        })
    }

    // ---------- Cache coverage check + DownloadManager resume ----------
    private suspend fun ensureFullyCached(dm: DownloadManager, download: Download) {
        if (download.state == Download.STATE_COMPLETED) return

        // Ask the cache how many bytes we already have for this key.
        val cache: Cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
        val key = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(
            androidx.media3.datasource.DataSpec(Uri.parse(download.request.uri.toString()))
        )
        val cachedBytes = cache.getCachedBytes(key, 0, androidx.media3.common.C.LENGTH_UNSET.toLong())
        val totalBytes  = download.bytesDownloaded.coerceAtLeast(1L)
        Log.i(TAG, "Cache coverage for ${download.request.id}: $cachedBytes / $totalBytes")

        if (cachedBytes >= totalBytes - 1024) {
            // Close enough — treat as complete.
            return
        }

        // Otherwise, (re-)add the download so DownloadManager resumes from cache.
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
                                dm.removeListener(this); if (cont.isActive) cont.resume(true)
                            }
                            Download.STATE_FAILED -> {
                                dm.removeListener(this); if (cont.isActive) cont.resume(false)
                            }
                            else -> Unit
                        }
                    }
                }
                dm.addListener(listener)
                // Seed check in case it already completed.
                runCatching {
                    val cur = dm.downloadIndex.getDownload(id)
                    if (cur?.state == Download.STATE_COMPLETED) {
                        dm.removeListener(listener); if (cont.isActive) cont.resume(true)
                    }
                }
                cont.invokeOnCancellation { dm.removeListener(listener) }
            }
        }

    // ---------- The actual MP4 mux ----------
    private suspend fun muxToMp4(mediaItem: MediaItem, title: String, onMuxSuccess: () -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->

            // IMPORTANT: use the unified CacheDataSource (read-only is fine here because
            // ensureFullyCached() guarantees the bytes are present).
            val cacheFactory: CacheDataSource.Factory =
                HlsDownloadHelper.getCacheDataSourceFactory(applicationContext, readOnly = true)

            val transformer = Transformer.Builder(applicationContext)
                .setAssetLoaderFactory(
                    androidx.media3.transformer.DefaultAssetLoaderFactory(
                        applicationContext,
                        androidx.media3.transformer.DefaultDecoderFactory(applicationContext),
                        /* forceAudioTrack= */ false,
                        androidx.media3.common.util.Clock.DEFAULT,
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(applicationContext)
                            .setDataSourceFactory(cacheFactory),
                        androidx.media3.datasource.DataSourceBitmapLoader(applicationContext)
                    )
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                        Toast.makeText(applicationContext, "Export complete: $title", Toast.LENGTH_LONG).show()
                        onMuxSuccess()
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

            val safe = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$safe.mp4"
            )
            transformer.start(mediaItem, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }

    private fun buildNotification(title: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Video to Downloads Folder")
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

    override fun onDestroy() { super.onDestroy(); serviceJob.cancel() }
}