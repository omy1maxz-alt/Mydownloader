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
    // Add these variables to store state internally
    private var currentTitle: String = "Web Video"
    private var currentPlaying: Boolean = false
    private var currentPosition: Long = 0L
    private var currentDuration: Long = 0L
    private var hasNext: Boolean = false
    private var hasPrevious: Boolean = false

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

        // Define actions for MediaSession callbacks
        const val ACTION_PLAY_PAUSE = "com.omymaxz.download.ACTION_PLAY_PAUSE"
        const val ACTION_STOP_SERVICE = "com.omymaxz.download.ACTION_STOP_SERVICE" // For stopping the service
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Create and activate the MediaSession
        mediaSession = MediaSessionCompat(this, "MediaForegroundServiceSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    android.util.Log.d("MediaForegroundService", "MediaSession Callback: onPlay")
                    sendMediaControlBroadcast(ACTION_PLAY)
                }

                override fun onPause() {
                    android.util.Log.d("MediaForegroundService", "MediaSession Callback: onPause")
                    sendMediaControlBroadcast(ACTION_PAUSE)
                }

                // Handle the stop action from the notification swipe/stop button
                override fun onStop() {
                    android.util.Log.d("MediaForegroundService", "MediaSession Callback: onStop")
                    sendMediaControlBroadcast(ACTION_STOP_SERVICE) // Use specific action for service stop
                }

                override fun onSkipToNext() {
                    android.util.Log.d("MediaForegroundService", "MediaSession Callback: onSkipToNext")
                    sendMediaControlBroadcast(ACTION_NEXT)
                }

                override fun onSkipToPrevious() {
                    android.util.Log.d("MediaForegroundService", "MediaSession Callback: onSkipToPrevious")
                    sendMediaControlBroadcast(ACTION_PREVIOUS)
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            // IMPORTANT: Set initial PlaybackState *before* activating
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP
                ) // Initial actions, will be updated later
                .build())

            // IMPORTANT: Activate the session here
            setActive(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        MediaButtonReceiver.handleIntent(mediaSession, intent)

        if (intent.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        updateStateFromIntent(intent)
        updateNotification()

        return START_STICKY
    }

    private fun updateStateFromIntent(intent: Intent) {
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
        currentPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, currentPlaying)
        currentPosition = intent.getLongExtra(EXTRA_CURRENT_POSITION, currentPosition)
        currentDuration = intent.getLongExtra(EXTRA_DURATION, currentDuration)
        hasNext = intent.getBooleanExtra(EXTRA_HAS_NEXT, hasNext)
        hasPrevious = intent.getBooleanExtra(EXTRA_HAS_PREVIOUS, hasPrevious)

        // IMPORTANT: Update the MediaSession's PlaybackState and Metadata immediately after updating state
        updatePlaybackState()
        updateMediaMetadata()
    }

    private fun updatePlaybackState() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        (if (hasPrevious) PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS else 0) or
                        (if (hasNext) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0)
            )

        val state = if (currentPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        stateBuilder.setState(state, currentPosition, 1.0f)

        android.util.Log.d("MediaForegroundService", "Updating PlaybackState: state=$state, pos=$currentPosition, duration=$currentDuration, hasNext=$hasNext, hasPrevious=$hasPrevious")
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaMetadata() {
        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)
            .build())
    }

    private fun updateNotification() {
        val notification = buildNotification(currentTitle, currentPlaying, hasNext, hasPrevious)
        startForeground(NOTIFICATION_ID, notification) // Must be called within 5 seconds of startForegroundService
    }

    private fun sendMediaControlBroadcast(action: String) {
        val intent = Intent(MainActivity.ACTION_MEDIA_CONTROL).apply {
            putExtra(MainActivity.EXTRA_COMMAND, action)
            `package` = this@MediaForegroundService.packageName // Ensure broadcast goes to correct package
        }
        android.util.Log.d("MediaForegroundService", "Sending media control broadcast: $action")
        sendBroadcast(intent)
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
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)) // Or use ACTION_PAUSE if preferred for swipe
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_notification) // Replace with your icon

        // Add previous action if available
        if (hasPrevious) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous, // Replace with your icon
                    "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                )
            )
        }

        // Add play/pause action
        builder.addAction(
            NotificationCompat.Action(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, // Replace with your icons
                if (isPlaying) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
            )
        )

        // Add next action if available
        if (hasNext) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_next, // Replace with your icon
                    "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                )
            )
        }

        // Set the style using the MediaSession token
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2) // Adjust indices based on how many actions you add (0-indexed)
        )

        return builder.build()
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Channel for background media playback"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.isActive = false // Deactivate the session
        mediaSession?.release() // Release the session
    }
}