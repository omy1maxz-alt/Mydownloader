package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

class MediaForegroundService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener,
    MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var mediaTitle = "Web Video"
    private var mediaUrl: String? = null
    private var isPlaying = false
    private var isPreparing = false
    private var headers: Map<String, String>? = null
    private var startPosition: Float = 0f

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MediaPlaybackChannel"
        const val ACTION_PLAY = "com.omymaxz.download.ACTION_PLAY"
        const val ACTION_PAUSE = "com.omymaxz.download.ACTION_PAUSE"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        // THIS ACTION IS ADDED TO FIX THE CRASH
        const val ACTION_START_PROACTIVE = "com.omymaxz.download.ACTION_START_PROACTIVE"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel() // Ensure channel exists
        val action = intent?.action
        when (action) {
            ACTION_START_PROACTIVE -> {
                // This is the fix. Just show the notification and wait.
                startForeground(NOTIFICATION_ID, buildNotification("Background task is running"))
            }
            ACTION_PLAY -> {
                if (mediaPlayer != null && !isPlaying && !isPreparing) {
                    resumePlayback()
                    return START_STICKY
                }

                if (!isPreparing && !isPlaying) {
                    mediaTitle = intent.getStringExtra("title") ?: "Web Video"
                    mediaUrl = intent.getStringExtra("url")
                    @Suppress("UNCHECKED_CAST")
                    headers = intent.getSerializableExtra("headers") as? Map<String, String>
                    startPosition = intent.getFloatExtra("position", 0f)
                    if (mediaUrl == null) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startPlayback()
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
        }
        return START_STICKY
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun startPlayback() {
        if (!requestAudioFocus()) {
            android.util.Log.w("MediaForegroundService", "Failed to get audio focus")
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            try {
                if (headers?.isNotEmpty() == true) {
                    setDataSource(applicationContext, Uri.parse(mediaUrl), headers)
                } else {
                    setDataSource(mediaUrl)
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaForegroundService", "Error setting data source: ${e.message}", e)
                try {
                    setDataSource(mediaUrl)
                } catch (e2: Exception) {
                    android.util.Log.e("MediaForegroundService", "Failed to set data source completely", e2)
                    stopPlayback()
                    return
                }
            }
            setOnPreparedListener(this@MediaForegroundService)
            setOnErrorListener(this@MediaForegroundService)
            setOnCompletionListener(this@MediaForegroundService)
            prepareAsync()
        }
        isPreparing = true
        wakeLock?.acquire(10*60*1000L)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun pausePlayback() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            updateNotification()
            stopForeground(false)
        }
    }

    private fun resumePlayback() {
        if (!isPlaying && mediaPlayer != null && requestAudioFocus()) {
            mediaPlayer?.start()
            isPlaying = true
            wakeLock?.acquire(10*60*1000L)
            updateNotification()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isPreparing = false
        abandonAudioFocus()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(true)
        stopSelf()
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
             @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        isPreparing = false
        isPlaying = true
        if (startPosition > 0) {
            mp?.seekTo((startPosition * 1000).toInt())
        }
        mp?.start()
        updateNotification()
        android.util.Log.d("MediaForegroundService", "Prepared and started at position: $startPosition")
    }

    override fun onCompletion(mp: MediaPlayer?) {
        stopPlayback()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        val errorMsg = when (what) {
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Unknown error"
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Server died"
            else -> "Error code: $what"
        }
        android.util.Log.e("MediaForegroundService", "MediaPlayer Error: $errorMsg, Extra: $extra")
        stopPlayback()
        return true
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
                if(!isPlaying) resumePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if(isPlaying) pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.1f, 0.1f)
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(customContentText: String? = null): Notification {
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

        val contentText = customContentText ?: when {
            isPreparing -> "Buffering..."
            isPlaying -> "Playing in background"
            else -> "Paused"
        }

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
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for background media playback"
            }
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}