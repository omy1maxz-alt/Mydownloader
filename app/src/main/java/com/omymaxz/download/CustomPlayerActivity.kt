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
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.view.View
import android.widget.LinearLayout

class CustomPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL     = "extra_video_url"
        const val EXTRA_VIDEO_TITLE   = "extra_video_title"
        const val EXTRA_SUBTITLE_URLS = "extra_subtitle_urls" // Now an ArrayList<String>
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
        findViewById<FloatingActionButton>(R.id.fab_pip).setOnClickListener { enterPipMode() }
        hideSystemUI()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "Picture-in-Picture not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val pv = findViewById<PlayerView>(R.id.player_view)
        val fabContainer = findViewById<LinearLayout>(R.id.fab_container)

        if (isInPictureInPictureMode) {
            pv.useController = false
            fabContainer.visibility = View.GONE
        } else {
            pv.useController = true
            fabContainer.visibility = View.VISIBLE
            hideSystemUI()
        }
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
    override fun onPause()  {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Keep playing in PiP
        } else {
            player?.pause()
        }
    }
    override fun onStop()   { super.onStop();   /* keep activePlayer alive for bg playback */ }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Keep original intent to retain EXTRA_VIDEO_URL if new intent doesn't have it
        if (intent?.hasExtra(EXTRA_VIDEO_URL) == true) {
            setIntent(intent)
        }
        val newSubUrls = intent?.getStringArrayListExtra(EXTRA_SUBTITLE_URLS)
        if (!newSubUrls.isNullOrEmpty() && player != null) {
            // We just got new subtitle URLs pushed while playing. Fetch them.
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    var downloadedAny = false
                    val safeTitle = videoTitle ?: "Offline_Video_${System.currentTimeMillis()}"
                    val outDir = HlsDownloadHelper.subtitlesDirFor(this@CustomPlayerActivity, safeTitle)

                    for (subUrl in newSubUrls) {
                        val bytes = HlsDownloadHelper.httpGetBytes(subUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentCookie)
                        if (bytes != null) {
                            val contentString = String(bytes, Charsets.UTF_8)
                            var detectedLang = "und"
                            if (contentString.contains("thank", ignoreCase = true)) {
                                detectedLang = "en"
                            } else if (contentString.contains("gracias", ignoreCase = true)) {
                                detectedLang = "es"
                            } else {
                                // Assign an arbitrary unique identifier if multiple "Detected" are found
                                detectedLang = "Detected_${Math.abs(subUrl.hashCode())}"
                            }

                            val ext = if (subUrl.contains(".srt", true)) ".srt" else ".vtt"
                            val outFile = File(outDir, "${safeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_$detectedLang$ext")

                            if (!outFile.exists()) {
                                java.io.FileOutputStream(outFile).use { it.write(bytes) }
                                downloadedAny = true
                            }
                        }
                    }

                    if (downloadedAny) {
                        val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(this@CustomPlayerActivity)
                        val newLocalFiles = HlsDownloadHelper.listLocalSubtitles(this@CustomPlayerActivity, safeTitle)
                        withContext(Dispatchers.Main) {
                            hotSwapSubtitles(newLocalFiles, cacheFactory)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CustomPlayerActivity", "Failed to add subtitle from onNewIntent", e)
                }
            }
        }
    }

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

        // Set English as the default preferred subtitle language and explicitly enable text rendering
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setPreferredTextLanguage("en")
            ?.setIgnoredTextSelectionFlags(0)
            ?.setSelectUndeterminedTextLanguage(true)
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
                // 1. Process EXTRA_SUBTITLE_URLS array as regular downloaded files so they merge properly without being hardcoded to "English"
                val intentSubUrls = intent.getStringArrayListExtra(EXTRA_SUBTITLE_URLS)
                if (!intentSubUrls.isNullOrEmpty()) {
                    val outDir = HlsDownloadHelper.subtitlesDirFor(this@CustomPlayerActivity, videoTitle!!)
                    for (subUrl in intentSubUrls) {
                        val bytes = HlsDownloadHelper.httpGetBytes(subUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentCookie)
                        if (bytes != null) {
                            val contentString = String(bytes, Charsets.UTF_8)
                            var detectedLang = "und"
                            if (contentString.contains("thank", ignoreCase = true)) {
                                detectedLang = "en"
                            } else if (contentString.contains("gracias", ignoreCase = true)) {
                                detectedLang = "es"
                            } else {
                                detectedLang = "Detected_${Math.abs(subUrl.hashCode())}"
                            }

                            val ext = if (subUrl.contains(".srt", true)) ".srt" else ".vtt"
                            val outFile = File(outDir, "${videoTitle!!.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_$detectedLang$ext")

                            if (!outFile.exists()) {
                                java.io.FileOutputStream(outFile).use { it.write(bytes) }
                            }
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

        // Ensure videoUrl is not null to prevent crashes when hot-swapping subtitles from manual Add to Player intents
        if (videoUrl == null) {
            Log.w("CustomPlayerActivity", "videoUrl is null during hotSwapSubtitles. Attempting to extract from current player item.")
            videoUrl = p.currentMediaItem?.localConfiguration?.uri?.toString()
            if (videoUrl == null) {
                Log.e("CustomPlayerActivity", "Could not recover videoUrl. Cannot hot swap subtitles.")
                return
            }
        }

        // --- Build base video source (recreate to avoid buffering issue) ---
        val isHls = videoUrl?.contains("m3u8", ignoreCase = true) == true
        val baseItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .build()

        val baseSource: MediaSource = if (isHls)
            HlsMediaSource.Factory(cacheFactoryToUse).createMediaSource(baseItem)
        else
            ProgressiveMediaSource.Factory(cacheFactoryToUse).createMediaSource(baseItem)

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
        val fabContainer = findViewById<LinearLayout>(R.id.fab_container)
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
                fabContainer.visibility = v
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                fabContainer.visibility = v
            }
        })
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