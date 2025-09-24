package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class MediaForegroundService : Service() {

    private var mediaSession: MediaSessionCompat? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val ACTION_NEXT = "com.omymaxz.download.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.omymaxz.download.ACTION_PREVIOUS"
        const val EXTRA_TITLE = "com.omymaxz.download.EXTRA_TITLE"
        const val EXTRA_IS_PLAYING = "com.omymaxz.download.EXTRA_IS_PLAYING"
        const val EXTRA_CURRENT_POSITION = "com.omymaxz.download.EXTRA_CURRENT_POSITION"
        const val EXTRA_DURATION = "com.omymaxz.download.EXTRA_DURATION"
        const val EXTRA_HAS_NEXT = "com.omymaxz.download.EXTRA_HAS_NEXT"
        const val EXTRA_HAS_PREVIOUS = "com.omymaxz.download.EXTRA_HAS_PREVIOUS"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MediaForegroundServiceSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    sendMediaControlBroadcast(ACTION_PLAY)
                }

                override fun onPause() {
                    sendMediaControlBroadcast(ACTION_PAUSE)
                }

                override fun onStop() {
                    sendMediaControlBroadcast(ACTION_STOP)
                }

                override fun onSkipToNext() {
                    sendMediaControlBroadcast(ACTION_NEXT)
                }

                override fun onSkipToPrevious() {
                    sendMediaControlBroadcast(ACTION_PREVIOUS)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Web Video"
        val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
        val position = intent.getLongExtra(EXTRA_CURRENT_POSITION, 0L)
        val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
        val hasNext = intent.getBooleanExtra(EXTRA_HAS_NEXT, false)
        val hasPrevious = intent.getBooleanExtra(EXTRA_HAS_PREVIOUS, false)

        val playbackState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        var actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (hasNext) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        if (hasPrevious) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }

        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build())

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(playbackState, position, 1.0f)
                .setActions(actions)
                .build()
        )

        startForeground(NOTIFICATION_ID, buildNotification(title, isPlaying, hasNext, hasPrevious))

        return START_STICKY
    }

    private fun sendMediaControlBroadcast(action: String) {
        val intent = Intent(MainActivity.ACTION_MEDIA_CONTROL).apply {
            putExtra(MainActivity.EXTRA_COMMAND, action)
            `package` = this@MediaForegroundService.packageName
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(title: String, isPlaying: Boolean, hasNext: Boolean, hasPrevious: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        builder
            .setContentTitle(title)
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_notification)

        if (hasPrevious) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous,
                    "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                )
            )
        }

        builder.addAction(
            NotificationCompat.Action(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                if (isPlaying) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        )

        if (hasNext) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_next,
                    "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                )
            )
        }

        builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for background media playback"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(serviceChannel)
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
    }
}