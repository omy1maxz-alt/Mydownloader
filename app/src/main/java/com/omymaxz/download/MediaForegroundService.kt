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
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

class MediaForegroundService : Service() {

    private val binder = LocalBinder()
    private var exoPlayer: ExoPlayer? = null
    private lateinit var notificationManager: NotificationManager

    private var mediaTitle = "Web Video"

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val ACTION_START_PROACTIVE = "com.omymaxz.download.ACTION_START_PROACTIVE"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROACTIVE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Background task is running"))
            }
            ACTION_PLAY -> {
                mediaTitle = intent.getStringExtra("title") ?: "Web Video"
                val mediaUrl = intent.getStringExtra("url")
                @Suppress("UNCHECKED_CAST")
                val headers = intent.getSerializableExtra("headers") as? HashMap<String, String>
                val startPosition = intent.getFloatExtra("position", 0f)

                if (mediaUrl != null) {
                    startPlayback(mediaUrl, headers, startPosition)
                }
            }
            ACTION_PAUSE -> exoPlayer?.pause()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(url: String, headers: HashMap<String, String>?, position: Float) {
        exoPlayer?.release() // Release any old player

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
            headers?.let { dataSourceFactory.setDefaultRequestProperties(it) }

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))

            setMediaSource(mediaSource)
            seekTo((position * 1000).toLong())
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotification()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        stopPlayback()
                    }
                    updateNotification()
                }
            })
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopPlayback() {
        exoPlayer?.release()
        exoPlayer = null
        stopForeground(true)
        stopSelf()
    }

    private fun updateNotification() {
        if (exoPlayer != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(customContentText: String? = null): Notification {
        val isPlaying = exoPlayer?.isPlaying ?: false

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

        val contentText = customContentText ?: if (isPlaying) "Playing in background" else "Paused"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(openAppPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .build()
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

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}