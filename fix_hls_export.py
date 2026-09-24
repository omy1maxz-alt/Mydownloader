import sys

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Let's fix the `copyMp4FromCache` to actually check SimpleCache spans instead of solely relying on CacheDataSource which throws on incomplete progressive files if length is unknown.
old_copyMp4 = """    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val cacheOnlyFactory = cacheOnlyDataSource()
        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val dataSpec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(uri)
            .setKey(cacheKey)
            .build()

        try {
            writeExportLog("Reading MP4 directly from cache for: $url")
            cacheOnlyFactory.open(dataSpec)
            out.outputStream().use { fos ->
                val buffer = ByteArray(1024 * 256)
                var bytesRead: Int
                while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache: ${e.message}")
            throw Exception("Failed to copy MP4 from cache", e)
        } finally {
            try {
                cacheOnlyFactory.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }"""

new_copyMp4 = """    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)

        val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
        if (spans.isEmpty()) {
            writeExportLog("No cache spans found for key: $cacheKey")
            throw Exception("No cache spans found for MP4")
        }

        try {
            writeExportLog("Copying MP4 from cache via direct spans (${spans.size} found) for key: $cacheKey")
            out.outputStream().use { fos ->
                for (span in spans) {
                    if (span.file != null && span.file!!.exists()) {
                        span.file!!.inputStream().use { fis ->
                            fis.copyTo(fos)
                        }
                    }
                }
            }

            // Notify MediaStore
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, out.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = applicationContext.contentResolver
                val targetUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        out.inputStream().use { input -> input.copyTo(output) }
                    }
                    out.delete()
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache spans: ${e.message}")
            throw Exception("Failed to copy MP4 from cache spans", e)
        }
    }"""

content = content.replace(old_copyMp4, new_copyMp4)

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "w") as f:
    f.write(content)


with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    hls_content = f.read()

import re
old_factory = re.search(r"    fun getDataSourceFactory.*?    \}", hls_content, re.DOTALL)
new_factory = """    fun getDataSourceFactory(context: Context): DataSource.Factory {
        // Instantiate a NEW factory per request rather than mutating the global singleton
        // to prevent race conditions or missing headers on background fetches.
        // Wrap the HttpDataSource in a DefaultDataSource so it can safely handle data: URIs and file: URIs
        val upstreamFactory = DataSource.Factory {
            val httpDataSource = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)

            currentUserAgent?.let { httpDataSource.setUserAgent(it) }
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
            httpDataSource.setDefaultRequestProperties(props)

            val baseDataSource = DefaultDataSource.Factory(context, httpDataSource).createDataSource()
            baseDataSource
        }

        return androidx.media3.datasource.ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val redactedUri = dataSpec.uri.toString().replace(Regex("([?&])(auth-token|sig|mac|token)=([^&]+)"), "$1$2=REDACTED")
            android.util.Log.d("HLS_HTTP", "Requesting: $redactedUri")
            dataSpec
        }
    }"""
if old_factory:
    hls_content = hls_content.replace(old_factory.group(0), new_factory)

old_cache_factory = """            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, getDataSourceFactory(context)))
            // DO NOT STRIP QUERY FROM CACHE KEY if it's the primary content identifier, or at least
            // ensure the query is not mistakenly stripped from the URI itself by some Exoplayer bug.
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (readOnly) f.setCacheWriteDataSinkFactory(null)"""

new_cache_factory = """            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, getDataSourceFactory(context)))
            // DO NOT STRIP QUERY FROM CACHE KEY if it's the primary content identifier, or at least
            // ensure the query is not mistakenly stripped from the URI itself by some Exoplayer bug.
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(if (readOnly) androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR else androidx.media3.datasource.cache.CacheDataSource.FLAG_BLOCK_ON_CACHE)
        if (readOnly) f.setCacheWriteDataSinkFactory(null)"""
hls_content = hls_content.replace(old_cache_factory, new_cache_factory)

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.write(hls_content)


