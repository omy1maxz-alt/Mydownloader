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
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class MediaForegroundService : Service() {

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var mediaControlCallback: MediaControlCallback? = null
    private var isPlaying = false
    private var mediaTitle = "Web Video"

    interface MediaControlCallback {
        fun onPlayPause()
        fun onStop()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY_PAUSE = "com.omymaxz.download.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MyDownloaderMediaSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                super.onPlay()
                isPlaying = true
                updateNotification()
                mediaControlCallback?.onPlayPause()
            }

            override fun onPause() {
                super.onPause()
                isPlaying = false
                updateNotification()
                mediaControlCallback?.onPlayPause()
            }

            override fun onStop() {
                super.onStop()
                isPlaying = false
                mediaControlCallback?.onStop()
                stopSelf()
            }
        })
        mediaSession.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                isPlaying = !isPlaying
                updateNotification()
                mediaControlCallback?.onPlayPause()
            }
            ACTION_STOP -> {
                mediaControlCallback?.onStop()
                stopForeground(true)
                stopSelf()
            }
            ACTION_PLAY -> {
                mediaTitle = intent.getStringExtra("title") ?: "Web Video"
                isPlaying = true
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            else -> {
                 MediaButtonReceiver.handleIntent(mediaSession, intent)
            }
        }
        return START_STICKY
    }
    
    fun setMediaControlCallback(callback: MediaControlCallback?) {
        this.mediaControlCallback = callback
    }

    fun updateMediaInfo(title: String) {
        mediaTitle = title
        updateNotification()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val playPauseIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(this, 1, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaTitle)
            .setContentText("Playing in background")
            .setSmallIcon(R.drawable.ic_notification) // You'll need to create this icon
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(openAppPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }
}
