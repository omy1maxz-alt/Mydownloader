@file:OptIn(UnstableApi::class)

package com.omymaxz.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
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
import java.io.File
import java.util.concurrent.Executor

@UnstableApi
class HlsDownloadHelper(private val context: Context) {

    private val downloadManager: DownloadManager
    private val downloadNotificationHelper: DownloadNotificationHelper

    companion object {
        private var instance: HlsDownloadHelper? = null
        private lateinit var cache: Cache
        private lateinit var databaseProvider: DatabaseProvider

        fun getInstance(context: Context): HlsDownloadHelper {
            if (instance == null) {
                val downloadDirectory = File(context.getExternalFilesDir(null), "downloads")
                databaseProvider = StandaloneDatabaseProvider(context)
                cache = SimpleCache(downloadDirectory, NoOpCacheEvictor(), databaseProvider)
                instance = HlsDownloadHelper(context.applicationContext)
            }
            return instance!!
        }
    }

    init {
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
        val mediaItem = MediaItem.fromUri(uri)
        val downloadRequest = DownloadRequest.Builder(uri.toString(), uri).build()

        DownloadService.sendAddDownload(
            context,
            MyDownloadService::class.java,
            downloadRequest,
            false
        )
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