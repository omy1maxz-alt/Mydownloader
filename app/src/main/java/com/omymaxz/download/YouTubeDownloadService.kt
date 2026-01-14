package com.omymaxz.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class YouTubeDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "com.omymaxz.download.action.START_DOWNLOAD"
        const val EXTRA_VIDEO_URL = "com.omymaxz.download.extra.VIDEO_URL"
        const val EXTRA_AUDIO_URL = "com.omymaxz.download.extra.AUDIO_URL" // Optional
        const val EXTRA_TITLE = "com.omymaxz.download.extra.TITLE"
        const val EXTRA_MIME_TYPE = "com.omymaxz.download.extra.MIME_TYPE" // Target mime type
        const val EXTRA_USER_AGENT = "com.omymaxz.download.extra.USER_AGENT"
        const val EXTRA_COOKIE = "com.omymaxz.download.extra.COOKIE"
        const val CHANNEL_ID = "youtube_download_channel"
        const val NOTIFICATION_ID_BASE = 2000
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var notificationManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_DOWNLOAD) {
            val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
            val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "YouTube Video"
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
            val cookie = intent.getStringExtra(EXTRA_COOKIE)

            if (videoUrl != null) {
                val notificationId = NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 1000).toInt()
                startForeground(notificationId, createNotification(title, "Starting download...", 0, true))

                serviceScope.launch {
                    processDownload(videoUrl, audioUrl, title, mimeType, userAgent, cookie, notificationId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "YouTube Downloads"
            val descriptionText = "Notifications for YouTube downloads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, status: String, progress: Int, indeterminate: Boolean): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private suspend fun processDownload(
        videoUrl: String,
        audioUrl: String?,
        title: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?,
        notificationId: Int
    ) {
        val tempDir = cacheDir
        val safeTitle = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val videoTempFile = File(tempDir, "${safeTitle}_video.tmp")
        val audioTempFile = File(tempDir, "${safeTitle}_audio.tmp")

        try {
            // 1. Download Video
            updateNotification(notificationId, title, "Downloading video...", 0, true)
            val videoSuccess = downloadFile(videoUrl, videoTempFile, userAgent, cookie) { progress ->
                updateNotification(notificationId, title, "Downloading video: $progress%", progress, false)
            }
            if (!videoSuccess) throw Exception("Failed to download video")

            // 2. Download Audio if needed
            if (audioUrl != null) {
                updateNotification(notificationId, title, "Downloading audio...", 0, true)
                val audioSuccess = downloadFile(audioUrl, audioTempFile, userAgent, cookie) { progress ->
                    updateNotification(notificationId, title, "Downloading audio: $progress%", progress, false)
                }
                if (!audioSuccess) throw Exception("Failed to download audio")
            }

            // 3. Mux or Move
            val finalFileName = "$safeTitle.${if (mimeType.contains("webm")) "webm" else "mp4"}"

            if (audioUrl != null) {
                updateNotification(notificationId, title, "Processing...", 0, true)
                val outFile = File(tempDir, "muxed_$finalFileName")
                val muxSuccess = muxVideoAndAudio(videoTempFile, audioTempFile, outFile, mimeType)
                if (muxSuccess) {
                    saveToDownloads(outFile, finalFileName, mimeType)
                    outFile.delete()
                } else {
                    throw Exception("Muxing failed")
                }
            } else {
                saveToDownloads(videoTempFile, finalFileName, mimeType)
            }

            // Cleanup
            videoTempFile.delete()
            audioTempFile.delete()

            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Download complete: $title", Toast.LENGTH_SHORT).show()
            }
            notificationManager?.cancel(notificationId)

        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification(notificationId, title, "Error: ${e.message}", 0, false)
            // Keep error notification for a while or convert to cancellable
            // For now, let's just leave it or cancel it?
            // Better to show error.
             val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Download Failed")
                .setContentText(e.message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build()
            notificationManager?.notify(notificationId, errorNotification)
        }

        // Stop service if no more jobs?
        // We are using START_NOT_STICKY and one job per startCommand logic roughly.
        // Ideally we track active downloads. For simplicity, we just let it finish.
        // Android will kill the service eventually if not foreground.
        // We called startForeground, so we should call stopForeground when done if this is the last one.
        // But we might have multiple concurrent downloads.
        // A robust service manages a queue.
        // For now, let's just stopForeground if we assume single download or handle it simply.
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun updateNotification(id: Int, title: String, status: String, progress: Int, indeterminate: Boolean) {
        notificationManager?.notify(id, createNotification(title, status, progress, indeterminate))
    }

    private suspend fun downloadFile(urlStr: String, destination: File, userAgent: String?, cookie: String?, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var input: BufferedInputStream? = null
        var output: FileOutputStream? = null
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Referer", "https://www.youtube.com/")
            connection.connect()

            if (connection.responseCode !in 200..299) {
                 return@withContext false
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
            e.printStackTrace()
            return@withContext false
        } finally {
            output?.close()
            input?.close()
            connection?.disconnect()
        }
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outFile: File, mimeType: String): Boolean {
        try {
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
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = i
                    muxerVideoTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            // Find Audio Track
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = i
                    muxerAudioTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
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

            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun saveToDownloads(file: File, filename: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, filename)
            file.copyTo(destFile, overwrite = true)
        }
    }
}
