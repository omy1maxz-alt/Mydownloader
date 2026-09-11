package com.omymaxz.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat

object YoutubeExtractorHelper {
    private const val TAG = "YoutubeExtractorHelper"

    @Synchronized
    fun init(context: Context) {
        try {
            if (!NewPipe.getDownloader().equals(null)) {
                // Already initialized
                return
            }
        } catch (e: Exception) {
            // NewPipe throws if downloader is null
        }

        try {
            NewPipe.init(NewPipeDownloader(), Localization.DEFAULT)
            Log.d(TAG, "NewPipeExtractor initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NewPipeExtractor: ${e.message}")
        }
    }

    suspend fun extractMedia(context: Context, url: String): MediaFile? = withContext(Dispatchers.IO) {
        try {
            init(context)

            val service = ServiceList.YouTube
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            val title = extractor.name ?: "YouTube_Video"

            // NewPipe categorizes streams into VideoOnly, AudioOnly, and VideoStreams (which usually contain both if available on older formats,
            // but YouTube mostly uses DASH where they are separate).
            // ExoPlayer can seamlessly play DASH manifests, so we should look for DASH manifest URL first if available.
            val dashManifestUrl = extractor.dashMpdUrl
            val hlsManifestUrl = extractor.hlsUrl

            if (!dashManifestUrl.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube DASH manifest: $dashManifestUrl")
                return@withContext MediaFile(
                    url = dashManifestUrl,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                    mimeType = "application/dash+xml",
                    quality = "Adaptive DASH",
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true
                )
            }

            if (!hlsManifestUrl.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube HLS manifest: $hlsManifestUrl")
                return@withContext MediaFile(
                    url = hlsManifestUrl,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                    mimeType = "application/x-mpegURL",
                    quality = "Adaptive HLS",
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true
                )
            }

            // Fallback to searching for the highest quality combined video/audio stream (like 360p or 720p non-DASH if it exists)
            val videoStreams = extractor.videoStreams
            val bestCombinedStream = videoStreams.maxByOrNull { it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }

            if (bestCombinedStream != null && !bestCombinedStream.content.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube MP4 combined: ${bestCombinedStream.content}")
                return@withContext MediaFile(
                    url = bestCombinedStream.content,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_"),
                    mimeType = "video/mp4",
                    quality = bestCombinedStream.resolution,
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true
                )
            }

            Log.e(TAG, "Failed to extract any suitable streams. VideoOnly+AudioOnly merge without DASH not natively supported by basic ExoPlayer setup yet.")
            return@withContext null

        } catch (e: Exception) {
            Log.e(TAG, "NewPipeExtractor extraction failed: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
}
