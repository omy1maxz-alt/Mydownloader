package com.omymaxz.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.PlayerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class CustomPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL     = "extra_video_url"
        const val EXTRA_VIDEO_TITLE   = "extra_video_title"
        const val EXTRA_SUBTITLE_URL  = "extra_subtitle_url"
        var activePlayer: ExoPlayer? = null
    }

    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_player)

        videoUrl   = intent.getStringExtra(EXTRA_VIDEO_URL)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)
            ?: "Offline_Video_${System.currentTimeMillis()}"

        if (videoUrl == null) {
            Toast.makeText(this, "No video URL provided", Toast.LENGTH_SHORT).show(); finish(); return
        }

        findViewById<FloatingActionButton>(R.id.fab_save_offline).setOnClickListener { saveVideoOffline() }
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onStart()  { super.onStart();  initializePlayer() }
    override fun onResume() { super.onResume(); if (player == null) initializePlayer() }
    override fun onPause()  { super.onPause();  player?.pause() }
    override fun onStop()   { super.onStop();   /* keep activePlayer alive for bg playback */ }

    private fun initializePlayer() {
        if (activePlayer != null) {
            player = activePlayer
            attachPlayerView()
            return
        }

        // Seed global headers so any lazy HTTP request the cache makes uses them.
        HlsDownloadHelper.currentReferer    = videoUrl
        HlsDownloadHelper.currentUserAgent  = HlsDownloadHelper.currentUserAgent
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheFactory))
            .build()

        attachPlayerView()

        // --- Build base video source ---
        val isHls = videoUrl!!.contains("m3u8", ignoreCase = true)
        val baseItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .build()

        val baseSource: MediaSource = if (isHls)
            HlsMediaSource.Factory(cacheFactory).createMediaSource(baseItem)
        else
            ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(baseItem)

        // --- Collect subtitle sources ---
        val subSources = mutableListOf<MediaSource>()

        // (a) Intent-provided subtitle URL (HTTP or local path)
        intent.getStringExtra(EXTRA_SUBTITLE_URL)?.takeIf { it.isNotBlank() }?.let { url ->
            buildSubtitleSource(url, "en", "English (Default)", cacheFactory)?.let { subSources.add(it) }
        }

        // (b) Local subtitles saved by HlsDownloadHelper — uses the SAME naming convention.
        val localFiles = HlsDownloadHelper.listLocalSubtitles(this, videoTitle!!)
        val localDs = androidx.media3.datasource.DefaultDataSource.Factory(this)
        for (f in localFiles) {
            val lang = f.nameWithoutExtension
                .substringAfter("_subtitle_", "und")
                .substringBeforeLast(".")   // drop extension
            buildSubtitleSource(Uri.fromFile(f).toString(), lang, lang.uppercase(), localDs)
                ?.let { subSources.add(it) }
        }

        val finalSource: MediaSource =
            if (subSources.isEmpty()) baseSource
            else MergingMediaSource(baseSource, *subSources.toTypedArray())

        player?.setMediaSource(finalSource)
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("CustomPlayerActivity", "Player error", error)
                AlertDialog.Builder(this@CustomPlayerActivity)
                    .setTitle("Playback Error")
                    .setMessage("Error (${error.errorCodeName}):\n${error.message}")
                    .setPositiveButton("OK", null).show()
            }
        })

        activePlayer = player
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun attachPlayerView() {
        val pv = findViewById<PlayerView>(R.id.player_view)
        pv.player = player
        val fab = findViewById<FloatingActionButton>(R.id.fab_save_offline)
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> fab.visibility = v })
    }

    /**
     * Build a SingleSampleMediaSource for a subtitle.
     * - NO SELECTION_FLAG_FORCED so users can toggle in ExoPlayer UI.
     * - C.TIME_UNSET duration prevents MergingMediaSource from trimming it.
     */
    private fun buildSubtitleSource(
        uriOrPath: String, language: String, label: String,
        dsFactory: androidx.media3.datasource.DataSource.Factory
    ): MediaSource? {
        return try {
            val isHttp = uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://")
            val uri = if (isHttp) Uri.parse(uriOrPath) else Uri.fromFile(File(uriOrPath))
            val mime = when {
                uriOrPath.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
                else                             -> MimeTypes.TEXT_VTT
            }
            val cfg = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLanguage(language)
                .setLabel(label)
                .setSelectionFlags(0)                // <-- NOT forced
                .build()

            SingleSampleMediaSource.Factory(dsFactory)
                .setTreatLoadErrorsAsEndOfStream(true)
                .createMediaSource(cfg, C.TIME_UNSET)
        } catch (t: Throwable) {
            Log.e("CustomPlayerActivity", "Subtitle source failed for $uriOrPath", t); null
        }
    }

    private fun releasePlayer() { /* intentional no-op: activePlayer survives onStop */ }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) { activePlayer?.release(); activePlayer = null }
    }

    private fun saveVideoOffline() {
        val intent = Intent(this, HlsExportService::class.java).apply {
            putExtra(HlsExportService.EXTRA_URL, videoUrl)
            putExtra(HlsExportService.EXTRA_TITLE, videoTitle)
        }
        startService(intent)
        Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show()
    }
}