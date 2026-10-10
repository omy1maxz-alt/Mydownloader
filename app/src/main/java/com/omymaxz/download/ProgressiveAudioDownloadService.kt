package com.omymaxz.download

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.webkit.CookieManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ProgressiveAudioDownloadService : Service() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_COOKIE = "extra_cookie"
        private const val TAG = "ProgressiveAudioDownloadService"
        const val NOTIFICATION_ID = 4001
    }


    private fun createNotification(title: String): android.app.Notification {
        val channelId = "progressive_audio_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Downloads", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Downloading Audio")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "audio.m4a"
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)

        startForeground(NOTIFICATION_ID, createNotification(title))

        val activeCookie = if (cookie.isNullOrEmpty()) {
            CookieManager.getInstance().getCookie(url) ?: ""
        } else {
            cookie
        }


        CoroutineScope(Dispatchers.IO).launch {
            downloadAudio(url, title, userAgent, referer, activeCookie)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun downloadAudio(urlStr: String, title: String, userAgent: String?, referer: String?, cookie: String?) {
        var tempFile: File? = null
        try {
            Log.d(TAG, "[AUDIO_DOWNLOAD] url=$urlStr")
            Log.d(TAG, "[AUDIO_DOWNLOAD] userAgentPresent=${userAgent != null} refererPresent=${referer != null} cookiePresent=${cookie != null}")

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "*/*")
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
            if (referer != null) connection.setRequestProperty("Referer", referer)
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)

            connection.connect()

            val status = connection.responseCode
            val contentType = connection.contentType ?: ""
            val contentLength = connection.contentLength
            val contentRange = connection.getHeaderField("Content-Range")

            Log.d(TAG, "[AUDIO_DOWNLOAD] status=$status contentType=$contentType contentLength=$contentLength contentRange=$contentRange")

            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                Log.e(TAG, "[AUDIO_VALIDATE] Invalid HTTP status: $status. Aborting.")
                return
            }

            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                Log.e(TAG, "[AUDIO_VALIDATE] Invalid Content-Type: $contentType. Aborting.")
                return
            }

            tempFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}.tmp")
            val outputStream = FileOutputStream(tempFile)
            val inputStream: InputStream = connection.inputStream

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            var firstBytes = ByteArray(0)

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (totalRead == 0L && bytesRead >= 12) {
                    firstBytes = buffer.copyOfRange(0, 12)
                }
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            val sigHex = firstBytes.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "[AUDIO_VALIDATE] signature=$sigHex fileSize=${tempFile.length()}")

            // Perform simple MP4/M4A validation (ftyp box)
            val isValid = if (title.endsWith(".m4a", true) || title.endsWith(".mp4", true)) {
                val hasFtyp = sigHex.contains("66747970") // "ftyp" in hex
                hasFtyp
            } else {
                true // Allow other formats without strict container checks for now
            }

            Log.d(TAG, "[AUDIO_VALIDATE] containerValid=$isValid")

            if (!isValid) {
                Log.e(TAG, "[AUDIO_VALIDATE] Invalid container signature for audio file. Aborting.")
                tempFile.delete()
                return
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var finalFile = File(downloadsDir, title)

            // Auto-increment filename if it exists
            var counter = 1
            val baseName = title.substringBeforeLast('.')
            val ext = title.substringAfterLast('.')
            while (finalFile.exists()) {
                finalFile = File(downloadsDir, "${baseName}_$counter.$ext")
                counter++
            }

            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()

            Log.d(TAG, "[AUDIO_VALIDATE] final destination path=${finalFile.absolutePath} final file length=${finalFile.length()}")

        } catch (e: Exception) {
            Log.e(TAG, "[AUDIO_DOWNLOAD] Exception during audio download: ${e.message}", e)
            tempFile?.delete()
        }
    }
}
