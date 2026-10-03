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
    suspend fun checkIsFullyCached(context: Context, mainUri: Uri, mimeType: String, streamKeys: List<androidx.media3.common.StreamKey>): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cache = getUnifiedCache(context)

        try {
            // For progressive MP4 / WebM
            if (mimeType == androidx.media3.common.MimeTypes.VIDEO_MP4 || mimeType == androidx.media3.common.MimeTypes.VIDEO_WEBM) {
                val cacheKey = customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(mainUri).build())
                val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
                val metadata = cache.getContentMetadata(cacheKey)
                val expectedLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(metadata)

                if (expectedLength <= 0) return@withContext false
                if (spans.isEmpty()) return@withContext false

                var currentPosition = 0L
                for (span in spans) {
                    if (span.position != currentPosition) return@withContext false
                    if (!span.isCached || span.file == null || !span.file!!.exists()) return@withContext false
                    currentPosition += span.length
                }
                return@withContext currentPosition > 0 && currentPosition >= expectedLength
            }

            // For HLS
            if (mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
                // 1. Fetch Master Playlist from Cache
                val masterSpec = androidx.media3.datasource.DataSpec.Builder().setUri(mainUri).build()
                val masterCacheKey = customCacheKeyFactory.buildCacheKey(masterSpec)

                val masterSpans = cache.getCachedSpans(masterCacheKey).sortedBy { it.position }
                if (masterSpans.isEmpty() || !masterSpans[0].isCached || masterSpans[0].file == null || !masterSpans[0].file!!.exists()) {
                    return@withContext false
                }

                val masterText = masterSpans[0].file!!.readText()
                if (!masterText.contains("#EXTM3U")) return@withContext false

                var videoVariantUrl = mainUri.toString()
                var audioVariantUrl: String? = null

                if (streamKeys.isNotEmpty()) {
                    try {
                        val parser = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser()
                        val parsedPlaylist = parser.parse(mainUri, masterText.byteInputStream(Charsets.UTF_8))
                        if (parsedPlaylist is androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist) {
                            val filteredPlaylist = parsedPlaylist.copy(streamKeys) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
                            videoVariantUrl = filteredPlaylist.variants.firstOrNull()?.url?.toString() ?: mainUri.toString()
                            audioVariantUrl = filteredPlaylist.audios.firstOrNull()?.url?.toString()
                        }
                    } catch (e: Exception) {}
                } else {
                    // Try to pick the first variant if it's a master playlist
                    try {
                        val parser = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser()
                        val parsedPlaylist = parser.parse(mainUri, masterText.byteInputStream(Charsets.UTF_8))
                        if (parsedPlaylist is androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist) {
                            videoVariantUrl = parsedPlaylist.variants.firstOrNull()?.url?.toString() ?: mainUri.toString()
                            audioVariantUrl = parsedPlaylist.audios.firstOrNull()?.url?.toString()
                        }
                    } catch (e: Exception) {}
                }

                suspend fun isPlaylistFullyCached(playlistUrl: String): Boolean {
                    val pSpec = androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse(playlistUrl)).build()
                    val pKey = customCacheKeyFactory.buildCacheKey(pSpec)
                    val pSpans = cache.getCachedSpans(pKey).sortedBy { it.position }

                    if (pSpans.isEmpty() || !pSpans[0].isCached || pSpans[0].file == null || !pSpans[0].file!!.exists()) {
                        return false
                    }

                    val pText = pSpans[0].file!!.readText()
                    if (!pText.contains("#EXTM3U")) return false

                    val lines = pText.lines()
                    var requiredSegmentsCount = 0
                    var cachedSegmentsCount = 0

                    for (line in lines) {
                        if (line.isBlank()) continue

                        var segmentUrl: String? = null
                        if (line.startsWith("#EXT-X-MAP:URI=")) {
                            val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                            if (uriMatch != null) {
                                segmentUrl = uriMatch.groupValues[1]
                            }
                        } else if (!line.startsWith("#")) {
                            segmentUrl = line
                        }

                        if (segmentUrl != null) {
                            val fullUrl = if (segmentUrl.startsWith("http")) segmentUrl else java.net.URI(playlistUrl).resolve(segmentUrl).toString()
                            requiredSegmentsCount++

                            val segSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            val segKey = customCacheKeyFactory.buildCacheKey(segSpec)

                            val segSpans = cache.getCachedSpans(segKey)
                                .filter { it.isCached && it.file != null && it.file!!.exists() }
                                .sortedBy { it.position }

                            var isSegCached = false
                            if (segSpans.isNotEmpty()) {
                                // For segments, we must check if there is no gap.
                                var expectedPos = 0L
                                var hasGap = false
                                for (span in segSpans) {
                                    if (span.position != expectedPos) { hasGap = true; break }
                                    expectedPos += span.length
                                }

                                if (!hasGap) {
                                    // Verify Signature
                                    var isValidSignature = true
                                    try {
                                        java.io.FileInputStream(segSpans[0].file).use { sigInput ->
                                            val header = ByteArray(8)
                                            if (sigInput.read(header) >= 8) {
                                                if ((header[0] == 0x89.toByte() && header[1] == 0x50.toByte()) ||
                                                    (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) ||
                                                    (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte())) {
                                                    isValidSignature = false
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {}
                                    if (isValidSignature) isSegCached = true
                                }
                            }

                            if (!isSegCached) {
                                // Check cross-domain fallback
                                val segUri = android.net.Uri.parse(fullUrl)
                                val uriPath = segUri.path
                                if (uriPath != null) {
                                    val strippedUriPath = uriPath.substringBefore("?")
                                    val pathMatches = cache.keys.filter { key ->
                                        runCatching { android.net.Uri.parse(key).path?.substringBefore("?") == strippedUriPath }.getOrDefault(false)
                                    }

                                    var matchedKey: String? = null
                                    if (pathMatches.isNotEmpty()) {
                                        matchedKey = pathMatches.firstOrNull { runCatching { android.net.Uri.parse(it).host == segUri.host }.getOrDefault(false) }
                                        if (matchedKey == null && pathMatches.size == 1) {
                                            matchedKey = pathMatches.first()
                                        }
                                    }

                                    if (matchedKey != null) {
                                        val fbSpans = cache.getCachedSpans(matchedKey)
                                            .filter { it.isCached && it.file != null && it.file!!.exists() }
                                            .sortedBy { it.position }

                                        if (fbSpans.isNotEmpty()) {
                                            var expectedPos = 0L
                                            var hasGap = false
                                            for (span in fbSpans) {
                                                if (span.position != expectedPos) { hasGap = true; break }
                                                expectedPos += span.length
                                            }
                                            if (!hasGap) isSegCached = true
                                        }
                                    }
                                }
                            }

                            if (isSegCached) cachedSegmentsCount++
                        }
                    }

                    android.util.Log.d("CACHE_COMPLETE_CHECK", "URL=$playlistUrl | TYPE=HLS | REQUIRED=$requiredSegmentsCount | CACHED=$cachedSegmentsCount")
                    return requiredSegmentsCount > 0 && requiredSegmentsCount == cachedSegmentsCount
                }

                val videoCached = isPlaylistFullyCached(videoVariantUrl)
                if (!videoCached) return@withContext false

                if (audioVariantUrl != null) {
                    val audioCached = isPlaylistFullyCached(audioVariantUrl)
                    if (!audioCached) return@withContext false
                }

                return@withContext true
            }

            // For DASH
            if (mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD) {
                // DASH implementation would go here, for now fallback to exoplayer state
                return@withContext false
            }

        } catch (e: Exception) {
            android.util.Log.e("CACHE_COMPLETE_CHECK", "Error checking cache: ${e.message}")
        }
        return@withContext false
    }
}
