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
            val downloader = NewPipe.getDownloader()
            if (downloader != null) {
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
            Log.e(TAG, "Failed to initialize NewPipeExtractor: ${e.message}", e)
        }
    }

    suspend fun extractMedia(context: Context, url: String): MediaFile? = withContext(Dispatchers.IO) {
        try {
            init(context)

            val service = ServiceList.YouTube
            val cleanUrl = if (url.contains("youtube.com/embed/")) {
                val videoId = url.substringAfter("embed/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            } else if (url.contains("youtube-nocookie.com/embed/")) {
                val videoId = url.substringAfter("embed/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            } else {
                url
            }
            Log.d(TAG, "Requesting NewPipe extraction for URL: $cleanUrl")
            val extractor = service.getStreamExtractor(cleanUrl)
            extractor.fetchPage()

            val title = extractor.name ?: "YouTube_Video"

            // NewPipe categorizes streams into VideoOnly, AudioOnly, and VideoStreams (which usually contain both if available on older formats,
            // but YouTube mostly uses DASH where they are separate).
            // ExoPlayer can seamlessly play DASH manifests, so we should look for DASH manifest URL first if available.
            val dashManifestUrl = extractor.dashMpdUrl
            val hlsManifestUrl = extractor.hlsUrl

            val subtitleList = mutableListOf<String>()
            try {
                // NewPipe's default list is TTML-only on YouTube, but getSubtitles(format)
                // exposes all caption tracks including YouTube's auto-generated tracks.
                val subtitleFormats = listOf(MediaFormat.VTT, MediaFormat.TTML, MediaFormat.SRT)
                subtitleFormats.forEach { format ->
                    runCatching {
                        extractor.getSubtitles(format).forEach { sub ->
                            val contentUrl = sub.content
                            if (!contentUrl.isNullOrBlank()) {
                                val langCode = sub.locale?.language.orEmpty()
                                val separator = if (contentUrl.contains("?")) "&" else "?"
                                val taggedUrl = if (langCode.isNotBlank() && !contentUrl.contains(Regex("[?&]lang="))) {
                                    "${contentUrl}${separator}lang=$langCode"
                                } else contentUrl
                                if (subtitleList.none { it.substringBefore("&lang=") == taggedUrl.substringBefore("&lang=") }) {
                                    subtitleList.add(taggedUrl)
                                }
                            }
                        }
                    }.onFailure { error ->
                        Log.d(TAG, "Subtitle format $format unavailable: ${error.message}")
                    }
                }
                Log.d(TAG, "Extracted ${subtitleList.size} subtitle tracks (including auto-generated when provided by YouTube).")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract YouTube subtitle tracks: ${e.message}", e)
            }

            var bestAudioUrl: String? = null
            var bestAudioExtension = "m4a"
            var bestAudioMimeType = "audio/mp4"
            try {
                val audioStreams = extractor.audioStreams
                val bestAudio = audioStreams.maxByOrNull { it.bitrate }
                bestAudioUrl = bestAudio?.content
                if (bestAudio != null) {
                    val format = bestAudio.format
                    bestAudioExtension = format?.suffix ?: "m4a"
                    bestAudioMimeType = format?.mimeType ?: "audio/mp4"
                    Log.d(TAG, "Best audio stream selected: suffix=$bestAudioExtension, mime=$bestAudioMimeType, codec=${format?.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract audioStreams explicitly: ${e.message}")
            }
            if (!dashManifestUrl.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube DASH manifest: $dashManifestUrl")
                return@withContext MediaFile(
                    url = dashManifestUrl,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".mp4", // Default container for adaptive dash video exports
                    mimeType = "application/dash+xml",
                    quality = "Adaptive DASH",
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true,
                    subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null,
                    audioUrl = bestAudioUrl
                )
            }

            if (!hlsManifestUrl.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube HLS manifest: $hlsManifestUrl")
                return@withContext MediaFile(
                    url = hlsManifestUrl,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".mp4",
                    mimeType = "application/x-mpegURL",
                    quality = "Adaptive HLS",
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true,
                    subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null,
                    audioUrl = bestAudioUrl
                )
            }

            // Fallback to searching for the highest quality combined video/audio stream (like 360p or 720p non-DASH if it exists)
            val videoStreams = extractor.videoStreams
            val bestCombinedStream = videoStreams.maxByOrNull { it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }

            if (bestCombinedStream != null && !bestCombinedStream.content.isNullOrEmpty()) {
                Log.d(TAG, "Successfully extracted YouTube MP4 combined: ${bestCombinedStream.content}")
                return@withContext MediaFile(
                    url = bestCombinedStream.content,
                    title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".mp4",
                    mimeType = "video/mp4",
                    quality = bestCombinedStream.resolution,
                    category = MediaCategory.VIDEO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true,
                    subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null,
                    audioUrl = bestAudioUrl
                )
            }

            val videoOnlyStreams = extractor.videoOnlyStreams
            if (videoOnlyStreams.isNotEmpty() && bestAudioUrl != null) {
                val highestVideo = videoOnlyStreams.maxByOrNull { it.resolution.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 }
                if (highestVideo != null && !highestVideo.content.isNullOrEmpty()) {
                    Log.d(TAG, "Falling back to separate highest quality VideoOnly stream: ${highestVideo.content} and Audio: ${bestAudioUrl!!}")
                    return@withContext MediaFile(
                        url = highestVideo.content,
                        title = title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".mp4",
                        mimeType = "video/mp4",
                        quality = highestVideo.resolution,
                        category = MediaCategory.VIDEO,
                        fileSize = "Unknown",
                        language = null,
                        isMainContent = true,
                        audioUrl = bestAudioUrl!!,
                        subtitleUrls = if (subtitleList.isNotEmpty()) subtitleList else null,
                    )
                }
            }
            if (bestAudioUrl != null) {
                Log.d(TAG, "Falling back to AudioOnly stream: $bestAudioUrl with ext=$bestAudioExtension")

                // Determine file extension
                val finalExt = if (bestAudioExtension.isNotBlank()) ".$bestAudioExtension" else ".m4a"
                val finalTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + "_AudioOnly" + finalExt

                return@withContext MediaFile(
                    url = bestAudioUrl,
                    title = finalTitle,
                    mimeType = bestAudioMimeType,
                    quality = "Audio",
                    category = MediaCategory.AUDIO,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true,
                    subtitleUrls = null
                )
            }
            Log.e(TAG, "Failed to extract any suitable streams. VideoOnly+AudioOnly merge without DASH not natively supported by basic ExoPlayer setup yet.")
            return@withContext null

        } catch (e: Exception) {
            Log.e(TAG, "NewPipeExtractor extraction failed: ${e.message}", e)
            e.printStackTrace()
            return@withContext null
        }
    }
}
