package com.omymaxz.download

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BackgroundCacheService : Service() {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var cacheWriter: CacheWriter? = null
    private var activeJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoUrl = intent?.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL) ?: return START_NOT_STICKY

        Log.i("BackgroundCache", "Starting aggressive background cache for: $videoUrl")

        // Cancel any existing caching jobs if the user quickly swapped videos
        cacheWriter?.cancel()
        activeJob?.cancel()

        activeJob = scope.launch {
            try {
                val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                val dataSource = HlsDownloadHelper.getCacheDataSourceFactory(applicationContext).createDataSource()

                val dataSpec = DataSpec(Uri.parse(videoUrl))

                cacheWriter = CacheWriter(
                    dataSource,
                    dataSpec,
                    null,
                    null
                )

                // This blocks and downloads the stream fully in the background
                cacheWriter?.cache()
                Log.i("BackgroundCache", "Aggressive caching finished for $videoUrl")
            } catch (e: Exception) {
                Log.e("BackgroundCache", "Aggressive caching failed or cancelled", e)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cacheWriter?.cancel()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
