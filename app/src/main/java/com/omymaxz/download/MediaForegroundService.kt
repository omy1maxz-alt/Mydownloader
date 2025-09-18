package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle

class MediaForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val ACTION_NEXT = "com.omymaxz.download.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.omymaxz.download.ACTION_PREVIOUS"
        const val ACTION_START_PROACTIVE = "com.omymaxz.download.ACTION_START_PROACTIVE"
        const val ACTION_MEDIA_CONTROL = "com.omymaxz.download.ACTION_MEDIA_CONTROL"
        const val EXTRA_COMMAND = "com.omymaxz.download.EXTRA_COMMAND"
        const val EXTRA_HAS_NEXT = "com.omymaxz.download.EXTRA_HAS_NEXT"
        const val EXTRA_HAS_PREVIOUS = "com.omymaxz.download.EXTRA_HAS_PREVIOUS"
        const val EXTRA_IS_PLAYING = "com.omymaxz.download.EXTRA_IS_PLAYING"
        const val EXTRA_TITLE = "com.omymaxz.download.EXTRA_TITLE"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> sendMediaControlBroadcast("play")
            ACTION_PAUSE -> sendMediaControlBroadcast("pause")
            ACTION_NEXT -> sendMediaControlBroadcast("next")
            ACTION_PREVIOUS -> sendMediaControlBroadcast("previous")
            ACTION_STOP -> {
                sendMediaControlBroadcast("stop")
                stopSelf()
            }
            else -> {
                // This is the case when the service is started from MainActivity
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Web Video"
                val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) ?: false
                val hasNext = intent?.getBooleanExtra(EXTRA_HAS_NEXT, false) ?: false
                val hasPrevious = intent?.getBooleanExtra(EXTRA_HAS_PREVIOUS, false) ?: false
                startForeground(NOTIFICATION_ID, buildNotification(title, isPlaying, hasNext, hasPrevious))
            }
        }
        return START_STICKY
    }

    private fun sendMediaControlBroadcast(command: String) {
        val intent = Intent(ACTION_MEDIA_CONTROL).apply {
            putExtra(EXTRA_COMMAND, command)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun buildNotification(title: String, isPlaying: Boolean, hasNext: Boolean, hasPrevious: Boolean): Notification {
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        val playPauseIntent = Intent(this, MediaForegroundService::class.java).apply { action = playPauseAction }
        val playPausePendingIntent = PendingIntent.getService(this, 1, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, MediaForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val contentText = if (isPlaying) "Playing in background" else "Paused"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)

        if (hasPrevious) {
            val prevIntent = Intent(this, MediaForegroundService::class.java).apply { action = ACTION_PREVIOUS }
            val prevPendingIntent = PendingIntent.getService(this, 3, prevIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(R.drawable.ic_skip_previous, "Previous", prevPendingIntent)
        }

        builder.addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)

        if (hasNext) {
            val nextIntent = Intent(this, MediaForegroundService::class.java).apply { action = ACTION_NEXT }
            val nextPendingIntent = PendingIntent.getService(this, 4, nextIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)
        }

        builder.setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for background media playback"
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}