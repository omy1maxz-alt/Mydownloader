package com.omymaxz.download

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executor
import androidx.media3.common.util.Util

object HlsDownloadHelper {

    val customCacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.uri.buildUpon().clearQuery().toString()
    }

    private var streamCache: Cache? = null

    @Synchronized
    fun getUnifiedCache(context: Context): Cache {
        if (streamCache == null) {
            val unifiedContentDirectory = File(context.getExternalFilesDir(null), "unified_video_cache")
            streamCache = SimpleCache(
                unifiedContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return streamCache!!
    }

    @Synchronized
    fun getStreamCacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(getDataSourceFactory(context))
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    fun clearUnifiedCache(context: Context) {
        val cache = getUnifiedCache(context)
        val keys = cache.keys
        for (key in keys) {
            cache.removeResource(key)
        }
    }

    private var downloadManager: DownloadManager? = null
    private var downloadNotificationHelper: DownloadNotificationHelper? = null
    private var databaseProvider: DatabaseProvider? = null
    private var dataSourceFactory: DataSource.Factory? = null

    // Shared state for headers
    var currentUserAgent: String? = null
    var currentCookie: String? = null
    var currentReferer: String? = null

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        if (downloadManager == null) {
            val appContext = context.applicationContext
            val databaseProvider = getDatabaseProvider(appContext)
            val cache = getUnifiedCache(appContext)
            val dataSourceFactory = getDataSourceFactory(appContext)
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setCacheKeyFactory(customCacheKeyFactory)

            downloadManager = DownloadManager(
                appContext,
                androidx.media3.exoplayer.offline.DefaultDownloadIndex(databaseProvider),
                DefaultDownloaderFactory(cacheDataSourceFactory, Executor { it.run() })
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
    fun getDataSourceFactory(context: Context): DataSource.Factory {
        if (dataSourceFactory == null) {
            val upstreamFactory = DefaultHttpDataSource.Factory()
            dataSourceFactory = DataSource.Factory {
                val dataSource = upstreamFactory.createDataSource()
                if (currentUserAgent != null) {
                    dataSource.setRequestProperty("User-Agent", currentUserAgent!!)
                }
                if (currentCookie != null) {
                    dataSource.setRequestProperty("Cookie", currentCookie!!)
                }
                if (currentReferer != null) {
                    dataSource.setRequestProperty("Referer", currentReferer!!)
                }
                dataSource
            }
        }
        return dataSourceFactory!!
    }

    /**
     * CHANGED: readOnly now only disables the cache *write sink*, exactly as before, for the
     * final read-only Transformer mux pass. It no longer needs to double as "don't hit the
     * network" — HlsExportService now decides that explicitly by choosing which segments (if
     * any) to pre-fetch through a *writable* factory before ever touching Transformer. See
     * HlsExportService.exportHlsToMp4FromUrl.
     */
    @Synchronized
    fun getCacheDataSourceFactory(context: Context, readOnly: Boolean = false): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val factory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(getDataSourceFactory(context))
            .setCacheKeyFactory(customCacheKeyFactory)

        if (readOnly) {
            factory.setCacheWriteDataSinkFactory(null) // Disable writing when reading for export
        }
        return factory
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

    // ---------------------------------------------------------------------------------------
    // FIX (Issue 1, root cause): subtitle fetching used to live only inline inside
    // downloadHls()'s onPrepared callback, so it only ever ran for the explicit
    // "offline download" flow. Pulled out into its own reusable, awaitable function so
    // CustomPlayerActivity can call it for plain streaming/caching playback too, and
    // HlsExportService can call it before an MP4 export — not just the DownloadManager path.
    // ---------------------------------------------------------------------------------------
    suspend fun fetchAndCacheSubtitles(
        context: Context,
        url: String,
        title: String,
        userAgent: String?,
        cookie: String?
    ): List<File> = withContext(Dispatchers.IO) {
        val results = mutableListOf<File>()
        try {
            val lines = fetchPlaylistLines(url, userAgent, cookie)
            val subtitleTracks = mutableListOf<Pair<String, String>>()
            lines.forEach { line ->
                if (line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") && line.contains("URI=\"")) {
                    val start = line.indexOf("URI=\"") + 5
                    val end = line.indexOf("\"", start)
                    if (start > 4 && end > start) {
                        val uri = line.substring(start, end)
                        var language = "en"
                        if (line.contains("LANGUAGE=\"")) {
                            val langStart = line.indexOf("LANGUAGE=\"") + 10
                            val langEnd = line.indexOf("\"", langStart)
                            if (langEnd > langStart) language = line.substring(langStart, langEnd)
                        } else if (line.contains("NAME=\"")) {
                            val nameStart = line.indexOf("NAME=\"") + 6
                            val nameEnd = line.indexOf("\"", nameStart)
                            if (nameEnd > nameStart) language = line.substring(nameStart, nameEnd)
                        }
                        subtitleTracks.add(Pair(language, uri))
                    }
                }
            }

            subtitleTracks.forEach { (language, relUri) ->
                try {
                    // FIX: proper RFC-3986 relative resolution instead of naive string
                    // concatenation — this was breaking whenever the master playlist URL had
                    // a query string (auth tokens) or the subtitle URI used "../".
                    val absoluteSubUrl = resolveUri(url, relUri)

                    var directVttUrl: String? = null
                    if (absoluteSubUrl.contains(".m3u8")) {
                        val subLines = fetchPlaylistLines(absoluteSubUrl, userAgent, cookie)
                        for (subLine in subLines) {
                            if (!subLine.startsWith("#") && subLine.isNotBlank()) {
                                directVttUrl = resolveUri(absoluteSubUrl, subLine)
                            }
                        }
                    } else {
                        directVttUrl = absoluteSubUrl
                    }

                    directVttUrl?.let { vttUrl ->
                        val finalConn = URL(vttUrl).openConnection() as HttpURLConnection
                        finalConn.connectTimeout = 15000
                        finalConn.readTimeout = 15000
                        if (userAgent != null) finalConn.setRequestProperty("User-Agent", userAgent)
                        if (cookie != null) finalConn.setRequestProperty("Cookie", cookie)

                        val subExt = if (vttUrl.contains(".srt", true)) ".srt" else ".vtt"
                        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                        val sanitizedLang = language.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                        val subtitlesDirectory = File(context.getExternalFilesDir(null), "subtitles")
                        if (!subtitlesDirectory.exists()) subtitlesDirectory.mkdirs()
                        val subFile = File(subtitlesDirectory, "${sanitizedTitle}_subtitle_$sanitizedLang$subExt")

                        finalConn.inputStream.use { input ->
                            FileOutputStream(subFile).use { output -> input.copyTo(output) }
                        }
                        results.add(subFile)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    /** RFC-3986 relative URI resolution — handles tokens in the base query string and "../". */
    private fun resolveUri(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        } catch (e: Exception) {
            if (relativeOrAbsolute.startsWith("http")) relativeOrAbsolute
            else baseUrl.substringBeforeLast("/") + "/" + relativeOrAbsolute
        }
    }

    private fun fetchPlaylistLines(url: String, userAgent: String?, cookie: String?): List<String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
        if (cookie != null) connection.setRequestProperty("Cookie", cookie)
        return connection.inputStream.bufferedReader().readLines()
    }

    // ---------------------------------------------------------------------------------------
    // NEW (Issue 2): segment-level cache inspection. This is what HlsExportService uses to
    // stop treating every export as "just run Transformer over the whole manifest" and
    // instead know, per segment, whether it's already fully cached.
    // ---------------------------------------------------------------------------------------
    data class SegmentCacheInfo(
        val uri: String,
        val cacheKey: String,
        val startMs: Long,
        val durationMs: Long,
        val isCached: Boolean
    )

    suspend fun inspectSegments(
        context: Context,
        url: String,
        userAgent: String?,
        cookie: String?
    ): List<SegmentCacheInfo> = withContext(Dispatchers.IO) {
        val mediaPlaylistUrl = resolveMediaPlaylistUrl(url, userAgent, cookie)
        val lines = fetchPlaylistLines(mediaPlaylistUrl, userAgent, cookie)
        val cache = getUnifiedCache(context)
        val segments = mutableListOf<SegmentCacheInfo>()
        var pendingDurationMs = 0L
        var elapsedMs = 0L

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:")) {
                val durationSec = line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                pendingDurationMs = (durationSec * 1000).toLong()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val segmentUrl = resolveUri(mediaPlaylistUrl, line)
                val cacheKey = customCacheKeyFactory.buildCacheKey(DataSpec(Uri.parse(segmentUrl)))
                val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
                val isCached = contentLength > 0 && cache.getCachedBytes(cacheKey, 0, contentLength) == contentLength
                segments += SegmentCacheInfo(segmentUrl, cacheKey, elapsedMs, pendingDurationMs, isCached)
                elapsedMs += pendingDurationMs
                pendingDurationMs = 0L
            }
        }
        segments
    }

    /** If [url] is a master playlist, resolves to its first variant's media playlist URL. */
    private fun resolveMediaPlaylistUrl(url: String, userAgent: String?, cookie: String?): String {
        val lines = fetchPlaylistLines(url, userAgent, cookie)
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        if (!isMaster) return url
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                        return resolveUri(url, candidate)
                    }
                }
            }
        }
        return url
    }

    fun downloadHls(context: Context, url: String, title: String, userAgent: String?, cookie: String?) {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setTag(title)

        currentUserAgent = userAgent
        currentCookie = cookie

        val specificDataSourceFactory = DefaultHttpDataSource.Factory()
        if (userAgent != null) specificDataSourceFactory.setUserAgent(userAgent)
        if (cookie != null) specificDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))

        val downloadHelper = DownloadHelper.forMediaItem(
            context,
            mediaItemBuilder.build(),
            null,
            specificDataSourceFactory
        )

        downloadHelper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper) {
                CoroutineScope(Dispatchers.IO).launch {
                    fetchAndCacheSubtitles(context, url, title, userAgent, cookie)
                }

                val downloadRequest = helper.getDownloadRequest(Util.getUtf8Bytes(title))
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