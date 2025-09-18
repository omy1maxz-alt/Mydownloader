package com.omymaxz.download

import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MediaForegroundService : MediaSessionService() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    inner class LocalBinder : Binder() {
        fun getService(): MediaForegroundService = this@MediaForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private inner class MediaCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val updatedMediaItems = mutableListOf<MediaItem>()
            for (mediaItem in mediaItems) {
                val uri = mediaItem.requestMetadata.mediaUri
                if (uri != null) {
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                    val headers = mediaItem.requestMetadata.extras?.getSerializable("headers") as? HashMap<String, String>
                    headers?.let { dataSourceFactory.setDefaultRequestProperties(it) }

                    val newMediaItem = mediaItem.buildUpon()
                        .setUri(uri)
                        .build()

                    updatedMediaItems.add(newMediaItem)
                }
            }
            return Futures.immediateFuture(updatedMediaItems)
        }
    }
}