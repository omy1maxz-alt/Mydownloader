package com.omymaxz.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class MediaForegroundService : Service() {
    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "MediaPlayerChannel"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null

    private var isPlaying = false
    private var currentTitle: String = "Media Player"

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        initializeMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("url")
        when (intent?.action) {
            ACTION_PLAY -> {
                currentTitle = intent.getStringExtra("title") ?: "Media Player"
                if (url != null) {
                    play(url)
                } else {
                    resume()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MediaPlayerSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stop()
            })

            isActive = true
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun play(url: String) {
        if (!requestAudioFocus()) return

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(url)
            setOnPreparedListener {
                isPlaying = true
                start()
                updatePlaybackState()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            setOnCompletionListener {
                stop()
            }
            setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            prepareAsync()
        }
    }

    private fun pause() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            updatePlaybackState()
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
            stopForeground(false)
        }
    }

    private fun resume() {
        if (!isPlaying) {
            mediaPlayer?.start()
            isPlaying = true
            updatePlaybackState()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        updatePlaybackState()
        stopSelf()
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Web Media")
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun buildNotification(): Notification {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseActionTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText("Playing web media")
            .setSmallIcon(R.drawable.ic_home)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(playPauseIcon, playPauseActionTitle, createPendingIntent(playPauseAction))
            .addAction(android.R.drawable.ic_media_next, "Stop", createPendingIntent(ACTION_STOP))
            .setOngoing(isPlaying)
            .setContentIntent(createMainActivityIntent())
            .build()
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaForegroundService::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, 0, intent, flags)
    }

    private fun createMainActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    fun updateMediaInfo(title: String) {
        currentTitle = title
        updatePlaybackState()
        if (isPlaying) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}