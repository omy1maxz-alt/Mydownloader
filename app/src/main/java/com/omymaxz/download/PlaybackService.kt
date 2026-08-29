package com.omymaxz.download

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

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

        mediaSession = MediaSession.Builder(this, player!!)
            .setId("PlaybackServiceSession")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "com.omymaxz.download.START_BACKGROUND_AUDIO") {
            val url = intent.getStringExtra("video_url")
            val title = intent.getStringExtra("video_title") ?: "Background Audio"
            val position = intent.getLongExtra("current_position", 0L)

            if (url != null) {
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
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
