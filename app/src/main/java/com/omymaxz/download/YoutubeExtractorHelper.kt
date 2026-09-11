package com.omymaxz.download

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YoutubeExtractorHelper {
    private const val TAG = "YoutubeExtractorHelper"
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            YoutubeDL.getInstance().init(context)
            // Optional: You can also init FFmpeg here if needed for downloads:
            // FFmpeg.getInstance().init(context)
            isInitialized = true
            Log.d(TAG, "YoutubeDL initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL: ${e.message}")
        }
    }

    suspend fun extractMedia(url: String): MediaFile? = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.e(TAG, "YoutubeDL is not initialized.")
                return@withContext null
            }

            // We request the best video and audio.
            // We use the --dump-json argument implicitly by using getInfo.
            val request = YoutubeDLRequest(url)
            val info = YoutubeDL.getInstance().getInfo(request)

            val title = info.title ?: "YouTube Video"
            val duration = info.duration.toLong()

            // Prefer the DASH manifest if available for adaptive streaming
            var streamUrl = info.manifestUrl
            var mimeType = "application/dash+xml"

            // Fallback to a direct combined MP4 if no manifest is available
            if (streamUrl.isNullOrEmpty()) {
                streamUrl = info.url
                mimeType = "video/mp4"
            }

            if (streamUrl.isNullOrEmpty()) {
                Log.e(TAG, "Failed to extract any stream URL.")
                return@withContext null
            }

            return@withContext MediaFile(
                url = streamUrl,
                title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                mimeType = mimeType,
                quality = "Adaptive",
                category = MediaCategory.VIDEO,
                fileSize = "Unknown",
                language = null,
                isMainContent = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "YoutubeDL extraction failed: ${e.message}")
            return@withContext null
        }
    }
}
