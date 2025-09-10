package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

class MediaForegroundService : Service() {

    private val binder = LocalBinder()
    private var isPlaying = false
    private var mediaTitle = "Web Video"
    private var mediaUrl: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                mediaTitle = intent.getStringExtra("title") ?: "Web Video"
                mediaUrl = intent.getStringExtra("url")
                isPlaying = true
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_PAUSE -> {
                isPlaying = false
                updateNotification()
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun setMediaPlaying(playing: Boolean, title: String? = null) {
        if (title != null) {
            mediaTitle = title
        }
        isPlaying = playing
        if (isPlaying) {
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            updateNotification()
        }
    }

    fun updateMediaInfo(title: String) {
        mediaTitle = title
        updateNotification()
    }

    private fun updateNotification() {
        if (::notificationManager.isInitialized) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private lateinit var notificationManager: NotificationManager

    private fun buildNotification(): Notification {
        if (!::notificationManager.isInitialized) {
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPauseIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            1,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaTitle)
            .setContentText(if (isPlaying) "Playing in background" else "Paused")
            .setSmallIcon(R.drawable.ic_notification) // Make sure this icon exists
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(openAppPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying) // Only ongoing when playing
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop foreground here, let the stop action handle it
    }
}
