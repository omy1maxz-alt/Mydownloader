package com.omymaxz.download

import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "playback_service_channel"
        const val ACTION_CLOSE_BACKGROUND = "com.omymaxz.download.ACTION_CLOSE_BACKGROUND"
        private const val ACTION_START_BACKGROUND_AUDIO = "com.omymaxz.download.START_BACKGROUND_AUDIO"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(this)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 12_000_000, 1500, 2500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CustomPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player!!)
            .setId("PlaybackServiceSession")
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Custom Player background playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for Custom Player background playback"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_BACKGROUND) {
            player?.stop()
            player?.clearMediaItems()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra("video_title")
            ?: player?.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: "Background Audio"

        // Start foreground promptly when launched with startForegroundService().
        createNotificationChannel()

        val urlForNotification = intent?.getStringExtra("video_url")
            ?: player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val openPlayerIntent = Intent(this, CustomPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            urlForNotification?.let { putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, it) }
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, title)
        }
        val openPlayerPendingIntent = PendingIntent.getActivity(
            this,
            (urlForNotification ?: "custom-player").hashCode(),
            openPlayerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_CLOSE_BACKGROUND
        }
        val closePendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Custom Player")
            .setContentText(title)
            .setSubText("Custom Player • Background playback")
            .setContentIntent(openPlayerPendingIntent)
            .setDeleteIntent(closePendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Close",
                closePendingIntent
            )
            .setSmallIcon(R.drawable.ic_headset)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (intent?.action == ACTION_START_BACKGROUND_AUDIO) {
            val url = intent.getStringExtra("video_url")
            val position = intent.getLongExtra("current_position", 0L)

            if (!url.isNullOrBlank()) {
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build()
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.seekTo(position)
                player?.play()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player?.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }
}
