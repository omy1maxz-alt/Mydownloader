package com.omymaxz.download

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YoutubeExtractorHelper {
    private const val TAG = "YoutubeExtractorHelper"

    @Synchronized
    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            Log.d(TAG, "YoutubeDL initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YoutubeDL: ${e.message}")
        }
    }

    suspend fun extractMedia(context: Context, url: String): MediaFile? = withContext(Dispatchers.IO) {
        try {
            // Ensure initialized before extraction
            init(context)

            val request = YoutubeDLRequest(url)

            // CRITICAL FIX: Force yt-dlp to find the absolute best SINGLE pre-combined MP4 file.
            // When we use `bestvideo+bestaudio`, yt-dlp needs local FFmpeg to merge them into a single file on disk.
            // Because we are streaming `info.url` over the network directly into ExoPlayer, we MUST request a pre-merged format.
            request.addOption("-f", "best[ext=mp4]/best")

            val info = YoutubeDL.getInstance().getInfo(request)

            val title = info.title ?: "YouTube_Video"
            // Fallback to manifest URL if direct URL is not available or is a raw format that requires DASH
            val streamUrl = if (info.manifestUrl != null && info.manifestUrl.isNotEmpty()) info.manifestUrl else info.url

            if (streamUrl.isNullOrEmpty()) {
                Log.e(TAG, "Failed to extract a direct MP4 stream URL. Info: ${info.title}")
                return@withContext null
            }

            Log.d(TAG, "Successfully extracted YouTube MP4: $streamUrl")

            // yt-dlp might still return a DASH manifest or HLS manifest if a direct mp4 isn't available
            // we must properly type it to prevent ExoPlayer ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            var finalMimeType = "video/mp4"
            if (streamUrl.contains(".mpd") || streamUrl.contains("manifest/dash")) {
                finalMimeType = "application/dash+xml"
            } else if (streamUrl.contains(".m3u8") || streamUrl.contains("manifest/hls")) {
                finalMimeType = "application/x-mpegURL"
            }

            return@withContext MediaFile(
                url = streamUrl,
                title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                mimeType = finalMimeType,
                quality = "Best Available",
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