with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "r") as f:
    yt_content = f.read()

import re

# Add android.webkit.CookieManager to imports
if "android.webkit.CookieManager" not in yt_content:
    yt_content = yt_content.replace("import android.widget.Toast", "import android.webkit.CookieManager\nimport android.widget.Toast")

# Update startCommand parameter extraction
old_startCommand = """            val title = intent.getStringExtra(EXTRA_TITLE) ?: "YouTube Video"
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            val cookie = intent.getStringExtra(EXTRA_COOKIE)
            val referer = intent.getStringExtra(EXTRA_REFERER)"""

new_startCommand = """            val title = intent.getStringExtra(EXTRA_TITLE) ?: "YouTube Video"
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            val referer = intent.getStringExtra(EXTRA_REFERER) ?: "https://www.youtube.com/" """

yt_content = yt_content.replace(old_startCommand, new_startCommand)

# Update processDownload signature and call
old_process_call = """processDownload(videoUrl, audioUrl, title, mimeType, userAgent, cookie, referer, notificationId)"""
new_process_call = """processDownload(videoUrl, audioUrl, title, mimeType, userAgent, referer, notificationId)"""
yt_content = yt_content.replace(old_process_call, new_process_call)

old_process_def = """    private suspend fun processDownload(
        videoUrl: String,
        audioUrl: String?,
        title: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?,
        referer: String?,
        notificationId: Int
    )"""

new_process_def = """    private suspend fun processDownload(
        videoUrl: String,
        audioUrl: String?,
        title: String,
        mimeType: String,
        userAgent: String?,
        referer: String,
        notificationId: Int
    )"""

yt_content = yt_content.replace(old_process_def, new_process_def)

# Update the downloadFile calls inside processDownload
old_download_video_call = """val videoSuccess = downloadFile(videoUrl, videoTempFile, userAgent, cookie, referer) { progress ->"""
new_download_video_call = """val videoSuccess = downloadFile(videoUrl, videoTempFile, userAgent, referer) { progress ->"""
yt_content = yt_content.replace(old_download_video_call, new_download_video_call)

old_download_audio_call = """val audioSuccess = downloadFile(audioUrl, audioTempFile, userAgent, cookie, referer) { progress ->"""
new_download_audio_call = """val audioSuccess = downloadFile(audioUrl, audioTempFile, userAgent, referer) { progress ->"""
yt_content = yt_content.replace(old_download_audio_call, new_download_audio_call)


old_mux_throw = """throw Exception("Muxing failed")"""
new_mux_throw = """throw Exception("YOUTUBE_MUXING_FAILED")"""
yt_content = yt_content.replace(old_mux_throw, new_mux_throw)


# Replace downloadFile implementation entirely
old_download_func = re.search(r"    private suspend fun downloadFile\(urlStr: String, destination: File, userAgent: String\?, cookie: String\?, referer: String\?, onProgress: \(Int\) -> Unit\): Boolean = withContext\(Dispatchers.IO\) \{.*?    \}", yt_content, re.DOTALL)

