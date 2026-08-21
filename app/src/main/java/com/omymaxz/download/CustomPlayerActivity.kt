package com.omymaxz.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CustomPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_SUBTITLE_URL = "extra_subtitle_url"

        var activePlayer: ExoPlayer? = null
    }

    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null
    private var subtitlesAlreadyAttached = false

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
        if (activePlayer != null) {
            player = activePlayer
            val playerView = findViewById<PlayerView>(R.id.player_view)
            playerView.player = player

            val fabSave = findViewById<FloatingActionButton>(R.id.fab_save_offline)
            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                fabSave.visibility = visibility
            })
            return
        }

        if (videoUrl != null) {
            HlsDownloadHelper.currentReferer = videoUrl
            if (HlsDownloadHelper.currentUserAgent == null) {
                HlsDownloadHelper.currentUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
            }
        }
        val cacheDataSourceFactory = HlsDownloadHelper.getCacheDataSourceFactory(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory))
            .build()

        val playerView = findViewById<PlayerView>(R.id.player_view)
        playerView.player = player

        val fabSave = findViewById<FloatingActionButton>(R.id.fab_save_offline)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            fabSave.visibility = visibility
        })

        val intentSubtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
        val isHls = videoUrl?.contains("m3u8") == true
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MP4)
        val baseMediaItem = mediaItemBuilder.build()

        val baseMediaSource: MediaSource = if (isHls) {
            HlsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(baseMediaItem)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(baseMediaItem)
        }

        // FIX (Issue 1): start playback immediately with whatever local subtitle files
        // already exist (fast path, e.g. replaying something downloaded earlier)...
        val initialSubtitleFiles = findLocalSubtitleFiles()
        val initialSource = buildMediaSourceWithSubtitles(baseMediaSource, cacheDataSourceFactory, intentSubtitleUrl, initialSubtitleFiles)
        subtitlesAlreadyAttached = initialSubtitleFiles.isNotEmpty() || !intentSubtitleUrl.isNullOrEmpty()

        player?.setMediaSource(initialSource)
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

        activePlayer = player
        player?.prepare()
        player?.playWhenReady = true

        // FIX (Issue 1, root cause): previously nothing ever fetched subtitles for plain
        // streaming/caching playback — only the explicit offline-download flow did. This
        // fetches them here too (bounded by a short timeout so it never blocks/stalls
        // playback) and, once available, hot-swaps them into the running player without
        // restarting from zero.
        if (!subtitlesAlreadyAttached && !videoUrl.isNullOrEmpty()) {
            lifecycleScope.launch {
                val fetched = withTimeoutOrNull(12_000L) {
                    HlsDownloadHelper.fetchAndCacheSubtitles(
                        this@CustomPlayerActivity,
                        videoUrl!!,
                        videoTitle!!,
                        HlsDownloadHelper.currentUserAgent,
                        HlsDownloadHelper.currentCookie
                    )
                }
                if (!fetched.isNullOrEmpty() && player != null) {
                    val currentPositionMs = player!!.currentPosition
                    val wasPlaying = player!!.playWhenReady
                    val updatedSource = buildMediaSourceWithSubtitles(baseMediaSource, cacheDataSourceFactory, intentSubtitleUrl, fetched)
                    player?.setMediaSource(updatedSource, currentPositionMs)
                    player?.prepare()
                    player?.playWhenReady = wasPlaying
                    Toast.makeText(this@CustomPlayerActivity, "Subtitles loaded", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun findLocalSubtitleFiles(): List<java.io.File> {
        val subtitleFiles = mutableListOf<java.io.File>()
        val title = videoTitle ?: return subtitleFiles
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val cacheDir = java.io.File(getExternalFilesDir(null), "subtitles")
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val files = cacheDir.listFiles { file ->
                file.name.startsWith("${sanitizedTitle}_subtitle") &&
                (file.name.endsWith(".vtt") || file.name.endsWith(".srt"))
            }
            if (files != null) subtitleFiles.addAll(files)
        }
        return subtitleFiles
    }

    private fun buildMediaSourceWithSubtitles(
        baseMediaSource: MediaSource,
        cacheDataSourceFactory: androidx.media3.datasource.cache.CacheDataSource.Factory,
        intentSubtitleUrl: String?,
        localSubtitleFiles: List<java.io.File>
    ): MediaSource {
        val subtitleSources = mutableListOf<MediaSource>()
        val localDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)

        if (!intentSubtitleUrl.isNullOrEmpty()) {
            val isHttp = intentSubtitleUrl.startsWith("http")
            val subtitleFile = if (!isHttp) java.io.File(intentSubtitleUrl) else null
            if (isHttp || (subtitleFile != null && subtitleFile.exists())) {
                try {
                    val mimeType = if (intentSubtitleUrl.endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(
                        if (isHttp) Uri.parse(intentSubtitleUrl) else Uri.fromFile(subtitleFile)
                    )
                        .setMimeType(mimeType)
                        .setLanguage("en")
                        .setLabel("English (Default)")
                        // No SELECTION_FLAG_FORCED — user toggles it via the player UI.
                        .build()
                    val subtitleSourceFactory = if (isHttp) cacheDataSourceFactory else localDataSourceFactory
                    subtitleSources.add(
                        SingleSampleMediaSource.Factory(subtitleSourceFactory)
                            .setTreatLoadErrorsAsEndOfStream(true)
                            .createMediaSource(subtitleConfig, androidx.media3.common.C.TIME_UNSET)
                    )
                } catch (e: Exception) {
                    Log.e("CustomPlayerActivity", "Failed to create intent subtitle source", e)
                }
            }
        }

        for (subFile in localSubtitleFiles) {
            try {
                var lang = "en"
                val nameParts = subFile.nameWithoutExtension.split("_subtitle_")
                if (nameParts.size > 1) lang = nameParts[1]

                val mimeType = if (subFile.name.endsWith(".srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                    .setMimeType(mimeType)
                    .setLanguage(lang)
                    .setLabel(lang.uppercase())
                    .build()
                subtitleSources.add(
                    SingleSampleMediaSource.Factory(localDataSourceFactory)
                        .setTreatLoadErrorsAsEndOfStream(true)
                        .createMediaSource(subtitleConfig, androidx.media3.common.C.TIME_UNSET)
                )
            } catch (e: Exception) {
                Log.e("CustomPlayerActivity", "Failed to create local subtitle source for ${subFile.name}", e)
            }
        }

        return if (subtitleSources.isNotEmpty()) {
            MergingMediaSource(baseMediaSource, *subtitleSources.toTypedArray())
        } else {
            baseMediaSource
        }
    }

    private fun releasePlayer() {
        // Not released onStop so it can continue playing in the background.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            activePlayer?.release()
            activePlayer = null
        }
    }

    // FIX (Issue 2): lets the user choose "just save what I've watched" (fast, cached-only,
    // no network) vs "complete and save the full video" (fetches the missing chunks first)
    // instead of the export always silently behaving like a full download.
    private fun saveVideoOffline() {
        AlertDialog.Builder(this)
            .setTitle("Save Video")
            .setMessage("Save just the portion you've watched, or download and save the full video?")
            .setPositiveButton("Watched portion") { _, _ -> startExport(cachedOnly = true) }
            .setNegativeButton("Full video") { _, _ -> startExport(cachedOnly = false) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun startExport(cachedOnly: Boolean) {
        val intent = Intent(this, HlsExportService::class.java).apply {
            putExtra(HlsExportService.EXTRA_URL, videoUrl)
            putExtra(HlsExportService.EXTRA_TITLE, videoTitle)
            putExtra(HlsExportService.EXTRA_CACHED_ONLY, cachedOnly)
        }
        startService(intent)
        Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show()
    }
}