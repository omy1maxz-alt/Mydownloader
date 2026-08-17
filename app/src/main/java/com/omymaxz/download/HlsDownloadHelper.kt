package com.omymaxz.download

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import androidx.media3.common.util.Util

object HlsDownloadHelper {

    private var streamCache: Cache? = null

    @Synchronized
    fun getStreamCache(context: Context): Cache {
        if (streamCache == null) {
            val streamContentDirectory = File(context.getExternalFilesDir(null), "stream_cache")
            streamCache = SimpleCache(
                streamContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return streamCache!!
    }

    @Synchronized
    fun getStreamCacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getStreamCache(context))
            .setUpstreamDataSourceFactory(getDataSourceFactory(context))
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    fun clearStreamCache(context: Context) {
        val cache = getStreamCache(context)
        val keys = cache.keys
        for (key in keys) {
            cache.removeResource(key)
        }
    }

    private var downloadManager: DownloadManager? = null
    private var downloadNotificationHelper: DownloadNotificationHelper? = null
    private var databaseProvider: DatabaseProvider? = null
    private var downloadCache: Cache? = null
    private var dataSourceFactory: DataSource.Factory? = null

    // Shared state for headers
    private var currentUserAgent: String? = null
    private var currentCookie: String? = null

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        if (downloadManager == null) {
            val appContext = context.applicationContext
            val databaseProvider = getDatabaseProvider(appContext)
            val cache = getDownloadCache(appContext)
            val dataSourceFactory = getDataSourceFactory(appContext)
            downloadManager = DownloadManager(
                appContext,
                databaseProvider,
                cache,
                dataSourceFactory,
                Executor { it.run() }
            ).apply {
                maxParallelDownloads = 3
                addListener(object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: androidx.media3.exoplayer.offline.Download,
                        finalException: Exception?
                    ) {
                        if (download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
                            val title = String(download.request.data)
                            val intent = android.content.Intent(appContext, HlsExportService::class.java).apply {
                                putExtra(HlsExportService.EXTRA_DOWNLOAD_ID, download.request.id)
                                putExtra(HlsExportService.EXTRA_TITLE, title)
                            }
                            appContext.startService(intent)
                        }
                    }
                })
            }
        }
        return downloadManager!!
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (databaseProvider == null) {
            databaseProvider = StandaloneDatabaseProvider(context)
        }
        return databaseProvider!!
    }

    @Synchronized
    private fun getDataSourceFactory(context: Context): DataSource.Factory {
        if (dataSourceFactory == null) {
            // Use a factory that applies the latest headers
            val upstreamFactory = DefaultHttpDataSource.Factory()


            dataSourceFactory = DataSource.Factory {
                val dataSource = upstreamFactory.createDataSource()
                if (currentUserAgent != null) {
                    dataSource.setRequestProperty("User-Agent", currentUserAgent!!)
                }
                if (currentCookie != null) {
                    dataSource.setRequestProperty("Cookie", currentCookie!!)
                }
                dataSource
            }
        }
        return dataSourceFactory!!
    }

    @Synchronized
    fun getCacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getDownloadCache(context))
            .setUpstreamDataSourceFactory(getDataSourceFactory(context))
            .setCacheWriteDataSinkFactory(null) // Disable writing when reading for export
    }

    @Synchronized
    fun getDownloadNotificationHelper(context: Context): DownloadNotificationHelper {
        if (downloadNotificationHelper == null) {
            downloadNotificationHelper = DownloadNotificationHelper(
                context,
                HlsDownloadService.CHANNEL_ID
            )
        }
        return downloadNotificationHelper!!
    }



    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        if (downloadCache == null) {
            val downloadContentDirectory = File(context.getExternalFilesDir(null), "downloads")
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache!!
    }



    fun downloadHls(context: Context, url: String, title: String, userAgent: String?, cookie: String?) {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setTag(title)

        // Store headers for the global factory to pick up
        currentUserAgent = userAgent
        currentCookie = cookie

        // Create a specific DataSource.Factory for this download preparation that includes the headers.
        val specificDataSourceFactory = DefaultHttpDataSource.Factory()
        if (userAgent != null) {
            specificDataSourceFactory.setUserAgent(userAgent)
        }
        if (cookie != null) {
            specificDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
        }

        val downloadHelper = DownloadHelper.forMediaItem(
            context,
            mediaItemBuilder.build(),
            null,
            specificDataSourceFactory // Use the specific factory for preparation
        )

        downloadHelper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper) {
                // The preparation is done. However, the actual download will run in the Service
                // which uses the *global* downloadManager and its *global* dataSourceFactory.
                // To support headers in the actual download, we must embed them into the DownloadRequest
                // or the Service must know how to recreate the factory.


                // ExoPlayer's DownloadManager uses a single DataSource.Factory.
                // To support per-download headers, the standard pattern is less straightforward.
                // However, often the "preparation" phase is where strict checks happen (manifest fetch).
                // If the segments also require headers, we might still have issues with the global factory.

                // CRITICAL FIX: Since we can't easily change the global factory per request in the Service,
                // we rely on the fact that often the manifest URL is the most protected.
                // But for robust support, we should ideally use a global factory that delegates based on
                // the request, which is complex.


                // CRITICAL FIX: Since we can't easily change the global factory per request in the Service,
                // we rely on the fact that often the manifest URL is the most protected.
                // But for robust support, we should ideally use a global factory that delegates based on
                // the request, which is complex.

                // As a fallback/best-effort for this architecture:
                // We prepared with headers. We will launch the download.
                // If the global factory is generic, segment downloads *might* fail if they need cookies.
                // BUT, replacing the entire DownloadManager architecture is out of scope.
                // We proceed with the specific factory for the helper preparation at least.


                val downloadRequest = helper.getDownloadRequest(
                    Util.getUtf8Bytes(title) // Store title in data
                )
                DownloadService.sendAddDownload(
                    context,
                    HlsDownloadService::class.java,
                    downloadRequest,
                    /* foreground= */ true
                )
                Toast.makeText(context, "HLS Download started: $title", Toast.LENGTH_SHORT).show()
            }

            override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                Toast.makeText(context, "Failed to prepare download: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
}
