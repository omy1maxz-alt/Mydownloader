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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.common.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

object HlsDownloadHelper {

    // ---- SINGLE cache-key strategy used EVERYWHERE (player, download, export, subs) ----
    val customCacheKeyFactory = CacheKeyFactory { dataSpec ->
        val uri = dataSpec.uri

        if (!uri.isHierarchical || (uri.scheme != "http" && uri.scheme != "https")) {
            if (uri.scheme != "data") { // Suppress logging for standard data URI subtitles as it causes noise
                android.util.Log.d("CACHE_KEY", "[CACHE_KEY] non-http-or-non-hierarchical scheme=${uri.scheme} fallback=true")
            }
            return@CacheKeyFactory uri.toString()
        }

        // Preserve crucial authentication tokens to prevent cache collisions
        // while stripping dynamic session IDs
        val importantKeys = setOf("auth-token", "token", "sig", "mac")
        val newQuery = StringBuilder()

        try {
            uri.queryParameterNames?.forEach { key ->
                if (importantKeys.contains(key.lowercase())) {
                    val value = uri.getQueryParameter(key)
                    if (value != null) {
                        if (newQuery.isNotEmpty()) newQuery.append("&")
                        newQuery.append("$key=$value")
                    }
                }
            }

            val builder = uri.buildUpon().fragment("")
            if (newQuery.isEmpty()) {
                builder.clearQuery()
            } else {
                builder.encodedQuery(newQuery.toString())
            }

            builder.build().toString()
        } catch (e: UnsupportedOperationException) {
            // Fallback for unexpected non-hierarchical URIs escaping the check
            uri.toString()
        }
    }

    // ---- Unified cache instance ----
    private var streamCache: SimpleCache? = null

    @Synchronized
    fun getUnifiedCache(context: Context): SimpleCache {
        if (streamCache == null) {
            val dir = File(context.getExternalFilesDir(null), "unified_video_cache")
            if (!dir.exists()) dir.mkdirs()
            streamCache = SimpleCache(dir, NoOpCacheEvictor(), getDatabaseProvider(context))
        }
        return streamCache!!
    }

    @Synchronized
    fun clearUnifiedCache(context: Context) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 1. Release the cache to unlock the files
                val cache = streamCache
                if (cache != null) {
                    cache.release()
                    streamCache = null
                }

