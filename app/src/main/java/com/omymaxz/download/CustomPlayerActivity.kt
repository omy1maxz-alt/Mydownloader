package com.omymaxz.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.appcompat.app.AlertDialog
import android.util.Log
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
        // Pass video URL as referer to prevent 403
        if (videoUrl != null) {
            HlsDownloadHelper.currentReferer = videoUrl
            // A common default user agent if the global one is null
            if (HlsDownloadHelper.currentUserAgent == null) {
                HlsDownloadHelper.currentUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
            }
        }
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

        var subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)

        // Check for local subtitle if none provided
        if (subtitleUrl.isNullOrEmpty() && videoTitle != null) {
            val sanitizedTitle = videoTitle!!.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val cacheDir = java.io.File(getExternalFilesDir(null), "subtitles")
            val vttFile = java.io.File(cacheDir, "${sanitizedTitle}_subtitle.vtt")
            val srtFile = java.io.File(cacheDir, "${sanitizedTitle}_subtitle.srt")
            if (vttFile.exists()) {
                subtitleUrl = vttFile.absolutePath
            } else if (srtFile.exists()) {
                subtitleUrl = srtFile.absolutePath
            }
        }

        if (!subtitleUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Subtitle found!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No subtitle URL found.", Toast.LENGTH_SHORT).show()
        }
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl))
            .setMimeType(if (videoUrl?.contains(".m3u8") == true) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)

        val baseMediaItem = mediaItemBuilder.build()
        val hlsMediaSource = HlsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(baseMediaItem)
        var mediaSourceToPlay: MediaSource = hlsMediaSource

        if (!subtitleUrl.isNullOrEmpty()) {
            val isHttp = subtitleUrl!!.startsWith("http")
            val subtitleFile = if (!isHttp) java.io.File(subtitleUrl) else null
            if (isHttp || (subtitleFile != null && subtitleFile.exists())) {
                try {
                    // Determine correct mime type based on extension
                    val mimeType = if (subtitleUrl!!.endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(
                        if (isHttp) Uri.parse(subtitleUrl) else Uri.fromFile(subtitleFile)
                    )
                        .setMimeType(mimeType)
                        .setLanguage("en")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED) // Force it to show by default
                        .build()

                    val localDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)
                    val subtitleSourceFactory = if (isHttp) cacheDataSourceFactory else localDataSourceFactory

                    val subtitleSource = SingleSampleMediaSource.Factory(subtitleSourceFactory)
                        .setTreatLoadErrorsAsEndOfStream(true)
                        .createMediaSource(subtitleConfig, androidx.media3.common.C.TIME_UNSET)

                    mediaSourceToPlay = MergingMediaSource(hlsMediaSource, subtitleSource)
                } catch (e: Exception) {
                    Log.e("CustomPlayerActivity", "Failed to create subtitle source", e)
                }
            } else {
                Log.e("CustomPlayerActivity", "Subtitle file does not exist: $subtitleUrl")
            }
        }

        player?.setMediaSource(mediaSourceToPlay)

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("CustomPlayerActivity", "Player error: ", error)
                val errorCodeName = error.errorCodeName
                AlertDialog.Builder(this@CustomPlayerActivity)
                    .setTitle("Playback Error")
                    .setMessage("Error ($errorCodeName):\n${error.message}")
                    .setPositiveButton("OK", null)
                    .show()
                Toast.makeText(this@CustomPlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })

        // Force track selection for text to automatically turn on subtitles if they exist
        player?.trackSelectionParameters = player!!.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("en")
            .build()

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
