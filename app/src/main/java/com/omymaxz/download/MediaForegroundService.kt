package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.session.MediaButtonReceiver

class MediaForegroundService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private val binder = LocalBinder()

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY_FROM_NOTIFICATION = "com.omymaxz.download.ACTION_PLAY_FROM_NOTIFICATION"
        const val ACTION_PAUSE_FROM_NOTIFICATION = "com.omymaxz.download.ACTION_PAUSE_FROM_NOTIFICATION"
        const val ACTION_NEXT_FROM_NOTIFICATION = "com.omymaxz.download.ACTION_NEXT_FROM_NOTIFICATION"
        const val ACTION_PREVIOUS_FROM_NOTIFICATION = "com.omymaxz.download.ACTION_PREVIOUS_FROM_NOTIFICATION"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.omymaxz.download.ACTION_STOP_FROM_NOTIFICATION"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_NEXT = "com.omymaxz.download.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.omymaxz.download.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        const val EXTRA_TITLE = "com.omymaxz.download.EXTRA_TITLE"
        const val EXTRA_IS_PLAYING = "com.omymaxz.download.EXTRA_IS_PLAYING"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val mediaButtonReceiver = ComponentName(applicationContext, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(applicationContext, "MediaForegroundServiceSession", mediaButtonReceiver, null).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { sendLocalBroadcast(ACTION_PLAY_FROM_NOTIFICATION) }
                override fun onPause() { sendLocalBroadcast(ACTION_PAUSE_FROM_NOTIFICATION) }
                override fun onSkipToNext() { sendLocalBroadcast(ACTION_NEXT_FROM_NOTIFICATION) }
                override fun onSkipToPrevious() { sendLocalBroadcast(ACTION_PREVIOUS_FROM_NOTIFICATION) }
                override fun onStop() { sendLocalBroadcast(ACTION_STOP_FROM_NOTIFICATION) }
            })
            isActive = true
        }
    }

    private fun sendLocalBroadcast(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Web Video"
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true

        startForeground(NOTIFICATION_ID, buildNotification(title, isPlaying, false, false))

        if (intent != null) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_STICKY
    }

    fun updateNotificationState(title: String, isPlaying: Boolean, duration: Long, position: Long, hasNext: Boolean, hasPrevious: Boolean) {
        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build())

        val playbackState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        
        var playbackStateActions = PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (hasPrevious) { playbackStateActions = playbackStateActions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS }
        if (hasNext) { playbackStateActions = playbackStateActions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(playbackState, position, 1.0f)
                .setActions(playbackStateActions)
                .build()
        )

        startForeground(NOTIFICATION_ID, buildNotification(title, isPlaying, hasNext, hasPrevious))
    }

    private fun buildNotification(title: String, isPlaying: Boolean, hasNext: Boolean, hasPrevious: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_notification)

        val compactViewActions = mutableListOf<Int>()

        if (hasPrevious) {
            builder.addAction(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            compactViewActions.add(compactViewActions.size)
        }

        builder.addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (isPlaying) "Pause" else "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
        compactViewActions.add(compactViewActions.size)

        if (hasNext) {
            builder.addAction(R.drawable.ic_skip_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            compactViewActions.add(compactViewActions.size)
        }

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(*compactViewActions.take(3).toIntArray())
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

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
    }
}