                // 2. Delete the ExoPlayer directory recursively (instant compared to file-by-file deletion)
                val dir = java.io.File(context.getExternalFilesDir(null), "unified_video_cache")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }

                // 3. Clear orphaned FFmpeg export directories in filesDir
                val filesDir = context.filesDir
                if (filesDir.exists()) {
                    filesDir.listFiles()?.forEach { file ->
                        if (file.isDirectory && file.name.startsWith("tmp_export_")) {
                            file.deleteRecursively()
                        }
                    }
                }

                // 4. Clear standard Android cacheDir
                val cacheDir = context.cacheDir
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                }

                // Clear ExoPlayer DownloadManager directory if it exists.
                // Media3's DownloadManager uses `getExternalFilesDir(null)/downloads` by default
                // when no custom directory is specified via a custom cache.
                // However, since we pass unified_video_cache as the cacheFactory, the downloaded segments
                // sit in unified_video_cache. BUT the download actions/state sit in the standalone database.
                // So let's nuke the StandaloneDatabaseProvider dir as well.

                // Actually StandaloneDatabaseProvider uses "exoplayer_internal.db" in context.databasePath
                val dbFile = context.getDatabasePath("exoplayer_internal.db")
                if (dbFile.exists()) dbFile.delete()

                val dbJourFile = context.getDatabasePath("exoplayer_internal.db-journal")
                if (dbJourFile.exists()) dbJourFile.delete()

                // Nuke the ExoPlayer download manager state
                try {
                    downloadManager?.removeAllDownloads()
                } catch(e:Exception){}

                // 5. Clear WebView cache (must be on Main Thread)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        android.webkit.WebView(context).clearCache(true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    android.widget.Toast.makeText(context, "All app caches cleared successfully", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Failed to clear cache: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---- Unified HTTP factory (headers applied per-request via thread-local-ish state) ----
    var currentUserAgent: String? = null
    var currentCookie: String? = null
    var currentReferer: String? = null

    @Synchronized
    fun getDataSourceFactory(context: Context): DataSource.Factory {
        // Instantiate a NEW factory per request rather than mutating the global singleton
        // to prevent race conditions or missing headers on background fetches.
        // Also use a ResolvingDataSource to explicitly log the final resolved DataSpec URI.
        val upstreamFactory = DataSource.Factory {
            val upstream = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)

            currentUserAgent?.let { upstream.setUserAgent(it) }
            val props = mutableMapOf<String, String>()
            currentCookie?.let { props["Cookie"] = it }
            currentReferer?.let {
                props["Referer"] = it
                try {
                    val refererUri = java.net.URL(it)
                    val origin = "${refererUri.protocol}://${refererUri.host}"
                    props["Origin"] = origin
                } catch (e: Exception) {}
            }
            props["Accept"] = "*/*"
            upstream.setDefaultRequestProperties(props)

            androidx.media3.datasource.DefaultDataSource.Factory(context, upstream).createDataSource()
        }

        return androidx.media3.datasource.ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            val host = uri.host ?: "unknown"
            val path = uri.path ?: ""
            val isManifest = path.endsWith(".m3u8") || path.endsWith(".mpd")
            val kind = if (isManifest) "manifest" else "media_segment"

            val headers = dataSpec.httpRequestHeaders
            val userAgentPresent = headers.containsKey("User-Agent") || headers.containsKey("user-agent")
            val refererPresent = headers.containsKey("Referer") || headers.containsKey("referer")
            val originPresent = headers.containsKey("Origin") || headers.containsKey("origin")
            val cookiePresent = headers.containsKey("Cookie") || headers.containsKey("cookie")

            val redactedPath = path.substringBeforeLast("/") + "/REDACTED" + (if (isManifest) ".m3u8" else ".ts")

            android.util.Log.d("HLS_HTTP", """
                HLS_HTTP
                host=$host
                path=$redactedPath
                kind=$kind
                userAgentPresent=$userAgentPresent
                refererPresent=$refererPresent
                originPresent=$originPresent
                cookiePresent=$cookiePresent
                cacheEnabled=true
                retryCount=0
                responseCode=PENDING_UPSTREAM
            """.trimIndent())

            dataSpec
        }
    }

    /** CacheDataSource used by player + export. `readOnly` disables writes (export path). */
    @Synchronized
    fun getCacheDataSourceFactory(context: Context, readOnly: Boolean = false):
            androidx.media3.datasource.cache.CacheDataSource.Factory {
        val f = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, getDataSourceFactory(context)))
            // DO NOT STRIP QUERY FROM CACHE KEY if it's the primary content identifier, or at least
            // ensure the query is not mistakenly stripped from the URI itself by some Exoplayer bug.
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(if (readOnly) androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR else androidx.media3.datasource.cache.CacheDataSource.FLAG_BLOCK_ON_CACHE)
        if (readOnly) f.setCacheWriteDataSinkFactory(null)
        return f
    }

    // ---- Download manager (singleton) ----
    private var downloadManager: DownloadManager? = null
    private var downloadNotificationHelper: DownloadNotificationHelper? = null
    private var databaseProvider: DatabaseProvider? = null

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        if (downloadManager == null) {
            val app = context.applicationContext
            val cacheFactory = getCacheDataSourceFactory(app, readOnly = false)
            downloadManager = DownloadManager(
                app,
                DefaultDownloadIndex(getDatabaseProvider(app)),
                DefaultDownloaderFactory(cacheFactory, Executor { it.run() })
            ).apply {
                maxParallelDownloads = 3
                addListener(object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        dm: DownloadManager,
                        download: Download,
                        finalException: Exception?
                    ) {
                        if (download.state == Download.STATE_COMPLETED) {
                            // Extract the specific title for this completed download to prevent mixing names
                            val title = String(download.request.data)
                            val intent = android.content.Intent(app, HlsExportService::class.java).apply {
                                putExtra(HlsExportService.EXTRA_DOWNLOAD_ID, download.request.id)
                                putExtra(HlsExportService.EXTRA_TITLE, title)
                            }
                            app.startService(intent)
                        }
                    }
                })
            }
        }
        return downloadManager!!
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (databaseProvider == null) databaseProvider = StandaloneDatabaseProvider(context)
        return databaseProvider!!
    }

    @Synchronized
    fun getDownloadNotificationHelper(context: Context): DownloadNotificationHelper {
        if (downloadNotificationHelper == null) {
            downloadNotificationHelper = DownloadNotificationHelper(context, HlsDownloadService.CHANNEL_ID)
        }
        return downloadNotificationHelper!!
    }

    // ---- Subtitle directory convention ----
    fun subtitlesDirFor(context: Context, title: String): File {
        val sanitized = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val dir = File(context.getExternalFilesDir(null), "subtitles/$sanitized")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listLocalSubtitles(context: Context, title: String): List<File> {
        val dir = subtitlesDirFor(context, title)
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.extension.equals("vtt", true) || it.extension.equals("srt", true)) }
            .sortedBy { it.name }
    }

    // ---- Download entry point ----
    fun downloadHls(context: Context, url: String, title: String, userAgent: String?, cookie: String?) {
        // Snapshot headers into the global factory so the DownloadManager's segments see them too.
        currentUserAgent = userAgent
        currentCookie = cookie

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setTag(title)
            .build()

        // Per-request factory for the *preparation* phase (manifest fetch).
        val prepFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        userAgent?.let { prepFactory.setUserAgent(it) }

        val props = mutableMapOf<String, String>()
        cookie?.let { props["Cookie"] = it }
        currentReferer?.let { props["Referer"] = it }
        props["Accept"] = "*/*"
        if (props.isNotEmpty()) prepFactory.setDefaultRequestProperties(props)

        val helper = DownloadHelper.forMediaItem(context, mediaItem, null, prepFactory)
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(h: DownloadHelper) {
                // 1) Parse master playlist & download subtitles in parallel.
                CoroutineScope(Dispatchers.IO).launch {
                    try { fetchAndSaveSubtitles(context, url, title, userAgent, cookie) }
                    catch (t: Throwable) { t.printStackTrace() }
                }
                // 2) Hand the actual segment download to the service (uses unified cache).
                val req = h.getDownloadRequest(Util.getUtf8Bytes(title))
                DownloadService.sendAddDownload(context, HlsDownloadService::class.java, req, true)
                Toast.makeText(context, "HLS Download started: $title", Toast.LENGTH_SHORT).show()
                h.release()
            }

            override fun onPrepareError(h: DownloadHelper, e: IOException) {
                Toast.makeText(context, "Failed to prepare download: ${e.message}", Toast.LENGTH_LONG).show()
                h.release()
            }
        })
    }

    /**
     * Fetch the master m3u8, parse SUBTITLES tracks with SubtitleUtils,
     * resolve each URI against the master's base URL, and write .vtt/.srt
     * into subtitles/<sanitizedTitle>/.
     */
    fun fetchAndSaveSubtitles(
        context: Context, masterUrl: String, title: String,
        userAgent: String?, cookie: String?
    ) {
        val referer = currentReferer
        val masterText = httpGetString(masterUrl, userAgent, referer, cookie) ?: return
        val tracks = SubtitleUtils.parseSubtitleTracks(masterText, masterUrl)
        if (tracks.isEmpty()) return

        val outDir = subtitlesDirFor(context, title)

        for (track in tracks) {
            try {
                // A subtitle URI may itself be an m3u8 (WebVTT variant playlist).
                val vttUrl = resolveToDirectVtt(track.uri, userAgent, cookie) ?: continue
                val ext = if (vttUrl.contains(".srt", true)) ".srt" else ".vtt"
                var lang = track.language.ifBlank { "und" }.replace(Regex("[^a-zA-Z0-9-]"), "_")

                val bytes = httpGetBytes(vttUrl, userAgent, referer, cookie) ?: continue

                // Inspect the subtitle content to force English detection if it contains "thank"
                val contentString = String(bytes, Charsets.UTF_8)
                if (contentString.contains("thank", ignoreCase = true)) {
                    lang = "en"
                }

                val outFile = File(outDir, "${title.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_$lang$ext")
                FileOutputStream(outFile).use {
                    // Fix ExoPlayer parse failure for VTT without header
                    if (ext == ".vtt" && !contentString.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
                        it.write("WEBVTT\n\n".toByteArray(Charsets.UTF_8))
                    }
                    it.write(bytes)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    /** If [subUrl] is an m3u8, parse it and return the first non-comment line (the .vtt). */
    private fun resolveToDirectVtt(subUrl: String, userAgent: String?, cookie: String?): String? {
        if (!subUrl.contains(".m3u8", true)) return subUrl
        val referer = currentReferer
        val text = httpGetString(subUrl, userAgent, referer, cookie) ?: return null
        val base = subUrl.substringBeforeLast('/')
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return if (line.startsWith("http://") || line.startsWith("https://")) line else "$base/$line"
        }
        return null
    }

    fun httpGetString(url: String, ua: String?, referer: String?, cookie: String?): String? =
        httpGetBytes(url, ua, referer, cookie)?.let { String(it, Charsets.UTF_8) }

    fun httpGetBytes(url: String, ua: String?, referer: String?, cookie: String?): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000
            instanceFollowRedirects = true
            ua?.let      { setRequestProperty("User-Agent", it) }
            referer?.let { setRequestProperty("Referer", it) }
            cookie?.let  { setRequestProperty("Cookie", it) }
        }
        return try { c.inputStream.use { it.readBytes() } } catch (t: Throwable) { null }
        finally { c.disconnect() }
    }
}