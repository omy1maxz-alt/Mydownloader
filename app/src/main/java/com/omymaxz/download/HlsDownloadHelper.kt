@file:OptIn(UnstableApi::class)

package com.omymaxz.download

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

@UnstableApi
class HlsDownloadHelper private constructor(private val context: Context) {

    private val downloadManager: DownloadManager
    private val downloadNotificationHelper: DownloadNotificationHelper

    companion object {
        @Volatile
        private var instance: HlsDownloadHelper? = null

        fun getInstance(context: Context): HlsDownloadHelper =
            instance ?: synchronized(this) {
                instance ?: HlsDownloadHelper(context.applicationContext).also { instance = it }
            }
    }

    init {
        val downloadDirectory = File(context.getExternalFilesDir(null), "downloads")
        val databaseProvider: DatabaseProvider = StandaloneDatabaseProvider(context)
        val cache: Cache = SimpleCache(downloadDirectory, NoOpCacheEvictor(), databaseProvider)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
        val executor = Executor { r -> r.run() }

        downloadManager = DownloadManager(
            context,
            databaseProvider,
            cache,
            dataSourceFactory,
            executor
        )

        downloadNotificationHelper = DownloadNotificationHelper(context, "download_channel")
    }

    fun download(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val mediaItem = MediaItem.fromUri(uri)
            val downloadHelper = DownloadHelper.forMediaItem(
                context,
                mediaItem,
                null,
                DefaultHttpDataSource.Factory()
            )
            downloadHelper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper) {
                    val downloadRequest = helper.getDownloadRequest(uri.toString(), null)
                    DownloadService.sendAddDownload(
                        context,
                        MyDownloadService::class.java,
                        downloadRequest,
                        false
                    )
                    helper.release()
                }

                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    helper.release()
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "Failed to prepare download: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    class MyDownloadService : DownloadService(
        FOREGROUND_NOTIFICATION_ID_NONE,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        "download_channel",
        R.string.download_notification_channel_name,
        0
    ) {
        override fun getDownloadManager(): DownloadManager {
            return getInstance(this).downloadManager
        }

        override fun getScheduler() = null

        override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): android.app.Notification {
            return getInstance(this).downloadNotificationHelper
                .buildProgressNotification(
                    this,
                    R.drawable.ic_launcher_foreground,
                    null,
                    null,
                    downloads,
                    notMetRequirements
                )
        }
    }
}