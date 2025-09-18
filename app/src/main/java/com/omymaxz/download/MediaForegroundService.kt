package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.*
import java.net.URL

class MediaForegroundService : Service() {

    private val binder = LocalBinder()
    private var exoPlayer: ExoPlayer? = null
    private lateinit var notificationManager: NotificationManager
    private var mediaSession: MediaSessionCompat? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

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
        const val ACTION_NEXT = "com.omymaxz.download.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.omymaxz.download.ACTION_PREVIOUS"
        const val ACTION_START_PROACTIVE = "com.omymaxz.download.ACTION_START_PROACTIVE"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val componentName = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "MediaForegroundService", componentName, null).apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            exoPlayer?.play()
        }

        override fun onPause() {
            exoPlayer?.pause()
        }

        override fun onSkipToNext() {
            val intent = Intent(ACTION_NEXT)
            LocalBroadcastManager.getInstance(this@MediaForegroundService).sendBroadcast(intent)
        }

        override fun onSkipToPrevious() {
            val intent = Intent(ACTION_PREVIOUS)
            LocalBroadcastManager.getInstance(this@MediaForegroundService).sendBroadcast(intent)
        }

        override fun onStop() {
            stopPlayback()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_START_PROACTIVE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Background task is running"))
            }
            ACTION_PLAY -> {
                val mediaUrl = intent.getStringExtra("url")
                if (mediaUrl != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting playback..."))
                    mediaTitle = intent.getStringExtra("title") ?: "Web Video"
                    @Suppress("UNCHECKED_CAST")
                    val headers = intent.getSerializableExtra("headers") as? HashMap<String, String>
                    val startPosition = intent.getFloatExtra("position", 0f)
                    val posterUrl = intent.getStringExtra("posterUrl")
                    startPlayback(mediaUrl, headers, startPosition, posterUrl)
                } else {
                    exoPlayer?.play()
                }
            }
            ACTION_PAUSE -> exoPlayer?.pause()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(url: String, headers: HashMap<String, String>?, position: Float, posterUrl: String?) {
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
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        stopPlayback()
                    }
                    updatePlaybackState()
                    updateNotification()
                }
            })
        }
        updateMetadata(posterUrl)
        updatePlaybackState()
        updateNotification()
    }

    private fun stopPlayback() {
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
        stopForeground(true)
        stopSelf()
    }

    private fun updatePlaybackState() {
        val state = if (exoPlayer?.isPlaying == true) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, exoPlayer?.currentPosition ?: 0, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
    }

    private fun updateMetadata(posterUrl: String?) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)

        // Set initial metadata without album art
        mediaSession?.setMetadata(metadataBuilder.build())

        if (posterUrl != null) {
            serviceScope.launch {
                val bitmap = downloadImage(posterUrl)
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    mediaSession?.setMetadata(metadataBuilder.build())
                    updateNotification()
                }
            }
        }
    }

    private suspend fun downloadImage(url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                connection.connect()
                val input = connection.getInputStream()
                BitmapFactory.decodeStream(input)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }


    private fun updateNotification() {
        if (exoPlayer != null && mediaSession != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(customContentText: String? = null): Notification {
        val controller = mediaSession!!.controller
        val mediaMetadata = controller.metadata
        val description = mediaMetadata.description

        val builder = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle(description.title ?: mediaTitle)
            setContentText(customContentText ?: description.subtitle ?: if (exoPlayer?.isPlaying == true) "Playing in background" else "Paused")
            setSubText(description.description)
            setLargeIcon(description.iconBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            val intent = Intent(this@MediaForegroundService, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this@MediaForegroundService, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            setContentIntent(pendingIntent)
            setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, PlaybackStateCompat.ACTION_STOP))
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setSmallIcon(R.drawable.ic_notification)

            addAction(NotificationCompat.Action(R.drawable.ic_arrow_back, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))

            val isPlaying = exoPlayer?.isPlaying ?: false
            val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            val playPauseTitle = if (isPlaying) "Pause" else "Play"
            val playPauseAction = if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
            addAction(NotificationCompat.Action(playPauseIcon, playPauseTitle, MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, playPauseAction)))

            addAction(NotificationCompat.Action(R.drawable.ic_arrow_forward, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))
            addAction(NotificationCompat.Action(R.drawable.ic_close, "Stop", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, PlaybackStateCompat.ACTION_STOP)))

            setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this@MediaForegroundService, PlaybackStateCompat.ACTION_STOP))
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for background media playback"
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        stopPlayback()
    }
}