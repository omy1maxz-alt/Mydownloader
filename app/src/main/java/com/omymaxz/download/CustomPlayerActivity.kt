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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
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
import androidx.media3.session.MediaSession

class CustomPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL     = "extra_video_url"
        const val EXTRA_VIDEO_TITLE   = "extra_video_title"
        const val EXTRA_SUBTITLE_URLS = "extra_subtitle_urls" // Now an ArrayList<String>
        const val EXTRA_USER_AGENT    = "extra_user_agent"
        const val EXTRA_REFERER       = "extra_referer"
        const val EXTRA_COOKIE        = "extra_cookie"
        var activePlayer: ExoPlayer? = null
    }

    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null

    private var mediaSession: MediaSession? = null

    private val cacheProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hasNotifiedCacheComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen timeout while playing video
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_custom_player)

        videoUrl   = intent.getStringExtra(EXTRA_VIDEO_URL)
        videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)
            ?: "Offline_Video_${System.currentTimeMillis()}"

        intent.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

        if (videoUrl == null) {
            Toast.makeText(this, "No video URL provided", Toast.LENGTH_SHORT).show(); finish(); return
        }

        findViewById<FloatingActionButton>(R.id.fab_save_offline).setOnClickListener { saveVideoOffline() }
        findViewById<FloatingActionButton>(R.id.fab_pip).setOnClickListener { enterPipMode() }
        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener { showTrackSelectionDialog() }
        hideSystemUI()

        // Start aggressive background caching using DownloadManager to fully parse HLS playlists
        val safeTitle = videoTitle ?: "Unknown_Video_${System.currentTimeMillis()}"
        val downloadRequest = DownloadRequest.Builder(
            "cache_${videoUrl.hashCode()}",
            Uri.parse(videoUrl)
        )
        .setData(safeTitle.toByteArray(Charsets.UTF_8))
        .build()

        DownloadService.sendAddDownload(
            this,
            HlsDownloadService::class.java,
            downloadRequest,
            false // don't start in foreground, just silently cache
        )
    }

    private fun showTrackSelectionDialog() {
        if (player == null) return
        val trackSelectionDialog = androidx.media3.ui.TrackSelectionDialogBuilder(
            this,
            "Video Quality",
            player!!,
            C.TRACK_TYPE_VIDEO
        ).build()
        trackSelectionDialog.show()
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
        savePlaybackPosition()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Keep playing in PiP
        } else {
            // Remove pause here to enable background playback when screen is off
            // player?.pause()
        }
    }
    override fun onStop()   {
        super.onStop()
        savePlaybackPosition()
        /* keep activePlayer alive for bg playback */
    }

    private fun savePlaybackPosition() {
        if (player != null && videoUrl != null) {
            val position = player!!.currentPosition
            if (position > 0) {
                val prefs = getSharedPreferences("VideoPlaybackPositions", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong(videoUrl!!, position).apply()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newUrl = intent?.getStringExtra(EXTRA_VIDEO_URL)
        val newTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE)

        intent?.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent?.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent?.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

        // If the new intent is playing a completely different video, re-initialize the player
        if (newUrl != null && newUrl != videoUrl) {
            videoUrl = newUrl
            videoTitle = newTitle ?: "Offline_Video_${System.currentTimeMillis()}"
            setIntent(intent)
            player?.release()
            player = null
            activePlayer = null
            initializePlayer()
            return
        }

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
                            val result = SubtitleUtils.extractSnippet(contentString)
                            if (!result.snippet.isNullOrBlank()) {
                                detectedLang = result.snippet.replace(Regex("[^a-zA-Z0-9 -]"), "").take(15)
                                if (!result.language.isNullOrBlank()) {
                                    detectedLang = "[${result.language}] $detectedLang"
                                }
                            } else if (contentString.contains("thank", ignoreCase = true)) {
                                detectedLang = "en"
                            } else if (contentString.contains("gracias", ignoreCase = true)) {
                                detectedLang = "es"
                            } else {
                                // Fallback to filename segment
                                val fileSegment = android.net.Uri.parse(subUrl).lastPathSegment?.substringBeforeLast("?") ?: "Sub"
                                detectedLang = "Track_$fileSegment"
                            }

                            // Append a unique hash to the filename to prevent overwriting when snippets are identical,
                            // but format it predictably so hotSwapSubtitles can strip it for the UI label.
                            val hash = Math.abs(subUrl.hashCode())

                            val ext = if (subUrl.contains(".srt", true)) ".srt" else ".vtt"
                            val outFile = File(outDir, "${safeTitle.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_${detectedLang}_HASH_${hash}$ext")

                            if (!outFile.exists()) {
                                java.io.FileOutputStream(outFile).use {
                                    // Fix ExoPlayer parse failure for VTT without header
                                    if (ext == ".vtt" && !contentString.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
                                        it.write("WEBVTT\n\n".toByteArray(Charsets.UTF_8))
                                    }
                                    it.write(bytes)
                                }
                            }
                            downloadedAny = true
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
        // HlsDownloadHelper.currentReferer and currentUserAgent might already be set from the Intent.
        // If not, we fall back to defaults or the video URL.
        HlsDownloadHelper.currentReferer    = HlsDownloadHelper.currentReferer ?: videoUrl
        HlsDownloadHelper.currentUserAgent  = HlsDownloadHelper.currentUserAgent
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        val cacheFactory = HlsDownloadHelper.getCacheDataSourceFactory(this)

        // Aggressive caching setup: ignoring standard 32MB size thresholds
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,             // minBufferMs
                12_000_000,         // maxBufferMs (approx 3.3 hours)
                2_500,              // bufferForPlaybackMs
                5_000               // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(androidx.media3.common.C.LENGTH_UNSET) // Uncapped RAM usage
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheFactory))
            .setLoadControl(loadControl)
            .build()

        if (mediaSession == null && player != null) {
            mediaSession = MediaSession.Builder(this, player!!).build()
        }

        attachPlayerView()

        // --- Build base video source ---
        val isHls = videoUrl!!.contains("m3u8", ignoreCase = true)

        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val localFiles = HlsDownloadHelper.listLocalSubtitles(this, videoTitle!!)

        // (a) Local subtitles
        for (f in localFiles) {
            val lang = f.nameWithoutExtension
                .substringAfter("_subtitle_", "und")
                .substringBeforeLast(".")
            val mime = if (f.extension.equals("srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(lang)
                .setLabel(lang.uppercase())
                .setSelectionFlags(1)
                .build()
            subtitleConfigs.add(cfg)
        }

        val emptySubtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("data:text/vtt;charset=utf-8,WEBVTT"))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage("none")
            .setLabel("None")
            .setSelectionFlags(0)
            .build()
        subtitleConfigs.add(emptySubtitleConfig)

        val newBaseItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        // Let DefaultMediaSourceFactory naturally handle HLS merging
        player?.setMediaItem(newBaseItem)

        // Set English as the default preferred subtitle language and explicitly enable text rendering
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setPreferredTextLanguage("en")
            ?.setIgnoredTextSelectionFlags(0)
            ?.setSelectUndeterminedTextLanguage(true)
            // Enable styling features like color and size via the UI by using default text rendering capabilities
            // The default subtitle view already responds to standard VTT/SRT styles and Android system caption settings
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

        val prefs = getSharedPreferences("VideoPlaybackPositions", android.content.Context.MODE_PRIVATE)
        val savedPosition = prefs.getLong(videoUrl!!, 0L)
        if (savedPosition > 0L) {
            player?.seekTo(savedPosition)
            Toast.makeText(this, "Resuming playback", Toast.LENGTH_SHORT).show()
        }

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
                            val result = SubtitleUtils.extractSnippet(contentString)
                            if (!result.snippet.isNullOrBlank()) {
                                detectedLang = result.snippet.replace(Regex("[^a-zA-Z0-9 -]"), "").take(15)
                                if (!result.language.isNullOrBlank()) {
                                    detectedLang = "[${result.language}] $detectedLang"
                                }
                            } else if (contentString.contains("thank", ignoreCase = true)) {
                                detectedLang = "en"
                            } else if (contentString.contains("gracias", ignoreCase = true)) {
                                detectedLang = "es"
                            } else {
                                // Fallback to filename segment
                                val fileSegment = android.net.Uri.parse(subUrl).lastPathSegment?.substringBeforeLast("?") ?: "Sub"
                                detectedLang = "Track_$fileSegment"
                            }

                            val hash = Math.abs(subUrl.hashCode())

                            val ext = if (subUrl.contains(".srt", true)) ".srt" else ".vtt"
                            val outFile = File(outDir, "${videoTitle!!.replace(Regex("[^a-zA-Z0-9.-]"), "_")}_subtitle_${detectedLang}_HASH_${hash}$ext")

                            if (!outFile.exists()) {
                                java.io.FileOutputStream(outFile).use {
                                    // Fix ExoPlayer parse failure for VTT without header
                                    if (ext == ".vtt" && !contentString.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
                                        it.write("WEBVTT\n\n".toByteArray(Charsets.UTF_8))
                                    }
                                    it.write(bytes)
                                }
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

        val isHls = videoUrl?.contains("m3u8", ignoreCase = true) == true
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

        // (a) Local subtitles
        for (f in localFiles) {
            val lang = f.nameWithoutExtension
                .substringAfter("_subtitle_", "und")
                .substringBeforeLast("_HASH_")
                .substringBeforeLast(".")
            val mime = if (f.extension.equals("srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(lang)
                .setLabel(lang.uppercase())
                .setSelectionFlags(1)
                .build()
            subtitleConfigs.add(cfg)
        }

        if (subtitleConfigs.isNotEmpty()) {
            val emptySubtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("data:text/vtt;charset=utf-8,WEBVTT"))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("none")
                .setLabel("None")
                .setSelectionFlags(0)
                .build()
            subtitleConfigs.add(emptySubtitleConfig)

            val newBaseItem = MediaItem.Builder()
                .setUri(Uri.parse(videoUrl!!))
                .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
                .setSubtitleConfigurations(subtitleConfigs)
                .build()

            // Save state
            val currentPos = p.currentPosition
            val playWhenReady = p.playWhenReady

            // Hot swap using setMediaItem so DefaultMediaSourceFactory naturally handles HLS merging
            p.setMediaItem(newBaseItem)
            p.prepare()
            p.seekTo(currentPos)
            p.playWhenReady = playWhenReady

            Toast.makeText(this, "Subtitles loaded: ${subtitleConfigs.size - 1} tracks", Toast.LENGTH_LONG).show()
        }
    }

    private val cacheProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val duration = p.duration
            val buffered = p.bufferedPosition
            val bufferedPercentage = p.bufferedPercentage

            if (duration > 0 && buffered > 0) {
                // Consider it fully cached if buffered position reaches the end AND the buffered percentage is 100.
                // Checking bufferedPercentage == 100 prevents false positives when the user skips/fast-forwards to the end,
                // which leaves missing chunks in the middle of the cache.
                if (!hasNotifiedCacheComplete && buffered >= duration - 1500 && bufferedPercentage >= 99) {
                    hasNotifiedCacheComplete = true
                    val fab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_save_offline)
                    // Tint FAB green to indicate it's safe to save
                    fab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
                    Toast.makeText(this@CustomPlayerActivity, "Video fully cached! Safe to Save Offline.", Toast.LENGTH_LONG).show()
                }
            }
            cacheProgressHandler.postDelayed(this, 1000)
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

        hasNotifiedCacheComplete = false
        cacheProgressHandler.removeCallbacks(cacheProgressRunnable)
        cacheProgressHandler.postDelayed(cacheProgressRunnable, 2000)
    }

    private fun releasePlayer() { /* intentional no-op: activePlayer survives onStop */ }

    override fun onDestroy() {
        super.onDestroy()
        cacheProgressHandler.removeCallbacks(cacheProgressRunnable)
        mediaSession?.release()
        mediaSession = null

        // Stop aggressive background caching when leaving the player by pausing it, not removing (which deletes cache)
        try {
            val downloadId = "cache_${videoUrl.hashCode()}"
            DownloadService.sendSetStopReason(
                this,
                HlsDownloadService::class.java,
                downloadId,
                1, // STOP_REASON_NONE + 1 = paused
                false
            )
        } catch (e: Exception) {
            Log.e("CustomPlayerActivity", "Failed to cancel background cache", e)
        }

        if (isFinishing) { activePlayer?.release(); activePlayer = null }
    }

    private fun saveVideoOffline() {
        val input = android.widget.EditText(this)
        input.setText(videoTitle)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename Video")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    videoTitle = newTitle
                }

                // Determine the exact variant URL currently playing to ensure the exported MP4
                // matches the chosen quality and has a video track (preventing blank videos).
                var exportUrl = videoUrl
                if (player != null && exportUrl?.contains(".m3u8", ignoreCase = true) == true) {
                    val tracks = player!!.currentTracks
                    for (group in tracks.groups) {
                        if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    val format = group.getTrackFormat(i)
                                    // ExoPlayer often embeds the original variant URL or ID in the format for HLS
                                    if (format.id != null && format.id!!.startsWith("http")) {
                                        exportUrl = format.id
                                    }
                                    break
                                }
                            }
                        }
                    }
                }

                val intent = Intent(this, HlsExportService::class.java).apply {
                    putExtra(HlsExportService.EXTRA_URL, exportUrl)
                    putExtra(HlsExportService.EXTRA_TITLE, videoTitle)
                    putExtra(HlsExportService.EXTRA_USER_AGENT, HlsDownloadHelper.currentUserAgent)
                    putExtra(HlsExportService.EXTRA_REFERER, HlsDownloadHelper.currentReferer)
                    putExtra(HlsExportService.EXTRA_COOKIE, HlsDownloadHelper.currentCookie)
                }
                startService(intent)
                Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}