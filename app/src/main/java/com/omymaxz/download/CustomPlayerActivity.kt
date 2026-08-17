package com.omymaxz.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class CustomPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_SUBTITLE_URL = "extra_subtitle_url"
    }

    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_player)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Offline_Video_${System.currentTimeMillis()}"

        if (videoUrl == null) {
            Toast.makeText(this, "No video URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fabSave = findViewById<FloatingActionButton>(R.id.fab_save_offline)
        fabSave.setOnClickListener {
            saveVideoOffline()
        }

        // Hide UI for immersive experience
        hideSystemUI()
    }

    private fun hideSystemUI() {
        // Simplified for brevity, use standard immersive flags if needed
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        val cacheDataSourceFactory = HlsDownloadHelper.getStreamCacheDataSourceFactory(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory))
            .build()

        val playerView = findViewById<PlayerView>(R.id.player_view)
        playerView.player = player

        val fabSave = findViewById<FloatingActionButton>(R.id.fab_save_offline)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            fabSave.visibility = visibility
        })

        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl))
            .setMimeType(if (videoUrl?.contains(".m3u8") == true) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)

        if (!subtitleUrl.isNullOrEmpty()) {
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(MimeTypes.TEXT_VTT) // Assume VTT by default, most common for web
                .setLanguage("en") // Defaulting to english if unknown
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }

        val mediaItem = mediaItemBuilder.build()

        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun saveVideoOffline() {
        // Create an intent to start HlsExportService directly with URL instead of DownloadManager ID
        val intent = Intent(this, HlsExportService::class.java).apply {
            putExtra(HlsExportService.EXTRA_URL, videoUrl)
            putExtra(HlsExportService.EXTRA_TITLE, videoTitle)
        }
        startService(intent)
        Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show()
    }
}
