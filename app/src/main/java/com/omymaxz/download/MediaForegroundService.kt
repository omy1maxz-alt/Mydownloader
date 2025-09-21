package com.omymaxz.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager

class MediaForegroundService : Service() {

    private var player: ExoPlayer? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_HANDOFF = "com.omymaxz.download.ACTION_HANDOFF"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val ACTION_NEXT = "com.omymaxz.download.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.omymaxz.download.ACTION_PREVIOUS"

        const val EXTRA_URL = "com.omymaxz.download.EXTRA_URL"
        const val EXTRA_POSITION = "com.omymaxz.download.EXTRA_POSITION"
        const val EXTRA_TITLE = "com.omymaxz.download.EXTRA_TITLE"
    }

    override fun onCreate() {
        super.onCreate()
        val context: Context = this
        player = ExoPlayer.Builder(context).build()

        createNotificationChannel()

        playerNotificationManager = PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return player.currentMediaItem?.mediaMetadata?.title ?: "Web Video"
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    val openAppIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    return PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }

                override fun getCurrentContentText(player: Player): CharSequence? = null
                override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): android.graphics.Bitmap? = null
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(notificationId: Int, notification: android.app.Notification, ongoing: Boolean) {
                    if (ongoing) {
                        startForeground(notificationId, notification)
                    } else {
                        stopForeground(false)
                    }
                }
                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    stopSelf()
                }
            })
            .build()

        playerNotificationManager?.setPlayer(player)
        playerNotificationManager?.setUseNextAction(true)
        playerNotificationManager?.setUsePreviousAction(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANDOFF -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val position = intent.getLongExtra(EXTRA_POSITION, 0)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Web Video"
                if (url != null) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
                        .build()

                    player?.setMediaItem(mediaItem)
                    player?.seekTo(position)
                    player?.prepare()
                    player?.play()
                }
            }
            ACTION_PLAY -> player?.play()
            ACTION_PAUSE -> player?.pause()
            ACTION_STOP -> stopSelf()
            ACTION_NEXT -> player?.seekToNext()
            ACTION_PREVIOUS -> player?.seekToPrevious()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for background media playback"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: 0
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        player?.release()
        player = null
    }
}
