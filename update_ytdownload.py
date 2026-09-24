import re

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "r") as f:
    content = f.read()

# Add android.webkit.CookieManager to imports
if "android.webkit.CookieManager" not in content:
    content = content.replace("import android.widget.Toast", "import android.webkit.CookieManager\nimport android.widget.Toast")

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

content = content.replace(old_startCommand, new_startCommand)

# Update processDownload signature and call
old_process_call = """processDownload(videoUrl, audioUrl, title, mimeType, userAgent, cookie, referer, notificationId)"""
new_process_call = """processDownload(videoUrl, audioUrl, title, mimeType, userAgent, referer, notificationId)"""
content = content.replace(old_process_call, new_process_call)

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

content = content.replace(old_process_def, new_process_def)

# Update the downloadFile calls inside processDownload
old_download_video_call = """val videoSuccess = downloadFile(videoUrl, videoTempFile, userAgent, cookie, referer) { progress ->"""
new_download_video_call = """val videoSuccess = downloadFile(videoUrl, videoTempFile, userAgent, referer) { progress ->"""
content = content.replace(old_download_video_call, new_download_video_call)

old_download_audio_call = """val audioSuccess = downloadFile(audioUrl, audioTempFile, userAgent, cookie, referer) { progress ->"""
new_download_audio_call = """val audioSuccess = downloadFile(audioUrl, audioTempFile, userAgent, referer) { progress ->"""
content = content.replace(old_download_audio_call, new_download_audio_call)


old_mux_throw = """throw Exception("Muxing failed")"""
new_mux_throw = """throw Exception("YOUTUBE_MUXING_FAILED")"""
content = content.replace(old_mux_throw, new_mux_throw)


# Replace downloadFile implementation entirely
old_download_func = re.search(r"    private suspend fun downloadFile\(urlStr: String, destination: File, userAgent: String\?, cookie: String\?, referer: String\?, onProgress: \(Int\) -> Unit\): Boolean = withContext\(Dispatchers.IO\) \{.*?    \}", content, re.DOTALL)

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
    content = content.replace(old_download_func.group(0), new_download_func)
else:
    print("Could not find downloadFile function to replace")


with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "w") as f:
    f.write(content)

print("Done")
