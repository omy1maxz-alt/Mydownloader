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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.lifecycleScope

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

        // (a) Local subtitles saved by HlsDownloadHelper — uses the SAME naming convention.
        val localFiles = HlsDownloadHelper.listLocalSubtitles(this, videoTitle!!)
        val localDs = androidx.media3.datasource.DefaultDataSource.Factory(this)
        for (f in localFiles) {
            val lang = f.nameWithoutExtension
                .substringAfter("_subtitle_", "und")
                .substringBeforeLast(".")   // drop extension
            buildSubtitleSource(Uri.fromFile(f).toString(), lang, lang.uppercase(), localDs)
                ?.let { subSources.add(it) }
        }

        // We create an empty dummy subtitle track so the 'None' button works properly
        val emptySubtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("data:text/vtt;charset=utf-8,WEBVTT"))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("none")
            .setLabel("None")
            .setSelectionFlags(0)
            .build()
        val emptySubtitleSource = SingleSampleMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this))
            .createMediaSource(emptySubtitleConfig, C.TIME_UNSET)

        val finalSource: MediaSource =
            if (subSources.isEmpty()) baseSource
            else MergingMediaSource(baseSource, emptySubtitleSource, *subSources.toTypedArray())

        player?.setMediaSource(finalSource)

        // Set English as the default preferred subtitle language
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setPreferredTextLanguage("en")
            ?.build()!!

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

        // Async background subtitle fetch for live streaming
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Process EXTRA_SUBTITLE_URL as a regular downloaded file so it merges properly without being hardcoded to "English"
                val intentSubUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
                if (!intentSubUrl.isNullOrBlank() && localFiles.isEmpty()) {
                    val bytes = HlsDownloadHelper.httpGetBytes(intentSubUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentCookie)
                    if (bytes != null) {
                        val contentString = String(bytes, Charsets.UTF_8)
                        var detectedLang = "und"
                        if (contentString.contains("thank", ignoreCase = true)) {
                            detectedLang = "en"
                        } else if (contentString.contains("gracias", ignoreCase = true)) {
                            detectedLang = "es"
                        } else {
                            detectedLang = "Detected"
                        }

                        val ext = if (intentSubUrl.contains(".srt", true)) ".srt" else ".vtt"
                        val outDir = HlsDownloadHelper.subtitlesDirFor(this@CustomPlayerActivity, videoTitle!!)
                        val outFile = File(outDir, "${videoTitle!!.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_$detectedLang$ext")

                        if (!outFile.exists()) {
                            java.io.FileOutputStream(outFile).use { it.write(bytes) }
                        }
                    }
                }

                // 2. Fetch HLS Subtitles
                if (isHls) {
                    HlsDownloadHelper.fetchAndSaveSubtitles(
                        this@CustomPlayerActivity,
                        videoUrl!!,
                        videoTitle!!,
                        HlsDownloadHelper.currentUserAgent,
                        HlsDownloadHelper.currentCookie
                    )
                }

                // 3. Hot swap if we have new files
                val newLocalFiles = HlsDownloadHelper.listLocalSubtitles(this@CustomPlayerActivity, videoTitle!!)
                if (newLocalFiles.isNotEmpty() && newLocalFiles.size > localFiles.size) {
                    withContext(Dispatchers.Main) {
                        hotSwapSubtitles(newLocalFiles, cacheFactory)
                    }
                }
            } catch (e: Exception) {
                Log.e("CustomPlayerActivity", "Background subtitle fetch failed", e)
            }
        }
    }

    private fun hotSwapSubtitles(localFiles: List<File>, cacheFactory: androidx.media3.datasource.DataSource.Factory) {
        val p = player ?: return
        val cacheFactoryToUse = cacheFactory

        // --- Build base video source (recreate to avoid buffering issue) ---
        val baseItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val baseSource: MediaSource = HlsMediaSource.Factory(cacheFactoryToUse).createMediaSource(baseItem)

        val subSources = mutableListOf<MediaSource>()

        // (a) Local subtitles
        val localDs = androidx.media3.datasource.DefaultDataSource.Factory(this)
        for (f in localFiles) {
            val lang = f.nameWithoutExtension
                .substringAfter("_subtitle_", "und")
                .substringBeforeLast(".")
            buildSubtitleSource(Uri.fromFile(f).toString(), lang, lang.uppercase(), localDs)
                ?.let { subSources.add(it) }
        }

        if (subSources.isNotEmpty()) {
            val emptySubtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("data:text/vtt;charset=utf-8,WEBVTT"))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("none")
                .setLabel("None")
                .setSelectionFlags(0)
                .build()
            val emptySubtitleSource = SingleSampleMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this))
                .createMediaSource(emptySubtitleConfig, C.TIME_UNSET)

            val finalSource = MergingMediaSource(baseSource, emptySubtitleSource, *subSources.toTypedArray())

            // Save state
            val currentPos = p.currentPosition
            val playWhenReady = p.playWhenReady

            // Hot swap
            p.setMediaSource(finalSource)
            p.prepare()
            p.seekTo(currentPos)
            p.playWhenReady = playWhenReady

            Toast.makeText(this, "Subtitles loaded", Toast.LENGTH_SHORT).show()
        }
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