new_download_func = """    private suspend fun downloadFile(urlStr: String, destination: File, userAgent: String?, referer: String, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val maxRetries = 3
        var attempt = 0
        val redactedUrl = urlStr.replace(Regex("([?&])(sig|signature|s|key|ip|expire|token|n)=([^&]+)"), "$1$2=REDACTED")
        android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] Starting download for: $redactedUrl")

        while (attempt < maxRetries) {
            var input: BufferedInputStream? = null
            var output: FileOutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", referer)
                connection.setRequestProperty("Origin", "https://www.youtube.com")
                connection.setRequestProperty("Accept", "*/*")

                // Dynamically fetch cookies strictly scoped to the actual destination host
                val cookie = CookieManager.getInstance().getCookie(urlStr)
                android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] media request cookies available: ${cookie != null}")
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }

                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode == 401 || responseCode == 403 || responseCode == 404) {
                    throw Exception("YOUTUBE_MEDIA_URL_EXPIRED")
                }
                if (responseCode !in 200..299) {
                     throw Exception("YOUTUBE_MEDIA_REQUEST_FAILED: HTTP $responseCode")
                }

                val contentType = connection.contentType ?: ""
                android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] Content-Type: $contentType")
                if (contentType.contains("text/html") || contentType.contains("application/json") || contentType.startsWith("image/")) {
                     throw Exception("YOUTUBE_MEDIA_INVALID_RESPONSE")
                }

                val fileLength = connection.contentLength
                input = BufferedInputStream(connection.inputStream)
                output = FileOutputStream(destination)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    if (!isActive) return@withContext false
                    total += count
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress > lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                output.flush()
                return@withContext true
            } catch (e: Exception) {
                val eMsg = e.message ?: ""
                if (eMsg.contains("YOUTUBE_MEDIA_URL_EXPIRED") || eMsg.contains("YOUTUBE_MEDIA_INVALID_RESPONSE")) {
                    throw e // Permanent errors, do not retry
                }
                android.util.Log.e("YouTubeDownloadService", "[YOUTUBE_TRACE] Transient error on attempt ${attempt + 1}: ${e.message}")
                attempt++
                if (attempt >= maxRetries) {
                    throw Exception("YOUTUBE_MEDIA_REQUEST_FAILED")
                }
                kotlinx.coroutines.delay(1000L * attempt) // Exponential backoff 1s, 2s
            } finally {
                output?.close()
                input?.close()
                connection?.disconnect()
            }
        }
        return@withContext false
    }"""

if old_download_func:
    yt_content = yt_content.replace(old_download_func.group(0), new_download_func)

old_mux_search = re.search(r"    private fun muxVideoAndAudio\(.*?    private fun saveToDownloads\(", yt_content, re.DOTALL)
if old_mux_search:
    new_mux = """    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outFile: File, mimeType: String): Boolean {
        try {
            android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] mux started")
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)

            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)

            val format = if (mimeType.contains("webm") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            val muxer = MediaMuxer(outFile.absolutePath, format)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerVideoTrackIndex = -1
            var muxerAudioTrackIndex = -1

            // Find Video Track
            for (i in 0 until videoExtractor.trackCount) {
                val trackFormat = videoExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = i
                    muxerVideoTrackIndex = muxer.addTrack(trackFormat)
                    break
                }
            }

            // Find Audio Track
            for (i in 0 until audioExtractor.trackCount) {
                val trackFormat = audioExtractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = i
                    muxerAudioTrackIndex = muxer.addTrack(trackFormat)
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
                android.util.Log.e("YouTubeDownloadService", "[YOUTUBE_TRACE] Required tracks not found for muxing")
                return false
            }

            muxer.start()

            // Copy Video
            val videoBuffer = ByteBuffer.allocate(1024 * 1024)
            val videoBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                if (sampleSize < 0) break
                videoBufferInfo.offset = 0
                videoBufferInfo.size = sampleSize
                videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrackIndex, videoBuffer, videoBufferInfo)
                videoExtractor.advance()
            }

            // Copy Audio
            val audioBuffer = ByteBuffer.allocate(512 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (sampleSize < 0) break
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrackIndex, audioBuffer, audioBufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()

            android.util.Log.d("YouTubeDownloadService", "[YOUTUBE_TRACE] mux succeeded")
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("YouTubeDownloadService", "[YOUTUBE_TRACE] mux failed: ${e.message}")
            return false
        }
    }

    private fun saveToDownloads("""

    yt_content = yt_content[:old_mux_search.start()] + new_mux + yt_content[old_mux_search.end() - len("    private fun saveToDownloads("):]

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "w") as f:
    f.write(yt_content)

print("HlsExport, HlsDownloadHelper, and YouTubeDownloadService reapplied.")
