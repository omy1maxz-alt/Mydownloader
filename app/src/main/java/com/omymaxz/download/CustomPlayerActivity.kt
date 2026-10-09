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
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.lifecycleScope
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
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
        const val EXTRA_MIME_TYPE     = "extra_mime_type"
        var activePlayer: ExoPlayer? = null
    }

    private var player: ExoPlayer? = null
    private var videoUrl: String? = null
    private var videoTitle: String? = null

    private val cacheProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hasNotifiedCacheComplete = false
    private var mediaSession: MediaSession? = null

    private val ACTION_BACKGROUND_PLAY = "com.omymaxz.download.ACTION_BACKGROUND_PLAY"

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BACKGROUND_PLAY -> {
                    val serviceIntent = Intent(this@CustomPlayerActivity, PlaybackService::class.java).apply {
                        action = "com.omymaxz.download.START_BACKGROUND_AUDIO"
                        putExtra("video_url", videoUrl)
                        putExtra("video_title", videoTitle)
                        putExtra("current_position", player?.currentPosition ?: 0L)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    finish()
                }
                "ACTION_PLAY" -> {
                    player?.play()
                    updatePictureInPictureActions()
                }
                "ACTION_PAUSE" -> {
                    player?.pause()
                    updatePictureInPictureActions()
                }
            }
        }
    }

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

                val exoSubtitleBtn = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_subtitle)
        exoSubtitleBtn?.setOnClickListener { showSubtitleSelectionDialog() }

        val exoSettingsBtn = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
        exoSettingsBtn?.visibility = android.view.View.VISIBLE
        // Custom override removed to allow native ExoPlayer settings menu
        var currentResizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        findViewById<android.widget.ImageButton>(R.id.fab_resize)?.setOnClickListener {
            currentResizeMode = when (currentResizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> { Toast.makeText(this, "Resize Mode: Stretch", Toast.LENGTH_SHORT).show(); androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL }
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> { Toast.makeText(this, "Resize Mode: Zoom (Crop)", Toast.LENGTH_SHORT).show(); androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> { Toast.makeText(this, "Resize Mode: Fixed Width", Toast.LENGTH_SHORT).show(); androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH }
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> { Toast.makeText(this, "Resize Mode: Fixed Height", Toast.LENGTH_SHORT).show(); androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT }
                else -> { Toast.makeText(this, "Resize Mode: Fit", Toast.LENGTH_SHORT).show(); androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
            }
            findViewById<androidx.media3.ui.PlayerView>(R.id.player_view).resizeMode = currentResizeMode
        }

        findViewById<android.widget.ImageButton>(R.id.fab_bubble)?.setOnClickListener { startFloatingBubble() }
        findViewById<android.widget.ImageButton>(R.id.fab_pip)?.setOnClickListener { enterPipMode() }
        findViewById<android.widget.ImageButton>(R.id.fab_save)?.setOnClickListener { saveVideoOffline() }
        hideSystemUI()

        // Removed aggressive background caching using DownloadManager on startup.
        // It was causing double-quota usage by automatically downloading the 1080p master playlist
        // while the user might be actively streaming 480p via the CacheDataSource.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = IntentFilter().apply {
                addAction(ACTION_BACKGROUND_PLAY)
                addAction("ACTION_PLAY")
                addAction("ACTION_PAUSE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pipReceiver, filter)
            }
        }
    }



    private fun showSubtitleSelectionDialog() {
        if (player == null) return
        val trackSelectionDialog = androidx.media3.ui.TrackSelectionDialogBuilder(
            this,
            "Select Subtitle",
            player!!,
            C.TRACK_TYPE_TEXT
        ).build()

        trackSelectionDialog.show()

        val themeColor = getSafeGlossyThemeColor(this)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(themeColor)
            cornerRadius = 32f
        }
        trackSelectionDialog.window?.setBackgroundDrawable(drawable)
    }


    private fun getSafeGlossyThemeColor(context: android.content.Context): Int {
        val prefs = context.getSharedPreferences("Settings", android.content.Context.MODE_PRIVATE)
        val defaultColor = android.graphics.Color.parseColor("#A0000000")

        return try {
            val colorStr = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
            android.graphics.Color.parseColor(colorStr)
        } catch (e: java.lang.ClassCastException) {
            try {
                // If it crashes because it's an Int, fallback to reading as Int
                prefs.getInt("glossy_theme_color", defaultColor)
            } catch (e2: Exception) {
                defaultColor
            }
        } catch (e: Exception) {
            defaultColor
        }
    }

    private fun showVideoQualityDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        var videoGroupCount = 0
        var videoTrackCount = 0
        val qualityList = mutableListOf<Pair<String, androidx.media3.common.TrackSelectionOverride?>>()

        // Add Auto option (clears override)
        qualityList.add(Pair("Auto", null))

        // We only want to select from video tracks
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                videoGroupCount++
                for (i in 0 until group.length) {
                    videoTrackCount++
                    val format = group.getTrackFormat(i)
                    val label = if (format.height > 0) {
                        "${format.height}p"
                    } else if (format.bitrate > 0) {
                        "${format.bitrate / 1000} kbps"
                    } else {
                        "Quality ${videoTrackCount}"
                    }

                    val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)
                    qualityList.add(Pair(label, override))
                }
            }
        }

        // Deduplicate labels
        val uniqueQualityList = qualityList.distinctBy { it.first }

        android.util.Log.d("VIDEO_QUALITY_TRACE", "videoGroups=$videoGroupCount, videoTracks=$videoTrackCount, uniqueQualities=${uniqueQualityList.size}")

        if (videoTrackCount <= 1) {
            Toast.makeText(this, "No alternative video qualities available", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = createThemedDialogBuilder(this)
        builder.setTitle("Video Quality")

        val items = uniqueQualityList.map { it.first }.toTypedArray()
        builder.setItems(items) { dialog, which ->
            val selection = uniqueQualityList[which]
            val override = selection.second

            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .apply {
                    if (override == null) {
                        clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    } else {
                        setOverrideForType(override)
                    }
                }
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                .build()

            // RESET CACHE STATE WHEN QUALITY CHANGES
            hasNotifiedCacheComplete = false
            cacheVerificationInProgress = false
            updateCacheCompleteUi(false)

            Toast.makeText(this, "Quality set to ${selection.first}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        applyGlossyThemeToDialog(dialog, this)
    }

    private fun applyGlossyThemeToDialog(dialog: android.app.Dialog, context: android.content.Context) {
        val themeColor = getSafeGlossyThemeColor(context)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(themeColor)
            cornerRadius = 32f
        }
        dialog.window?.setBackgroundDrawable(drawable)
    }

    private fun createThemedDialogBuilder(context: android.content.Context): androidx.appcompat.app.AlertDialog.Builder {
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        return builder
    }


    private fun startFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission for the floating bubble", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(EXTRA_VIDEO_URL, videoUrl)
            putExtra(EXTRA_VIDEO_TITLE, videoTitle)
            putExtra("current_position", player?.currentPosition ?: 0L)
        }
        startService(serviceIntent)

        moveTaskToBack(true)
    }

    private fun updatePictureInPictureActions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = player?.isPlaying == true

            // Background Play (Headset) action
            val bgIntent = Intent(ACTION_BACKGROUND_PLAY).setPackage(packageName)
            val bgPendingIntent = PendingIntent.getBroadcast(
                this, 0, bgIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val bgIcon = Icon.createWithResource(this, R.drawable.ic_headset)
            val bgAction = RemoteAction(bgIcon, "Listen in Background", "Listen to audio in background", bgPendingIntent)

            // Play/Pause action
            val playPauseActionId = if (isPlaying) "ACTION_PAUSE" else "ACTION_PLAY"
            val playPauseIntent = Intent(playPauseActionId).setPackage(packageName)
            val playPausePendingIntent = PendingIntent.getBroadcast(
                this, if (isPlaying) 1 else 2, playPauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val playPauseIcon = Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            val playPauseAction = RemoteAction(
                playPauseIcon,
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause playback" else "Play playback",
                playPausePendingIntent
            )

            val params = PictureInPictureParams.Builder()
                .setActions(listOf(playPauseAction, bgAction))
                .build()

            setPictureInPictureParams(params)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
            updatePictureInPictureActions()
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
            updatePictureInPictureActions()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val pv = findViewById<PlayerView>(R.id.player_view)

        if (isInPictureInPictureMode) {
            pv.useController = false
        } else {
            pv.useController = true
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onStart()  { super.onStart();  initializePlayer() }
    override fun onResume() { super.onResume(); if (player == null) initializePlayer() }
    override fun onPause()  {
        super.onPause()
        savePlaybackPosition()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Keep playing in PiP
        } else {
            // Restore pause to prevent rogue background audio without a proper Foreground Service Notification
            player?.pause()
        }
    }
    override fun onStop()   {
        super.onStop()
        savePlaybackPosition()
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
        // CRITICAL: Update the activity's intent so initializePlayer() reads the new extras!
        if (intent != null) {
            setIntent(intent)
        }

        // Stop the playback service if we are returning to the UI
        stopService(android.content.Intent(this, PlaybackService::class.java))

        val newUrl = intent?.getStringExtra(EXTRA_VIDEO_URL)
        val newTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE)

        intent?.getStringExtra(EXTRA_USER_AGENT)?.let { HlsDownloadHelper.currentUserAgent = it }
        intent?.getStringExtra(EXTRA_REFERER)?.let { HlsDownloadHelper.currentReferer = it }
        intent?.getStringExtra(EXTRA_COOKIE)?.let { HlsDownloadHelper.currentCookie = it }

        // Always re-initialize the player if a DIFFERENT video URL is requested.
        // If the URL is the same (e.g. adding a subtitle to the currently playing video), we do not reset the player.
        if (newUrl != null && newUrl != videoUrl) {
            android.util.Log.d("PLAYER_STATE_RESET", "[PLAYER_STATE_RESET]\npreviousUrl=$videoUrl\nnewUrl=$newUrl\nplayerReleased=true\nmediaItemsCleared=true\nnewMediaItemPrepared=true")
            videoUrl = newUrl
            videoTitle = newTitle ?: "Offline_Video_${System.currentTimeMillis()}"
            player?.release()
            player = null
            activePlayer = null
            initializePlayer()
            return
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
                        val bytes = if (subUrl.startsWith("file://")) {
                            try {
                                java.io.File(java.net.URI.create(subUrl)).readBytes()
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            HlsDownloadHelper.httpGetBytes(subUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentReferer, HlsDownloadHelper.currentCookie)
                        }
                        if (bytes != null) {
                            val contentString = String(bytes, Charsets.UTF_8)

                            // Check if a language query param was encoded in the URL during detection
                            val urlQueryLangMatch = Regex("[?&]lang=([a-zA-Z0-9-]+)").find(subUrl)
                            val explicitLang = urlQueryLangMatch?.groupValues?.get(1)

                            var detectedLang = "und"
                            val result = SubtitleUtils.extractSnippet(contentString)

                            val targetLang = explicitLang ?: result.language ?: "und"

                            if (!result.snippet.isNullOrBlank()) {
                                detectedLang = result.snippet.replace(Regex("[^a-zA-Z0-9 -]"), "").take(15)
                                if (targetLang != "und") {
                                    detectedLang = "[$targetLang] $detectedLang"
                                }
                            } else {
                                // Fallback to filename segment if snippet fails
                                val fileSegment = android.net.Uri.parse(subUrl).lastPathSegment?.substringBeforeLast("?") ?: "Sub"
                                detectedLang = "[$targetLang] Track_$fileSegment"
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
        val tracerMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        android.util.Log.d("PLAYER_TRACE", "[PLAYER_TRACE]\nvideoUrl=$videoUrl\nEXTRA_MIME_TYPE=$tracerMimeType\ndetectedFromUrl=null\nselectedMimeType=null")
        if (activePlayer != null) {
            // Do not blindly reuse the active player if the requested URL has changed!
            // This prevents a single-variant player from being preserved when a multi-quality master is requested later.
            val currentActiveUrl = activePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (currentActiveUrl == videoUrl) {
                android.util.Log.d("KISKH_PLAYER_IDENTITY", "[KISKH_PLAYER_IDENTITY]\nrequestedUrl=$videoUrl\nactivePlayerCurrentUrl=$currentActiveUrl\nsameSource=true\nreusePlayer=true")
                player = activePlayer
                attachPlayerView()
                return
            } else {
                android.util.Log.d("KISKH_PLAYER_IDENTITY", "[KISKH_PLAYER_IDENTITY]\nrequestedUrl=$videoUrl\nactivePlayerCurrentUrl=$currentActiveUrl\nsameSource=false\nreusePlayer=false")
                activePlayer?.release()
                activePlayer = null
            }
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

        // We MUST use cacheFactory for all media (including direct progressive like MP4)
        // to actually populate the cache spans. If we don't, cache_count will be 0 when saving.
        val isDirectProgressiveUrl = videoUrl?.contains(".mp4", ignoreCase = true) == true ||
                                     videoUrl?.contains(".webm", ignoreCase = true) == true ||
                                     videoUrl?.contains(".mkv", ignoreCase = true) == true

        val isExplicitProgressiveMime = tracerMimeType == androidx.media3.common.MimeTypes.VIDEO_MP4 ||
                                        tracerMimeType == androidx.media3.common.MimeTypes.VIDEO_WEBM ||
                                        tracerMimeType == androidx.media3.common.MimeTypes.VIDEO_MATROSKA

        val isHlsOrDash = tracerMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8 ||
                          tracerMimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD ||
                          videoUrl?.contains(".m3u8", ignoreCase = true) == true ||
                          videoUrl?.contains(".mpd", ignoreCase = true) == true ||
                          videoUrl?.contains("manifest", ignoreCase = true) == true

        val dataSourceFactory = cacheFactory

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        trackSelector.setParameters(trackSelector.buildUponParameters()) // Set some default if we want, or just leave it

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(CustomRetryPolicy()))
            .setLoadControl(loadControl)
            .build()

        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, player!!)
            .setId("CustomPlayerSession_${System.currentTimeMillis()}")
            .build()

        activePlayer = player

        attachPlayerView()

        // --- Build base video source ---
        val isHls = videoUrl!!.contains("m3u8", ignoreCase = true)

        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val localFiles = HlsDownloadHelper.listLocalSubtitles(this, videoTitle!!)

        // (a) Local subtitles
        for (f in localFiles) {
            val rawLang = f.nameWithoutExtension.substringAfter("_subtitle_", "und").substringBeforeLast("_HASH_").substringBeforeLast(".")
            // Fix: the file is named Video_Title_subtitle_[eng] Label_HASH_123.srt.
            // `substringAfter("_subtitle_")` returns `[eng] Label`
            // If the label is not English (e.g. Indonesian), and we strip brackets, we might get just "Indonesian"
            val displayLang = rawLang.replace(Regex("\\[.*?\\]"), "").trim().ifBlank { "Undetermined" }

            val cleanSystemLang = if (rawLang.contains("[id]", true) || rawLang.contains("indones", true)) "id"
                                  else if (rawLang.contains("[en]", true) || rawLang.contains("english", true)) "en"
                                  else if (rawLang.contains("[es]", true) || rawLang.contains("spanish", true)) "es"
                                  else if (rawLang.contains("[fil]", true) || rawLang.contains("filipino", true) || rawLang.contains("tagalog", true) || rawLang.contains("[tl]", true)) "tl"
                                  else if (rawLang.contains("[km]", true) || rawLang.contains("khmer", true)) "km"
                                  else if (rawLang.contains("[ms]", true) || rawLang.contains("malay", true)) "ms"
                                  else if (rawLang.contains("[pt]", true) || rawLang.contains("portuguese", true)) "pt"
                                  else if (rawLang.contains("[ar]", true) || rawLang.contains("arabic", true)) "ar"
                                  else if (rawLang.contains("[hi]", true) || rawLang.contains("hindi", true)) "hi"
                                  else if (rawLang.contains("[de]", true) || rawLang.contains("german", true)) "de"
                                  else if (rawLang.contains("[fr]", true) || rawLang.contains("french", true)) "fr"
                                  else "und"


            val mime = if (f.extension.equals("srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(cleanSystemLang)
                .setLabel(displayLang.replace("_", " ").uppercase())
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED)
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


        val intentMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val actualMimeType = when {
            intentMimeType != null -> intentMimeType
            isHls -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            videoUrl?.contains(".mpd") == true || videoUrl?.contains("manifest/dash") == true -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            else -> androidx.media3.common.MimeTypes.APPLICATION_MP4
        }

        android.util.Log.d("PLAYER_DEBUG", "[PLAYER_DEBUG]\nvideoUrl=$videoUrl\nmimeType=$actualMimeType\nsourceType=$intentMimeType")

        // Reset cache verification state on new media
        hasNotifiedCacheComplete = false
        cacheVerificationInProgress = false
        updateCacheCompleteUi(false)
        val newBaseItem = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl!!))
            .setMimeType(actualMimeType)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        android.util.Log.d("PLAYER_TRACE", "[PLAYER_TRACE]\nlaunchSource=initializePlayer\nvideoUrl=$videoUrl\nmimeType=$actualMimeType\nhasUserAgent=${intent.hasExtra(EXTRA_USER_AGENT)}\nhasReferer=${intent.hasExtra(EXTRA_REFERER)}\nhasCookie=${intent.hasExtra(EXTRA_COOKIE)}\nhasSubtitles=${intent.hasExtra(EXTRA_SUBTITLE_URLS)}\nMediaItem URI=${newBaseItem.localConfiguration?.uri}")

        // In ExoPlayer 1.2.0+, DefaultMediaSourceFactory has strict Extractor requirements for ProgressiveMediaSource.
        // It requires the MP4 format to be readable by FragmentedMp4Extractor or Mp4Extractor.
        // The error explicitly says "None of the available extractors (FragmentedMp4Extractor, Mp4Extractor... could read the stream".
        // This implies one of two things:
        // 1. The stream returned a 403/Forbidden HTML/JSON response instead of video bytes, causing the parsers to choke.
        // 2. The MP4 is a raw stream (like an FLV or a strange progressive chunk) that the bundled extractors don't like.
        // Let's explicitly log the HTTP response status for this URI via our helper.

        // Let DefaultMediaSourceFactory naturally handle HLS merging
        val splitAudioUrl = intent.getStringExtra(YouTubeDownloadService.EXTRA_AUDIO_URL)
        if (!splitAudioUrl.isNullOrEmpty()) {
            val audioItem = MediaItem.Builder()
                .setUri(Uri.parse(splitAudioUrl))
                .setMimeType(intent.getStringExtra("EXTRA_AUDIO_MIME_TYPE") ?: MimeTypes.AUDIO_MP4)
                .build()
            val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(cacheFactory)
            val videoSource = factory.createMediaSource(newBaseItem)
            val audioSource = factory.createMediaSource(audioItem)
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            player?.setMediaSource(mergedSource)
        } else {
            player?.setMediaItem(newBaseItem)
        }

        // Set English as the default preferred subtitle language and explicitly enable text rendering
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            // ?.setPreferredTextLanguage("en") // Removed forcing English as the default language
            ?.setIgnoredTextSelectionFlags(0)
            ?.setSelectUndeterminedTextLanguage(true)
            // Enable styling features like color and size via the UI by using default text rendering capabilities
            // The default subtitle view already responds to standard VTT/SRT styles and Android system caption settings
            ?.build()!!

        player?.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                var videoTracks = 0
                var audioTracks = 0
                val trackGroups = tracks.groups.size
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) videoTracks += group.length
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) audioTracks += group.length
                }
                android.util.Log.d("KISKH_HLS_TRACE", "[KISKH_HLS_TRACE]\nfinalUrl=$videoUrl\ntrackGroups=$trackGroups\nvideoTracks=$videoTracks\naudioTracks=$audioTracks")
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                var causeChain = ""
                var currentCause: Throwable? = error.cause
                while (currentCause != null) {
                    causeChain += "\nCause: ${currentCause.javaClass.simpleName} - ${currentCause.message}"
                    currentCause = currentCause.cause
                }

                android.util.Log.e("PLAYER_ERROR_TRACE", "[PLAYER_ERROR_TRACE] Code: ${error.errorCode} Name: ${error.errorCodeName}\nException: ${error.javaClass.name} - ${error.message}\n$causeChain")

                AlertDialog.Builder(this@CustomPlayerActivity)
                    .setTitle("Playback Error")
                    .setMessage("Error (${error.errorCodeName}):\n${error.message}\n$causeChain")
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
                        val bytes = if (subUrl.startsWith("file://")) {
                            try {
                                java.io.File(java.net.URI.create(subUrl)).readBytes()
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            HlsDownloadHelper.httpGetBytes(subUrl, HlsDownloadHelper.currentUserAgent, HlsDownloadHelper.currentReferer, HlsDownloadHelper.currentCookie)
                        }
                        if (bytes != null) {
                            val contentString = String(bytes, Charsets.UTF_8)

                            // Check if a language query param was encoded in the URL during detection
                            val urlQueryLangMatch = Regex("[?&]lang=([a-zA-Z0-9-]+)").find(subUrl)
                            val explicitLang = urlQueryLangMatch?.groupValues?.get(1)

                            var detectedLang = "und"
                            val result = SubtitleUtils.extractSnippet(contentString)

                            val targetLang = explicitLang ?: result.language ?: "und"

                            if (!result.snippet.isNullOrBlank()) {
                                detectedLang = result.snippet.replace(Regex("[^a-zA-Z0-9 -]"), "").take(15)
                                if (targetLang != "und") {
                                    detectedLang = "[$targetLang] $detectedLang"
                                }
                            } else {
                                // Fallback to filename segment if snippet fails
                                val fileSegment = android.net.Uri.parse(subUrl).lastPathSegment?.substringBeforeLast("?") ?: "Sub"
                                detectedLang = "[$targetLang] Track_$fileSegment"
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
            val rawLang = f.nameWithoutExtension.substringAfter("_subtitle_", "und").substringBeforeLast("_HASH_").substringBeforeLast(".")
            // Fix: the file is named Video_Title_subtitle_[eng] Label_HASH_123.srt.
            // `substringAfter("_subtitle_")` returns `[eng] Label`
            // If the label is not English (e.g. Indonesian), and we strip brackets, we might get just "Indonesian"
            val displayLang = rawLang.replace(Regex("\\[.*?\\]"), "").trim().ifBlank { "Undetermined" }

            val cleanSystemLang = if (rawLang.contains("[id]", true) || rawLang.contains("indones", true)) "id"
                                  else if (rawLang.contains("[en]", true) || rawLang.contains("english", true)) "en"
                                  else if (rawLang.contains("[es]", true) || rawLang.contains("spanish", true)) "es"
                                  else if (rawLang.contains("[fil]", true) || rawLang.contains("filipino", true) || rawLang.contains("tagalog", true) || rawLang.contains("[tl]", true)) "tl"
                                  else if (rawLang.contains("[km]", true) || rawLang.contains("khmer", true)) "km"
                                  else if (rawLang.contains("[ms]", true) || rawLang.contains("malay", true)) "ms"
                                  else if (rawLang.contains("[pt]", true) || rawLang.contains("portuguese", true)) "pt"
                                  else if (rawLang.contains("[ar]", true) || rawLang.contains("arabic", true)) "ar"
                                  else if (rawLang.contains("[hi]", true) || rawLang.contains("hindi", true)) "hi"
                                  else if (rawLang.contains("[de]", true) || rawLang.contains("german", true)) "de"
                                  else if (rawLang.contains("[fr]", true) || rawLang.contains("french", true)) "fr"
                                  else "und"


            val mime = if (f.extension.equals("srt", true)) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
            val cfg = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(cleanSystemLang)
                .setLabel(displayLang.replace("_", " ").uppercase())
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_FORCED)
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

            val intentMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
            val actualMimeType = when {
                intentMimeType != null -> intentMimeType
                isHls -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                videoUrl?.contains(".mpd") == true || videoUrl?.contains("manifest/dash") == true -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                else -> androidx.media3.common.MimeTypes.APPLICATION_MP4
            }
            android.util.Log.d("PLAYER_DEBUG", "[PLAYER_DEBUG]\nvideoUrl=$videoUrl\nmimeType=$actualMimeType\nsourceType=$intentMimeType")

            // Reset cache verification state on new media
        hasNotifiedCacheComplete = false
        cacheVerificationInProgress = false
        updateCacheCompleteUi(false)
        val newBaseItem = MediaItem.Builder()
                .setUri(Uri.parse(videoUrl!!))
                .setMimeType(actualMimeType)
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

    private var cacheVerificationInProgress = false

    private fun updateCacheCompleteUi(isComplete: Boolean) {
        val fab = findViewById<android.widget.ImageButton>(R.id.fab_save)
        if (isComplete) {
            fab?.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            fab?.clearColorFilter()
        }
    }

    private val cacheProgressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p == null || hasNotifiedCacheComplete || cacheVerificationInProgress) {
                cacheProgressHandler.postDelayed(this, 1000)
                return
            }
            val currentMediaItem = p.currentMediaItem
            if (currentMediaItem != null) {
                val duration = p.duration
                val buffered = p.bufferedPosition

                // 2. If duration is unknown/unavailable, DO NOT declare cache complete.
                if (duration <= 0 || duration == androidx.media3.common.C.TIME_UNSET) {
                    cacheProgressHandler.postDelayed(this, 1000)
                    return
                }

                // 3. If bufferedPosition has NOT reached the end, DO NOT call expensive cache verification
                if (buffered < duration - 1000) {
                    cacheProgressHandler.postDelayed(this, 1000)
                    return
                }

                // 4. Buffer reached end. Verify physical cache.
                cacheVerificationInProgress = true
                val uri = currentMediaItem.localConfiguration?.uri
                val mimeType = currentMediaItem.localConfiguration?.mimeType ?: androidx.media3.common.MimeTypes.APPLICATION_M3U8

                if (uri != null) {
                    val streamKeys = mutableListOf<androidx.media3.common.StreamKey>()
                    val tracks = p.currentTracks

                    var selectedVideoTracksLog = ""
                    var selectedAudioTracksLog = ""

                    tracks.groups.forEachIndexed { groupIndex, group ->
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                streamKeys.add(androidx.media3.common.StreamKey(groupIndex, i))
                                val format = group.getTrackFormat(i)
                                if (format.sampleMimeType?.startsWith("video/") == true) {
                                    selectedVideoTracksLog += "[Video ${format.width}x${format.height} ${format.bitrate}bps]"
                                } else if (format.sampleMimeType?.startsWith("audio/") == true) {
                                    selectedAudioTracksLog += "[Audio ${format.sampleMimeType} ${format.bitrate}bps]"
                                }
                            }
                        }
                    }

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        var cacheCheckMethod = ""
                        var reason = ""

                        val isFullyCached = if (mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD) {
                            cacheCheckMethod = "ExoPlayer_Buffer_MPD"
                            val cached = false // DASH physical span checking not yet supported, skipping false positive
                            reason = "DASH physical span checking not yet supported, skipping false positive."
                            cached
                        } else {
                            cacheCheckMethod = "HlsDownloadHelper.checkIsFullyCached"
                            val splitAudioUrl = intent.getStringExtra("com.omymaxz.download.extra.AUDIO_URL") ?: intent.getStringExtra(YouTubeDownloadService.EXTRA_AUDIO_URL)
                            val cached = HlsDownloadHelper.checkIsFullyCached(this@CustomPlayerActivity, uri, mimeType, streamKeys, splitAudioUrl)
                            reason = if (cached) "Logical cache spans complete" else "Missing logical spans or playlist"
                            cached
                        }

                        android.util.Log.i("CACHE_DIAGNOSTIC", "CACHE VERIFICATION | Method: $cacheCheckMethod | Reason: $reason | URI: $uri | MIME: $mimeType | Duration: $duration | Buf: $buffered | Video: $selectedVideoTracksLog | Audio: $selectedAudioTracksLog | StreamKeys: $streamKeys | Result: $isFullyCached")

                        if (isFullyCached && !hasNotifiedCacheComplete) {
                            hasNotifiedCacheComplete = true
                            updateCacheCompleteUi(true)
                            Toast.makeText(this@CustomPlayerActivity, "Video fully cached! Safe to Save Offline.", Toast.LENGTH_LONG).show()
                        }
                        cacheVerificationInProgress = false
                    }
                } else {
                    cacheVerificationInProgress = false
                }
            }
            cacheProgressHandler.postDelayed(this, 1000)
        }
    }
    private fun attachPlayerView() {
        val pv = findViewById<PlayerView>(R.id.player_view)
        pv.player = player

        // Ensure buttons stay wired and visible after player attachment
        val exoSettingsBtn = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
        exoSettingsBtn?.visibility = android.view.View.VISIBLE

        val fabSettings = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_settings)
        fabSettings?.setOnClickListener {
            showVideoQualityDialog()
        }
        // Custom override removed to allow native ExoPlayer settings menu

        // We will keep the button always visible so the user knows it exists,
        // as per their feedback. It will just show a toast if clicked and only 1 track exists.
        fabSettings?.visibility = android.view.View.VISIBLE

        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            }
        })

        hasNotifiedCacheComplete = false
        cacheProgressHandler.removeCallbacks(cacheProgressRunnable)
        cacheProgressHandler.postDelayed(cacheProgressRunnable, 2000)
    }

    private fun releasePlayer() { /* intentional no-op: activePlayer survives onStop */ }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null

        // If the activity is destroyed (e.g. PiP closed via 'X' button or swiped away) but the player is still playing,
        // we must explicitly hand off to the PlaybackService so the notification stays alive and controls work.
        if (player?.playWhenReady == true && player?.playbackState != androidx.media3.common.Player.STATE_ENDED) {
            val serviceIntent = android.content.Intent(this, PlaybackService::class.java).apply {
                action = "com.omymaxz.download.START_BACKGROUND_AUDIO"
                putExtra("video_url", videoUrl)
                putExtra("video_title", videoTitle)
                putExtra("current_position", player?.currentPosition ?: 0L)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // Only release if we actually intend to stop playback completely
            player?.release()
            player = null
        }

        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)

        } catch (e: Exception) {
            // Ignored
        }
        cacheProgressHandler.removeCallbacks(cacheProgressRunnable)

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

                // 1. Get the currently playing MediaItem which has resolved stream keys
                val currentMediaItem = player?.currentMediaItem
                val mimeType = currentMediaItem?.localConfiguration?.mimeType
                    ?: androidx.media3.common.MimeTypes.APPLICATION_M3U8

                // 2. Explicitly restrict to the currently selected tracks (e.g., 480p)
                // Extract using group.mediaTrackGroupIndex to align perfectly with native HLS manifest indices.
                val streamKeys = mutableListOf<androidx.media3.common.StreamKey>()
                val streamKeyStrings = ArrayList<String>() // Keep for FFmpeg fallback
                if (player != null) {
                    val tracks = player!!.currentTracks
                    // We need to pass the raw stream keys to Transformer. The groupIndex in tracks.groups
                    // corresponds directly to the track group index in the master playlist for HLS.
                    tracks.groups.forEachIndexed { groupIndex, group ->
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                streamKeys.add(androidx.media3.common.StreamKey(groupIndex, i))
                                streamKeyStrings.add("$groupIndex,$i")
                            }
                        }
                    }
                }

                val exportMediaItem = currentMediaItem?.buildUpon()
                    ?.setStreamKeys(streamKeys)
                    ?.build() ?: currentMediaItem

                // Release the hardware decoder before starting Transformer
                player?.pause()

                val intent = Intent(this, HlsExportService::class.java).apply {
                    if (exportMediaItem != null) {
                        putExtra(HlsExportService.EXTRA_MEDIA_ITEM_BUNDLE, exportMediaItem.toBundle())
                    }

                    putExtra(HlsExportService.EXTRA_VIDEO_URL, videoUrl) // Fallback for FFmpeg
                    putExtra(HlsExportService.EXTRA_TITLE, videoTitle)
                    val splitAudioUrl = intent.getStringExtra(YouTubeDownloadService.EXTRA_AUDIO_URL)
                    if (!splitAudioUrl.isNullOrEmpty()) {
                        putExtra("com.omymaxz.download.extra.AUDIO_URL", splitAudioUrl)
                    }
                    putExtra(HlsExportService.EXTRA_MIME_TYPE, mimeType)
                    putStringArrayListExtra(HlsExportService.EXTRA_STREAM_KEYS, streamKeyStrings)
                    putExtra(HlsExportService.EXTRA_FORCE_TRANSFORMER, true) // User pressed save, so guarantee offline mode

                    putExtra(HlsExportService.EXTRA_USER_AGENT, HlsDownloadHelper.currentUserAgent)
                    putExtra(HlsExportService.EXTRA_REFERER, HlsDownloadHelper.currentReferer)
                    putExtra(HlsExportService.EXTRA_COOKIE, HlsDownloadHelper.currentCookie)
                }
                startService(intent)
                Toast.makeText(this, "Exporting cached video...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}