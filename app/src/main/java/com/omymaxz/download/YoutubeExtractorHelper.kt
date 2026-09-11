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

            val request = YoutubeDLRequest(url)

            // CRITICAL FIX: Force yt-dlp to find a combined MP4 format.
            // This prevents it from returning a DASH manifest (.mpd) or an audio-only file.
            request.addOption("-f", "bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best")

            val info = YoutubeDL.getInstance().getInfo(request)

            val title = info.title ?: "YouTube_Video"

            // Check if we got a direct URL (combined MP4)
            val streamUrl = info.url

            if (streamUrl.isNullOrEmpty()) {
                Log.e(TAG, "Failed to extract a direct MP4 stream URL. Info: ${info.title}")
                return@withContext null
            }

            Log.d(TAG, "Successfully extracted YouTube MP4: $streamUrl")

            return@withContext MediaFile(
                url = streamUrl,
                title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                mimeType = "video/mp4", // Force MP4 MIME type for your player/exporter
                quality = "Best Available MP4",
                category = MediaCategory.VIDEO,
                fileSize = "Unknown",
                language = null,
                isMainContent = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "YoutubeDL extraction failed: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
}
