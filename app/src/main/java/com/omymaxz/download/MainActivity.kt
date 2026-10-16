package com.omymaxz.download

import androidx.core.widget.doAfterTextChanged

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executor
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omymaxz.download.databinding.ActivityMainBinding
import com.omymaxz.download.databinding.DialogMediaListBinding
import com.omymaxz.download.YouTubeDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private val detectorHideHandler = Handler(Looper.getMainLooper())
    private var floatingDetectorMediaKey: String? = null
    private var floatingDetectorHiddenMediaKey: String? = null
    private val detectorHideRunnable = Runnable {
        floatingDetectorHiddenMediaKey = floatingDetectorMediaKey
        findViewById<android.view.View>(R.id.floatingDetectorUI)?.visibility = android.view.View.GONE
    }



    private var floatingDetectorStartX = 0f
    private var floatingDetectorStartY = 0f
    private var floatingDetectorInitialTouchX = 0f
    private var floatingDetectorInitialTouchY = 0f
    private var isFloatingDetectorMoved = false
    private var savedDetectorX: Float? = null
    private var savedDetectorY: Float? = null

    val mediaEngine = MediaDetectionEngine(this)
    @Volatile private var approvedNavigationUrl: String? = null

    var isManualScanPending = false
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    @Volatile private var currentPageTitleSnapshot: String = ""
    private lateinit var userscriptInterface: UserscriptInterface
    private lateinit var gmApi: GMApi

    private val detectedMediaFiles = Collections.synchronizedList(mutableListOf<MediaFile>())
    private var currentMediaListAdapter: MediaListAdapter? = null
    private var refreshDetectedMediaSheet: (() -> Unit)? = null
    private val subtitleAnalysisInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private var lastUsedName: String = "Video"
    var lastUsedUrl: String? = null
    var cachedUserAgent: String? = null
    var currentVideoUrl: String? = null
    private var fullscreenView: View? = null
    private var isAutoTranslateEnabled = false
    private var fullscreenDownloadButton: View? = null
    private var fullscreenExitButton: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var isServiceRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val webMediaPipHandler = Handler(Looper.getMainLooper())
    @Volatile private var webMediaPlayingForPip = false
    @Volatile private var webMediaPipAspectRatio = android.util.Rational(16, 9)
    private var webMediaPipMonitorStarted = false
    private var pipToolbarVisibility = View.VISIBLE
    private var pipFabVisibility = View.GONE
    private var pipProgressVisibility = View.GONE
    private var pipDetectorVisibility = View.GONE
    private val webMediaPipMonitor = object : Runnable {
        override fun run() {
            if (!webMediaPipMonitorStarted || !::webView.isInitialized || isFinishing || isDestroyed) {
                webMediaPipMonitorStarted = false
                return
            }
            val script = """
                (function() {
                    var videos = Array.from(document.querySelectorAll('video'));
                    var active = videos.filter(function(v) {
                        if (v.paused || v.ended || v.readyState < 2) return false;
                        var r = v.getBoundingClientRect();
                        var style = window.getComputedStyle(v);
                        return r.width > 0 && r.height > 0 &&
                               style.display !== 'none' && style.visibility !== 'hidden';
                    }).sort(function(a, b) {
                        return (b.videoWidth * b.videoHeight) - (a.videoWidth * a.videoHeight);
                    })[0];
                    return active ? ('1,' + (active.videoWidth || active.clientWidth || 16) +
                        ',' + (active.videoHeight || active.clientHeight || 9)) : '0,16,9';
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { result ->
                val parts = result?.trim('"')?.split(',')
                val playing = parts?.getOrNull(0) == "1"
                webMediaPlayingForPip = playing
                if (playing) {
                    val width = parts?.getOrNull(1)?.toIntOrNull() ?: 16
                    val height = parts?.getOrNull(2)?.toIntOrNull() ?: 9
                    if (width > 0 && height > 0) {
                        val ratio = width.toFloat() / height.toFloat()
                        if (ratio in 0.5f..2.0f) {
                            webMediaPipAspectRatio = android.util.Rational(width, height)
                        }
                    }
                }
                updateWebMediaPipParams()
                if (webMediaPipMonitorStarted) {
                    webMediaPipHandler.postDelayed(this, 700L)
                }
            }
        }
    }
    private var lastQuickRefreshVideoKey: String? = null
    private var quickRefreshMonitorRunnable: Runnable? = null
    private var isDesktopMode: Boolean = false
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Notification permission is needed for background playback.", Toast.LENGTH_LONG).show()
            }
        }
    private var sessionBlockCount = 0
    private lateinit var db: AppDatabase
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private var tabs = mutableListOf<Tab>()
    private var currentTabIndex = -1
    private var tabsDialog: AlertDialog? = null
    private var isAppInBackground = false
    private var hasStartedForegroundService = false
    private var enabledUserScripts = listOf<UserScript>()
    private lateinit var redirectLogic: RedirectLogic
    private val suspiciousDomains = setOf(
        "googleads.com", "doubleclick.net", "googlesyndication.com",
        "facebook.com/tr", "amazon-adsystem.com", "adsystem.amazon.com",
        "outbrain.com", "taboola.com", "popads.net", "adnxs.com",
        "adsymptotic.com", "advertising.com", "adsystem.com",
        "profitableratecpm.com", "popunder.net", "pop-ads.com", "adcash.com",
        "propellerads.com", "revcontent.com", "mgid.com",

        // YouTube/Google ad delivery endpoints that are separate from normal video chunks.
        // Do not block googlevideo.com globally: it also serves the actual video.
        "www.googleadservices.com", "googleadservices.com",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
        "www.youtube.com/pagead", "youtube.com/pagead"
    )
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<android.net.Uri>? = null
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                // Handle multiple files
                val count = data.clipData!!.itemCount
                results = Array(count) { i ->
                    data.clipData!!.getItemAt(i).uri
                }
            } else if (data?.data != null) {
                // Handle single file
                results = arrayOf(data.data!!)
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }
    private var mediaService: MediaForegroundService? = null
    private var serviceBound = false
    private var webViewService: WebViewForegroundService? = null
    private var webViewServiceBound = false
    private var currentMediaTitle: String? = null
    var isMediaPlaying = false
    private var hasNextMedia: Boolean = false
    private var hasPreviousMedia: Boolean = false
    private var duration: Double = 0.0
    private var currentPosition: Long = 0L
    private val historyResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val urlToLoad = result.data?.getStringExtra("URL_TO_LOAD")
            if (urlToLoad != null) {
                webView.loadUrl(urlToLoad)
                showWebView()
            }
        }
    }


    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            val rootParams = binding.rootContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            rootParams?.setMargins(0, 0, 0, 0)
            binding.rootContainer.setPadding(0, 0, 0, 0)

            val params = binding.mainContent.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            params?.setMargins(0, 0, 0, 0)
            binding.mainContent.setPadding(0, 0, 0, 0)

            window.decorView.requestLayout()
            binding.rootContainer.requestLayout()
            binding.mainContent.requestLayout()
        }
    }

    companion object {
        const val ACTION_MEDIA_CONTROL = "com.omymaxz.download.MEDIA_CONTROL"
        const val EXTRA_COMMAND = "command"
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    checkDownloadStatus(downloadId)
                }
            }
        }
    }

    private fun checkDownloadStatus(downloadId: Long) {
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)

        downloadManager.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex != -1) {
                    val status = cursor.getInt(statusIndex)
                    if (status == DownloadManager.STATUS_FAILED) {
                        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)

                        val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                        val uriString = if (uriIndex != -1) cursor.getString(uriIndex) else "Unknown URL"

                        val reasonText = when (reason) {
                            DownloadManager.ERROR_CANNOT_RESUME -> "ERROR_CANNOT_RESUME (Some possibly transient error occurred but we can't resume the download.)"
                            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "ERROR_DEVICE_NOT_FOUND (No external storage device was found. Typically, this is because the SD card is not mounted.)"
                            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ERROR_FILE_ALREADY_EXISTS (The requested destination file already exists (the download manager will not overwrite an existing file).)"
                            DownloadManager.ERROR_FILE_ERROR -> "ERROR_FILE_ERROR (A storage issue arises which doesn't fit under any other error code.)"
                            DownloadManager.ERROR_HTTP_DATA_ERROR -> "ERROR_HTTP_DATA_ERROR (An error receiving or processing data occurred at the HTTP level.)"
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "ERROR_INSUFFICIENT_SPACE (There was not enough storage space to record the destination file.)"
                            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "ERROR_TOO_MANY_REDIRECTS (There were too many redirects.)"
                            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "ERROR_UNHANDLED_HTTP_CODE (An HTTP code was received that download manager can't handle.)"
                            DownloadManager.ERROR_UNKNOWN -> "ERROR_UNKNOWN (The download has completed with an error that doesn't fit under any other error code.)"
                            else -> {
                                if (reason in 400..599) {
                                    "HTTP_ERROR_$reason (The server returned HTTP status code $reason.)"
                                } else {
                                    "Unknown error code: $reason"
                                }
                            }
                        }

                        val errorMessage = "Download failed for URL:\n$uriString\n\nReason:\n$reasonText"

                        runOnUiThread {
                            createThemedDialogBuilder(this)
                                .setTitle("Download Failed")
                                .setMessage(errorMessage)
                                .setPositiveButton("Copy Error") { _, _ ->
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Download Error", errorMessage)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra(EXTRA_COMMAND) ?: return
            android.util.Log.d("MainActivity", "source=WEB_MEDIA action=$action activeTab=$currentTabIndex")
            val script = when (action) {
                MediaForegroundService.ACTION_PLAY -> "window.AndroidMediaController && window.AndroidMediaController.play();"
                MediaForegroundService.ACTION_PAUSE -> "window.AndroidMediaController && window.AndroidMediaController.pause();"
                MediaForegroundService.ACTION_NEXT -> "window.AndroidMediaController && window.AndroidMediaController.next && window.AndroidMediaController.next();"
                MediaForegroundService.ACTION_PREVIOUS -> "window.AndroidMediaController && window.AndroidMediaController.previous && window.AndroidMediaController.previous();"
                MediaForegroundService.ACTION_STOP_SERVICE -> "window.AndroidMediaController && window.AndroidMediaController.pause();"
                else -> null
            }
            if (script != null && ::webView.isInitialized && !this@MainActivity.isFinishing && !this@MainActivity.isDestroyed) webView.evaluateJavascript(script, null)
            if (action == MediaForegroundService.ACTION_STOP_SERVICE) {
                tabs.forEach { it.isMediaPlaying = false }
                isMediaPlaying = false
                stopPlaybackService()
            }
        }
    }

    inner class DoubaoInterface(private val activity: MainActivity) {
        private val blobSessions = mutableMapOf<String, java.io.FileOutputStream>()

        @JavascriptInterface
        fun startBlobDownload(url: String, mimeType: String, totalSize: Int): String {
            val sessionId = java.util.UUID.randomUUID().toString()
            try {
                val extension = if (mimeType.contains("png")) "png" else if (mimeType.contains("webp")) "webp" else "jpg"
                val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Doubao_${System.currentTimeMillis()}.$extension")
                val fos = java.io.FileOutputStream(file)
                blobSessions[sessionId] = fos
                return sessionId
            } catch (e: Exception) {
                e.printStackTrace()
                return ""
            }
        }

        @JavascriptInterface
        fun appendBlobChunk(sessionId: String, base64Chunk: String) {
            try {
                val fos = blobSessions[sessionId] ?: return
                // Remove potential data URI prefix if accidentally passed
                val cleanBase64 = if (base64Chunk.contains(",")) base64Chunk.substringAfter(",") else base64Chunk
                val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                fos.write(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun finishBlobDownload(sessionId: String) {
            try {
                blobSessions[sessionId]?.apply {
                    flush()
                    close()
                }
                blobSessions.remove(sessionId)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Blob Download complete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    Toast.makeText(activity, "Blob Download failed to finish", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun downloadImage(url: String) {
            activity.runOnUiThread {
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setTitle("Doubao Image")
                        setDescription("Downloading image from Doubao")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Doubao_${System.currentTimeMillis()}.png")
                        allowScanningByMediaScanner()
                    }
                    val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                createThemedDialogBuilder(this)
                    .setTitle("Background Playback")
                    .setMessage("For reliable background playback, please disable battery optimization for this app.")
                    .setPositiveButton("Settings") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun showManageStoragePermissionDialog() {
        createThemedDialogBuilder(this)
            .setTitle("Storage Permission Required")
            .setMessage("For Android 11 and above, this app needs special storage access to perform backup and restore operations. Please allow file access in the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageExternalStorageLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaForegroundService.LocalBinder
            mediaService = binder.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
            serviceBound = false
        }
    }

    private val webViewServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            webViewService = (service as WebViewForegroundService.WebViewBinder).getService()
            webViewServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            webViewService = null
            webViewServiceBound = false
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            runOnUiThread {
                if (webView.visibility == View.VISIBLE && webView.url != null) {
                    Toast.makeText(this@MainActivity, "Connection restored, reloading...", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
            }
        }
        override fun onLost(network: Network) {
            super.onLost(network)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var isPageLoading = false
    private var pendingScriptsToInject = mutableListOf<UserScript>()
    // Prevent repeated onProgressChanged callbacks from running a script multiple times per page.
    private var userscriptPageUrl: String? = null
    private val injectedUserscriptIdsForPage = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize NewPipe Extractor in the background
        Thread { YoutubeExtractorHelper.init(applicationContext) }.start()

        binding = ActivityMainBinding.inflate(layoutInflater)
        cachedUserAgent = android.webkit.WebSettings.getDefaultUserAgent(this)
        setContentView(binding.root)

        webView = findViewById(R.id.webView)
        userscriptInterface = UserscriptInterface(this, webView, lifecycleScope)
        gmApi = GMApi(webView)

        redirectLogic = RedirectLogic(getSharedPreferences("AdBlocker", Context.MODE_PRIVATE))
        setupWebView(webView)

        checkInitialStoragePermissions()

        db = AppDatabase.getDatabase(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        isAutoTranslateEnabled = prefs.getBoolean("auto_translate_enabled", false)
        if (isAutoTranslateEnabled) {
            binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
        }

        initializeTabs()
        setupUrlBarInToolbar()
        loadLastUsedName()
        setupHomeButton()
        setupTabButton()
        setupStartPage()
        setupToolbarNavButtons()
        applyGlossyTheme()
        binding.fabShowMedia.setOnClickListener {
            showMediaListDialog()
        }
        askForNotificationPermission()
        loadBookmarks()
        loadEnabledUserScripts()
        if (currentTabIndex in tabs.indices) {
            restoreTabState(currentTabIndex)
        } else {
            showStartPage()
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        checkBatteryOptimization()
        applyProxy()
    }

    private fun applyProxy() {
        val urbanAddress = UrbanVpnManager.localProxyAddress()
        val sharedPrefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        val manualHost = sharedPrefs.getString("proxy_host", null)?.trim()?.takeIf { it.isNotEmpty() }
        val manualPort = sharedPrefs.getInt("proxy_port", -1)
        val proxyAddress = urbanAddress ?: if (manualHost != null && manualPort in 1..65535) "$manualHost:$manualPort" else null
        val proxyEnabled = proxyAddress != null

        val proxyConfig = if (proxyEnabled) {
            ProxyConfig.Builder().addProxyRule(proxyAddress!!).build()
        } else {
            ProxyConfig.Builder().addDirect().build()
        }

        android.util.Log.i(
            "MyDownloaderProxy",
            when {
                urbanAddress != null -> "Applying Urban VPN local relay to WebView"
                proxyEnabled -> "Applying manual WebView proxy override for $proxyAddress"
                else -> "Applying direct WebView connection (proxy disabled or invalid settings)"
            }
        )
        try {
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                ContextCompat.getMainExecutor(this),
                Runnable {
                    android.util.Log.i("MyDownloaderProxy", "WebView proxy override callback completed; this alone does not verify routing")
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("MyDownloaderProxy", "Failed to apply WebView proxy override", e)
            Toast.makeText(this, "Could not apply WebView proxy: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkInitialStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
            val hasShownStorageNotice = sharedPrefs.getBoolean("HAS_SHOWN_STORAGE_NOTICE", false)

            if (!hasShownStorageNotice) {
                createThemedDialogBuilder(this)
                    .setTitle("Storage Access Notice")
                    .setMessage("This app may need storage access for backup/restore functionality. You'll be prompted when needed.")
                    .setPositiveButton("OK") { _, _ ->
                        sharedPrefs.edit().putBoolean("HAS_SHOWN_STORAGE_NOTICE", true).apply()
                    }
                    .show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Keep website WebView playback separate from CustomPlayerActivity's PiP behavior.
        if (CustomPlayerActivity.activePlayer != null || !webMediaPlayingForPip) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(buildWebMediaPipParams())
            } catch (e: Exception) {
                android.util.Log.w("WebMediaPiP", "Unable to enter PiP for website playback", e)
            }
        }
    }

    private fun buildWebMediaPipParams(): android.app.PictureInPictureParams {
        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(webMediaPipAspectRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(webMediaPlayingForPip)
        }
        return builder.build()
    }

    /**
     * PiP snapshots the Activity, not just the HTML video. Temporarily isolate the
     * active video in the page so the PiP window contains video rather than site chrome.
     * Original inline styles are saved and restored when leaving PiP.
     */
    private fun applyWebVideoPipLayout(enabled: Boolean) {
        if (!::webView.isInitialized) return
        val script = if (enabled) """
            (function() {
              var v = Array.from(document.querySelectorAll('video')).filter(function(x) {
                return !x.paused && !x.ended && x.readyState >= 2;
              }).sort(function(a,b) {
                return (b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight);
              })[0];
              if (!v) return 'no-active-video';
              if (window.__myDownloaderPipVideo && window.__myDownloaderPipVideo !== v) {
                window.__myDownloaderRestorePip && window.__myDownloaderRestorePip();
              }
              if (window.__myDownloaderPipVideo === v) return 'already-applied';
              var saved = [];
              function remember(el) {
                if (!el || saved.some(function(x){return x.el===el;})) return;
                saved.push({el:el, style:el.getAttribute('style'), hidden:el.getAttribute('hidden')});
              }
              remember(document.documentElement);
              remember(document.body);
              var node = v;
              while (node && node !== document.body) {
                var parent = node.parentElement;
                if (!parent) break;
                Array.from(parent.children).forEach(function(sibling) {
                  if (sibling !== node) {
                    remember(sibling);
                    sibling.style.setProperty('visibility','hidden','important');
                    sibling.style.setProperty('pointer-events','none','important');
                  }
                });
                node = parent;
              }
              remember(v);
              document.documentElement.style.setProperty('background','#000','important');
              document.documentElement.style.setProperty('overflow','hidden','important');
              document.body.style.setProperty('background','#000','important');
              document.body.style.setProperty('overflow','hidden','important');
              v.style.setProperty('position','fixed','important');
              v.style.setProperty('inset','0','important');
              v.style.setProperty('width','100vw','important');
              v.style.setProperty('height','100vh','important');
              v.style.setProperty('max-width','none','important');
              v.style.setProperty('max-height','none','important');
              v.style.setProperty('margin','0','important');
              v.style.setProperty('z-index','2147483647','important');
              v.style.setProperty('object-fit','contain','important');
              v.style.setProperty('background','#000','important');
              window.__myDownloaderPipVideo = v;
              window.__myDownloaderPipSaved = saved;
              window.__myDownloaderRestorePip = function() {
                (window.__myDownloaderPipSaved || []).reverse().forEach(function(x) {
                  if (x.style === null) x.el.removeAttribute('style');
                  else x.el.setAttribute('style', x.style);
                  if (x.hidden === null) x.el.removeAttribute('hidden');
                  else x.el.setAttribute('hidden', x.hidden);
                });
                window.__myDownloaderPipVideo = null;
                window.__myDownloaderPipSaved = null;
                window.__myDownloaderRestorePip = null;
              };
              return 'applied';
            })();
        """.trimIndent() else """
            (function() {
              if (window.__myDownloaderRestorePip) window.__myDownloaderRestorePip();
              return 'restored';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun updateWebMediaPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing || isDestroyed) return
        try {
            setPictureInPictureParams(buildWebMediaPipParams())
        } catch (e: Exception) {
            android.util.Log.d("WebMediaPiP", "Could not update PiP parameters", e)
        }
    }

    private fun startWebMediaPipMonitor() {
        if (webMediaPipMonitorStarted || !::webView.isInitialized || isFinishing || isDestroyed) return
        webMediaPipMonitorStarted = true
        webMediaPipHandler.removeCallbacks(webMediaPipMonitor)
        webMediaPipHandler.post(webMediaPipMonitor)
    }

    private fun stopWebMediaPipMonitor() {
        webMediaPipMonitorStarted = false
        webMediaPipHandler.removeCallbacks(webMediaPipMonitor)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            applyWebVideoPipLayout(true)
            pipToolbarVisibility = binding.toolbar.visibility
            pipFabVisibility = binding.fabShowMedia.visibility
            pipProgressVisibility = binding.progressBar.visibility
            pipDetectorVisibility = findViewById<android.view.View>(R.id.floatingDetectorUI)?.visibility
                ?: View.GONE
            binding.toolbar.visibility = View.GONE
            binding.fabShowMedia.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            findViewById<android.view.View>(R.id.floatingDetectorUI)?.visibility = View.GONE
        } else {
            applyWebVideoPipLayout(false)
            binding.toolbar.visibility = pipToolbarVisibility
            binding.fabShowMedia.visibility = pipFabVisibility
            binding.progressBar.visibility = pipProgressVisibility
            findViewById<android.view.View>(R.id.floatingDetectorUI)?.visibility = pipDetectorVisibility
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInBackground = true
        if (!isInPictureInPictureMode) stopWebMediaPipMonitor()
        if (isMediaPlaying) {
            startOrUpdatePlaybackService()
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        startWebMediaPipMonitor()
        if (hasStartedForegroundService) {
            stopPlaybackService()
        }

        // Prevent webview from auto-playing video if CustomPlayer is active in PiP or full screen
        if (CustomPlayerActivity.activePlayer != null) {
            val disableAutoPlayScript = "javascript:(function() { var video = document.querySelector('video'); if (video) { video.pause(); } })();"
            webView.loadUrl(disableAutoPlayScript)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // If CustomPlayerActivity enters PiP, MainActivity might regain focus but CustomPlayerActivity is still active.
        // We must ensure the webview video remains paused to prevent double audio.
        if (hasFocus && CustomPlayerActivity.activePlayer != null) {
            val disableAutoPlayScript = "javascript:(function() { var videos = document.querySelectorAll('video'); videos.forEach(v => v.pause()); })();"
            webView.loadUrl(disableAutoPlayScript)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MediaForegroundService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        Intent(this, WebViewForegroundService::class.java).also { intent ->
            bindService(intent, webViewServiceConnection, Context.BIND_AUTO_CREATE)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaControlReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_EXPORTED)
                registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
            } else {
                registerReceiver(mediaControlReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
                registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // Receivers are now unregistered in onStop
        stopWebMediaPipMonitor()
        webView.destroy()
        if (hasStartedForegroundService) {
            stopPlaybackService()
            hasStartedForegroundService = false
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(mediaControlReceiver)
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) { }

        val settingsPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val backgroundLoadingEnabled = settingsPrefs.getBoolean("background_loading_enabled", false)

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            mediaService = null
        }

        if (webViewServiceBound) {
            unbindService(webViewServiceConnection)
            webViewServiceBound = false
        }


        if (isMediaPlaying && !isChangingConfigurations) {
        } else {
            if (hasStartedForegroundService && !isMediaPlaying) {
                 stopPlaybackService()
            }
        }

        val currentUrl = if (webView.visibility == View.VISIBLE) webView.url else null
        val currentTitle = if (webView.visibility == View.VISIBLE) webView.title else null
        var currentState: Bundle? = null

        if (currentTabIndex in tabs.indices && webView.visibility == View.VISIBLE) {
            currentState = Bundle()
            try {
                webView.saveState(currentState)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to save WebView state: ${e.message}")
                currentState = null
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (currentTabIndex in tabs.indices) {
                    tabs[currentTabIndex].apply {
                        url = currentUrl
                        title = currentTitle ?: "New Tab"
                        state = currentState
                    }
                }
                val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
                val editor = sharedPrefs.edit()
                val gson = Gson()
                val tabsJson = gson.toJson(tabs)
                editor.putString("TABS_LIST", tabsJson)
                editor.putInt("CURRENT_TAB_INDEX", currentTabIndex)
                editor.apply()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to save tabs state: ${e.message}")
            }
        }
    }

    private fun applyGlossyTheme() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        // Default to a dark semi-transparent tint
        val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"

        try {
            val color = android.graphics.Color.parseColor(hexColor)
            binding.toolbar.setBackgroundColor(color)

            // Also apply theme color to the status bar and popup menu
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

                // Clear TRANSLUCENT_STATUS flag if it was ever set so we don't accidentally draw underneath the status bar/toolbar
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

                // Strip the alpha channel to make the status bar solid, as some OS versions don't render translucent well.
                val solidColor = android.graphics.Color.rgb(
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
                window.statusBarColor = solidColor
            }

            // On Android 12+, we can apply a hardware-accelerated background blur
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val renderEffect = android.graphics.RenderEffect.createBlurEffect(
                        10f, 10f, android.graphics.Shader.TileMode.CLAMP
                    )
                    // Apply blur to the background drawable if possible,
                    // or to a dedicated view behind it. For the toolbar itself,
                    // applying it to the toolbar blurs its children (buttons).
                    // To avoid blurring the buttons, we rely strictly on the semi-transparent color for now.
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to apply blur effect", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Invalid hex color: $hexColor")
        }
    }

    private fun setupToolbarNavButtons() {
        binding.overflowMenuButton.setOnClickListener { showCustomOverflowMenu(it) }
        binding.backButton.setOnClickListener { navigateBack() }
        binding.forwardButton.setOnClickListener { navigateForward() }
        binding.aiButton.setOnClickListener { openGeminiForCurrentPage() }
        binding.refreshButton.setOnClickListener { webView.reload() }
        binding.translateButton.setOnClickListener { toggleAutoTranslate() }
        binding.translateButton.setOnLongClickListener {
            showTranslateSettingsDialog()
            true
        }
    }

    private fun navigateBack() {
        if (currentTabIndex in tabs.indices) {
            val tab = tabs[currentTabIndex]
            if (tab.historyStack.size > 1) {
                tab.historyStack.removeAt(tab.historyStack.size - 1)
                webView.loadUrl(tab.historyStack.last())
            } else if (webView.canGoBack()) {
                webView.goBack()
            }
        } else if (webView.canGoBack()) {
            webView.goBack()
        }
        updateToolbarNavButtonState()
    }

    private fun navigateForward() {
        if (webView.canGoForward()) {
            webView.goForward()
        } else {
            Toast.makeText(this, "No page to go forward to", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGeminiForCurrentPage() {
        WebpageContextHolder.url = webView.url
        WebpageContextHolder.title = webView.title
        try {
            webView.evaluateJavascript("(function() { return document.body ? document.body.innerText : ''; })();") { result ->
                var cleanedText = result?.trim('"')?.replace("\\n", "\n") ?: ""
                if (cleanedText.length > 15000) {
                    cleanedText = cleanedText.substring(0, 15000) + "... (truncated)"
                }
                WebpageContextHolder.textContent = cleanedText
                startActivity(Intent(this, GeminiChatActivity::class.java))
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Could not read page context for Gemini", e)
            WebpageContextHolder.textContent = ""
            startActivity(Intent(this, GeminiChatActivity::class.java))
        }
    }

    private fun toggleAutoTranslate() {
        isAutoTranslateEnabled = !isAutoTranslateEnabled
        getSharedPreferences("Settings", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_translate_enabled", isAutoTranslateEnabled).apply()

        if (isAutoTranslateEnabled) {
            binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            injectTranslateScript(webView)
            Toast.makeText(this, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
        } else {
            binding.translateButton.clearColorFilter()
            Toast.makeText(this, "Auto-Translate OFF (Reloading...)", Toast.LENGTH_SHORT).show()
            webView.reload()
        }
    }

    private fun updateToolbarNavButtonState() {
        val canGoBack = (currentTabIndex in tabs.indices && tabs[currentTabIndex].historyStack.size > 1) || webView.canGoBack()
        binding.backButton.isEnabled = canGoBack
        binding.backButton.alpha = if (canGoBack) 1.0f else 0.5f

        val canGoForward = webView.canGoForward()
        binding.forwardButton.isEnabled = canGoForward
        binding.forwardButton.alpha = if (canGoForward) 1.0f else 0.5f
    }

    private fun initializeTabs() {
        if (!loadTabsState()) {
            tabs.add(Tab(title = "New Tab"))
            currentTabIndex = 0
        }
    }

    private fun setupTabButton() {
        binding.tabButton.setOnClickListener {
            showTabsDialog()
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        binding.tabCount.text = tabs.size.toString()
    }

    private fun showTabsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tabs, null)
        val tabsRecyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabsRecyclerView)
        val newTabButton = dialogView.findViewById<android.widget.Button>(R.id.newTabButton)
        val selectTabButton = dialogView.findViewById<android.widget.Button>(R.id.selectTabButton)
        val cancelSelectionButton = dialogView.findViewById<android.widget.Button>(R.id.cancelSelectionButton)
        val deleteSelectedButton = dialogView.findViewById<android.widget.Button>(R.id.deleteSelectedButton)

        // Force opaque theme directly on the view to guarantee no transparency
        val prefs = getSharedPreferences("Settings", android.content.Context.MODE_PRIVATE)
        val themeColorHex = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val baseColor = try { android.graphics.Color.parseColor(themeColorHex) } catch(e: Exception) { android.graphics.Color.parseColor("#A0000000") }
        val opaqueColor = android.graphics.Color.rgb(android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor))
        val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(opaqueColor)
            cornerRadius = 24f * resources.displayMetrics.density
        }
        dialogView.background = shapeDrawable

        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        tabsRecyclerView.layoutManager = layoutManager

        fun updateDeleteButton(count: Int) {
            deleteSelectedButton.text = "Delete ($count)"
        }

        val tabAdapter = TabAdapter(
            tabs = tabs,
            currentTabIndex = this.currentTabIndex,
            onTabClick = { position ->
                switchTab(position)
                tabsDialog?.dismiss()
            },
            onCloseClick = { position ->
                closeTab(position)
                tabsDialog?.dismiss()
                if (tabs.isNotEmpty()) {
                    showTabsDialog()
                }
            },
            onSelectionChanged = { count ->
                updateDeleteButton(count)
            }
        )
        tabsRecyclerView.adapter = tabAdapter

        newTabButton.setOnClickListener {
            createNewTab()
            tabsDialog?.dismiss()
        }

        selectTabButton.setOnClickListener {
            tabAdapter.setSelectionMode(true)
            newTabButton.visibility = View.GONE
            selectTabButton.visibility = View.GONE
            cancelSelectionButton.visibility = View.VISIBLE
            deleteSelectedButton.visibility = View.VISIBLE
            updateDeleteButton(0)
        }

        cancelSelectionButton.setOnClickListener {
            tabAdapter.setSelectionMode(false)
            newTabButton.visibility = View.VISIBLE
            selectTabButton.visibility = View.VISIBLE
            cancelSelectionButton.visibility = View.GONE
            deleteSelectedButton.visibility = View.GONE
        }

        deleteSelectedButton.setOnClickListener {
            val selectedPositions = tabAdapter.getSelectedPositions().sortedDescending()
            if (selectedPositions.isEmpty()) {
                Toast.makeText(this, "No tabs selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val wasCurrentTabDeleted = selectedPositions.contains(currentTabIndex)

            var deletedBeforeCurrent = 0

            for (pos in selectedPositions) {
                if (pos < currentTabIndex) {
                    deletedBeforeCurrent++
                }
                val closingTab = tabs[pos]
                if (closingTab.isMediaPlaying) {
                    stopPlaybackService()
                    isMediaPlaying = false
                }
                tabs.removeAt(pos)
            }

            if (tabs.isEmpty()) {
                tabs.add(Tab(title = "New Tab"))
                currentTabIndex = 0
                switchTab(0, forceReload = true)
            } else {
                if (wasCurrentTabDeleted) {
                    currentTabIndex -= deletedBeforeCurrent
                    if (currentTabIndex >= tabs.size) {
                        currentTabIndex = tabs.size - 1
                    }
                    if (currentTabIndex < 0) currentTabIndex = 0
                    switchTab(currentTabIndex, forceReload = true)
                } else {
                    currentTabIndex -= deletedBeforeCurrent
                    updateTabCount()
                    saveTabsState()
                }
            }

            tabsDialog?.dismiss()
            showTabsDialog()
        }

        tabsDialog = createThemedDialogBuilder(this, isOpaque = true)
            .setView(dialogView)
            .create()
        tabsDialog?.show()

        // Scroll to active tab
        tabsRecyclerView.post {
            layoutManager.scrollToPositionWithOffset(currentTabIndex, 0)
        }
    }

    private fun createNewTab() {
        saveCurrentTabState()
        val newTab = Tab(title = "New Tab")
        tabs.add(newTab)
        switchTab(tabs.size - 1)
    }

    private fun openInNewTab(url: String, inBackground: Boolean) {
        saveCurrentTabState()
        val newTab = Tab(url = url, title = "Loading...")
        tabs.add(newTab)
        updateTabCount()
        if (inBackground) {
            Toast.makeText(this, "Link opened in background tab", Toast.LENGTH_SHORT).show()
        } else {
            switchTab(tabs.size - 1)
        }
    }

    private fun closeTab(position: Int) {
        if (tabs.size <= 1) {
            Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
            return
        }
        val closingCurrentTab = position == currentTabIndex

        val closingTab = tabs[position]
        if (closingTab.isMediaPlaying) {
            stopPlaybackService()
            isMediaPlaying = false
        }

        tabs.removeAt(position)
        if (closingCurrentTab) {
            val newIndex = if (position > 0) position - 1 else 0
            switchTab(newIndex, forceReload = true)
        } else {
            if (position < currentTabIndex) {
                currentTabIndex--
            }
            updateTabCount()
        }
    }

    private fun saveCurrentTabState() {
        if (currentTabIndex in tabs.indices) {
            val currentTab = tabs[currentTabIndex]
            if (webView.visibility == View.VISIBLE) {
                currentTab.url = webView.url
                currentTab.title = webView.title ?: "New Tab"
                val state = Bundle()
                try {
                    webView.saveState(state)
                    currentTab.state = state
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to save WebView state: ${e.message}")
                }
            }
        }

    }

    private fun restoreTabState(tabIndex: Int) {
        if (tabIndex !in tabs.indices) return
        val tab = tabs[tabIndex]
        binding.urlEditTextToolbar.setText(tab.url)
        
        if (tab.url != null) {
            if (tab.historyStack.isNotEmpty()) {
                webView.loadUrl(tab.historyStack.last())
            } else {
                webView.loadUrl(tab.url!!)
            }
            showWebView()
            if (tab.state != null) {
                try {
                    webView.restoreState(tab.state!!)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to restore WebView state: ${e.message}")
                }
            }
        } else if (tab.state != null) {
             try {
                webView.restoreState(tab.state!!)
                showWebView()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to restore WebView state: ${e.message}")
                showStartPage()
            }
        } else {
            showStartPage()
        }
    }

    private fun switchTab(newIndex: Int, forceReload: Boolean = false) {
        if (newIndex !in tabs.indices) return
        if (isMediaPlaying) {
            stopPlaybackService()
            isMediaPlaying = false
        }
        if (!forceReload) {
            saveCurrentTabState()
        }
        currentTabIndex = newIndex
        restoreTabState(currentTabIndex)
        updateTabCount()
    }

    private fun setupHomeButton() {
        binding.homeButton.setOnClickListener {
            if (currentTabIndex in tabs.indices) {
                tabs[currentTabIndex].url = null
                tabs[currentTabIndex].state = null
                tabs[currentTabIndex].historyStack.clear()
            }
            showStartPage()
        }
    }

    private fun showStartPage() {
        webView.visibility = View.GONE
        binding.bookmarkRecyclerView.visibility = View.VISIBLE
        binding.urlEditTextToolbar.setText("")
        updateToolbarNavButtonState()
    }

    private fun showWebView() {
        webView.visibility = View.VISIBLE
        binding.bookmarkRecyclerView.visibility = View.GONE
        updateToolbarNavButtonState()
    }

    private fun setupStartPage() {
        bookmarkAdapter = BookmarkAdapter(
            bookmarks = mutableListOf(),
            onItemClick = { bookmark ->
                webView.loadUrl(bookmark.url)
                showWebView()
            },
            onItemLongClick = { bookmark ->
                showDeleteBookmarkDialog(bookmark)
            }
        )
        binding.bookmarkRecyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = bookmarkAdapter
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bookmarks = db.bookmarkDao().getAll()
            withContext(Dispatchers.Main) {
                bookmarkAdapter.updateData(bookmarks)
            }
        }
    }

    private fun showDeleteBookmarkDialog(bookmark: Bookmark) {
        createThemedDialogBuilder(this)
            .setTitle("Delete Bookmark?")
            .setMessage("Are you sure you want to delete '${bookmark.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteBookmark(bookmark)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.bookmarkDao().delete(bookmark)
            withContext(Dispatchers.Main) {
                loadBookmarks()
                Toast.makeText(this@MainActivity, "Bookmark deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupUrlBarInToolbar() {
        binding.urlEditTextToolbar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlFromEditTextToolbar()
                true
            } else {
                false
            }
        }
        binding.urlEditTextToolbar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlEditTextToolbar.post {
                    binding.urlEditTextToolbar.selectAll()
                }

                // Modern UI: Hide other buttons to stretch the URL bar
                binding.homeButton.visibility = View.GONE
                binding.backButton.visibility = View.GONE
                binding.forwardButton.visibility = View.GONE
                binding.refreshButton.visibility = View.GONE
                binding.translateButton.visibility = View.GONE
                binding.aiButton.visibility = View.GONE
                binding.tabButton.visibility = View.GONE
            } else {
                // Restore buttons when focus is lost
                binding.homeButton.visibility = View.VISIBLE
                binding.backButton.visibility = View.VISIBLE
                binding.forwardButton.visibility = View.VISIBLE
                binding.refreshButton.visibility = View.VISIBLE
                // Translate and AI are intentionally hidden in the compact toolbar.
                binding.translateButton.visibility = View.GONE
                binding.aiButton.visibility = View.GONE
                binding.tabButton.visibility = View.VISIBLE
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
             val clipboard = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
             val clip = android.content.ClipData.newPlainText("Copied Text", text)
             clipboard.setPrimaryClip(clip)
             runOnUiThread {
                 Toast.makeText(this@MainActivity, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
             }
        }
    }

    private fun loadUrlFromEditTextToolbar() {
        var url = binding.urlEditTextToolbar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://www.google.com/search?q=$url"
        }
        webView.loadUrl(url)
        showWebView()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlEditTextToolbar.windowToken, 0)
        binding.urlEditTextToolbar.clearFocus()
    }

    override fun onBackPressed() {
        if (fullscreenView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else if (currentTabIndex in tabs.indices && tabs[currentTabIndex].historyStack.size > 1) {
            tabs[currentTabIndex].historyStack.removeAt(tabs[currentTabIndex].historyStack.size - 1)
            val prevUrl = tabs[currentTabIndex].historyStack.last()
            webView.loadUrl(prevUrl)
            updateToolbarNavButtonState()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else if (webView.visibility == View.VISIBLE) {
            showStartPage()
        } else {
            super.onBackPressed()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        val density = resources.displayMetrics.density

        // Inject Doubao script at document start using a local intercepted URL
        val wrappedScript = "(function() { " +
            "if (!window.location.hostname.includes('doubao.com')) return; " +
            "if (window._doubaoInjectedEarly) return; " +
            "window._doubaoInjectedEarly = true; " +
            "var inject = function() {" +
            "  var script = document.createElement('script');" +
            "  script.id = 'doubao-injector';" +
            "  script.type = 'text/javascript';" +
            "  script.src = 'https://mydownloader.local/removemark_doubao.js';" +
            "  var parent = document.head || document.documentElement;" +
            "  if (parent) { parent.appendChild(script); console.log('[Doubao] Android injected script tag EARLY successfully'); }" +
            "  else { setTimeout(inject, 50); }" +
            "};" +
            "inject();" +
        "})();"

        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, wrappedScript, setOf("*"))
        }

        // --- PERFORMANCE OPTIMIZATIONS ---
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null) // Enable hardware acceleration
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true // Aggressively block auto-play to prevent background audio

            // --- PERFORMANCE SETTINGS ---
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT // Use cache
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Keep these as they are for proper layout handling
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            val blockPopups = prefs.getBoolean("block_popups_redirects", true)
            settings.javaScriptCanOpenWindowsAutomatically = !blockPopups
            settings.setSupportMultipleWindows(true)

            addJavascriptInterface(WebAPIPolyfill(this@MainActivity), "AndroidWebAPI")
            addJavascriptInterface(MediaStateInterface(this@MainActivity), "AndroidMediaState")
            addJavascriptInterface(userscriptInterface, "AndroidUserscriptAPI")
            addJavascriptInterface(gmApi, "GMApi")
            addJavascriptInterface(YouTubeInterface(this@MainActivity), "YouTubeInterface")
            addJavascriptInterface(DoubaoInterface(this@MainActivity), "AndroidDoubao")

            setOnCreateContextMenuListener { _, _, _ ->
                val hitTestResult = this.hitTestResult
                if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    val linkUrl = hitTestResult.extra
                    if (linkUrl != null) {
                        val options = arrayOf("Open in new tab", "Open in background tab", "Open in Custom Tab", "Copy link URL")
                        createThemedDialogBuilder(this@MainActivity)
                            .setTitle(linkUrl)
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> openInNewTab(linkUrl, false)
                                    1 -> openInNewTab(linkUrl, true)
                                    2 -> openInCustomTab(linkUrl)
                                    3 -> copyToClipboard(linkUrl)
                                }
                            }
                            .show()
                    }
                } else if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE) {
                    val imageUrl = hitTestResult.extra
                    if (imageUrl != null) {
                        val options = arrayOf("Open image in background", "Copy image link", "Download image")
                        createThemedDialogBuilder(this@MainActivity)
                            .setTitle(imageUrl)
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> openInNewTab(imageUrl, true)
                                    1 -> copyToClipboard(imageUrl)
                                    2 -> {
                                        try {
                                            val guessedName = URLUtil.guessFileName(imageUrl, null, null)
                                            val extension = guessedName.substringAfterLast('.', "png")
                                            val fileName = "image_${System.currentTimeMillis()}.$extension"
                                            val request = DownloadManager.Request(Uri.parse(imageUrl))
                                                .setTitle(fileName)
                                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                            dm.enqueue(request)
                                            Toast.makeText(applicationContext, "Downloading image...", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(applicationContext, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            .show()
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                private var lastNavigationTime = 0L
                private var navigationCount = 0

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    synchronized(injectedUserscriptIdsForPage) {
                        userscriptPageUrl = url
                        injectedUserscriptIdsForPage.clear()
                    }
                    val isYt = isYouTubeVideoUrl(url)
                    // YouTube SPA video changes are cleared by checkForYouTube using the
                    // canonical video ID; leaving YouTube invalidates in-flight extraction results.
                    if (!isYt) {
                        lastQuickRefreshVideoKey = null
                        resetYouTubeDetectionContext()
                        synchronized(detectedMediaFiles) {
                            detectedMediaFiles.clear()
                        }
                        (webView.tag as? MediaStateInterface)?.clearState()
                        mediaEngine.clear()
                        currentMediaListAdapter?.notifyDataSetChanged()
                    }
                    if (isYt) {
                        checkForYouTube(url)
                    }
                    runOnUiThread {
                        updateFabVisibility()
                    }

                    // Restore UI if trapped in fullscreen
                    if (fullscreenView != null) {
                        webView.webChromeClient?.onHideCustomView()
                    }
                    binding.mainContent.visibility = View.VISIBLE
                    binding.toolbar.visibility = View.VISIBLE

                    injectAntiHijackingScripts(view)

                    val javascript = if (isDesktopMode) {
                        """
                        javascript:(function() {
                            var vpf = document.querySelector('meta[name="viewport"]');
                            if(vpf){ vpf.remove(); }
                            var meta = document.createElement('meta');
                            meta.setAttribute('name', 'viewport');
                            meta.setAttribute('content', 'width=1920, user-scalable=yes, initial-scale=0.5');
                            document.getElementsByTagName('head')[0].appendChild(meta);

                            Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });
                            Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                        })();
                        """
                    } else {
                        """
                        javascript:(function() {
                            var vpf = document.querySelector('meta[name="viewport"]');
                            if(vpf){ vpf.remove(); }
                            var meta = document.createElement('meta');
                            meta.setAttribute('name', 'viewport');
                            meta.setAttribute('content', 'width=device-width, initial-scale=1.0, user-scalable=yes');
                            document.getElementsByTagName('head')[0].appendChild(meta);
                        })();
                        """
                    }
                    view?.evaluateJavascript(javascript.trimIndent(), null)

                    // Polyfill screen.orientation.lock to prevent native player crashes
                    val polyfill = """
                        javascript:(function(){
                          if (screen.orientation && screen.orientation.lock) {
                            const origLock = screen.orientation.lock.bind(screen.orientation);
                            screen.orientation.lock = function(orientation) {
                              return origLock(orientation).catch(function() { return Promise.resolve(); });
                            };
                          }
                        })();
                    """
                    view?.evaluateJavascript(polyfill.trimIndent(), null)

                    super.onPageStarted(view, url, favicon)
                    isPageLoading = true

                    binding.progressBar.visibility = View.VISIBLE
                    binding.urlEditTextToolbar.setText(url)
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }
                    if (currentTabIndex in tabs.indices) {
                        val tab = tabs[currentTabIndex]
                        if (tab.historyStack.isEmpty() || tab.historyStack.last() != url) {
                            tab.historyStack.add(url!!)
                            // Limit history size to prevent data bloat
                            if (tab.historyStack.size > 50) {
                                tab.historyStack.removeAt(0)
                            }
                        }
                    }
                    updateToolbarNavButtonState()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (url != null) this@MainActivity.lastUsedUrl = url
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // Auto-clear detected media on SPA navigation or in-page history push
                    // EXCEPT for YouTube, because YouTube is an SPA that immediately fires this
                    // and wipes our extracted video from the list right after finding it.
                    val isYt = isYouTubeVideoUrl(url)
                    if (!isYt) {
                        resetYouTubeDetectionContext()
                        synchronized(detectedMediaFiles) {
                            detectedMediaFiles.clear()
                        }
                        (webView.tag as? MediaStateInterface)?.clearState()
                        mediaEngine.clear()
                        runOnUiThread {
                            currentMediaListAdapter?.notifyDataSetChanged()
                            updateFabVisibility()
                        }
                    } else {
                        if (url != null) checkForYouTube(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.title?.takeIf { it.isNotBlank() }?.let { currentPageTitleSnapshot = it }
                        // No longer injecting transparent toolbar CSS padding. Layout is now structurally vertical.

                    isPageLoading = false
                    binding.progressBar.visibility = View.GONE
                    updateToolbarNavButtonState()
                    if (isAutoTranslateEnabled) {
                        injectTranslateScript(view)
                    }
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }

                    injectMediaStateDetector()
                    injectAdvancedMediaDetector()
                    injectStandardMediaDetector()
                    injectTelemetryScript()
                    view?.evaluateJavascript("(function() { AndroidMediaState.parseHtmlForHiddenM3u8(document.documentElement.innerHTML); })();", null)
                    if (url?.contains("jules.google.com", ignoreCase = true) == true) {
                        injectJulesLongPress(view)
                    }
                    injectPendingUserscripts()
                    url?.let {
                        addToHistory(it)
                        if (currentTabIndex in tabs.indices) {
                            tabs[currentTabIndex].url = it
                            tabs[currentTabIndex].title = view?.title ?: "No Title"
                        }
                    }
                    checkForYouTube(url)
                    startWebMediaPipMonitor()
                    if (getSharedPreferences("Settings", Context.MODE_PRIVATE)
                            .getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)) {
                        startYouTubeQuickRefreshMonitor()
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val isMainFrame = request?.isForMainFrame ?: false

                    // Only process top-level main frame navigations for these checks
                    if (!isMainFrame) return false

                    // One-shot safe navigation bypass if user explicitly approved it
                    if (url == approvedNavigationUrl) {
                        approvedNavigationUrl = null
                        return false // Let it load
                    }

                    val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)

                    // Confirm Navigation overrides all other redirect logic to ask the user.
                    val isConfirmNavEnabled = prefs.getBoolean("CONFIRM_NAVIGATION_ENABLED", false)
                    if (isConfirmNavEnabled) {
                        // Do not show the prompt if this URL is exactly the one the user typed/opened initially
                        // (We need to distinguish initial load vs subsequent redirect).
                        // If it's a redirect, or an unsolicited new page load:
                        showConfirmNavigationDialog(url, view)
                        return true // Block execution and wait for user input
                    }

                    if (!prefs.getBoolean("block_popups_redirects", true)) return false

                    if (redirectLogic.shouldOverrideUrlLoading(request, view?.url)) {
                        if (url.contains("perchance.org")) {
                            openInCustomTab(url)
                        } else {
                            showBlockedNavigationDialog(url)
                        }
                        return true
                    }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    // Intercept local Doubao script
                    if (url.contains("mydownloader.local/removemark_doubao.js")) {
                        try {
                            val inputStream = assets.open("removemark_doubao.js")
                            return WebResourceResponse("application/javascript", "UTF-8", inputStream)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val reqHeaders = request?.requestHeaders
                    val reqReferer = reqHeaders?.get("Referer") ?: reqHeaders?.get("referer")
                    val userAgent = reqHeaders?.get("User-Agent") ?: reqHeaders?.get("user-agent")

                    // Check for standard YouTube watch links or embedded player frames
                    if (url.contains("youtube.com/watch") ||
                        url.contains("youtube.com/embed/") ||
                        url.contains("youtube-nocookie.com/embed/")) {

                        // Extract video ID and normalize to watch URL if it's an embed
                        val normalizedUrl = if (url.contains("embed/")) {
                            val videoId = url.substringAfter("/embed/").substringBefore("?")
                            if (videoId.isNotEmpty()) "https://www.youtube.com/watch?v=$videoId" else url
                        } else {
                            url
                        }

                        checkForYouTube(normalizedUrl)
                    }

                    // Targeted diagnostic logging for "stream_iq.m3u8" or related goplay.su iQIYI traffic
                    if (url.contains("stream_iq.m3u8") || url.contains("iqiyi.com") || url.contains("71edge.com") || url.contains("goplay.su")) {
                        android.util.Log.i("iQIYI_Diagnostic", "Intercepted: URL=$url")
                        android.util.Log.i("iQIYI_Diagnostic", "Method=${request?.method}, Referer=$reqReferer")
                    }

                    // IDMP VTT to M3U8 Rewrite Logic
                    // Sites sometimes hide the video stream behind a thumbnail track.
                    // e.g. https://domain.com/.../seek/thumbnails.vtt -> https://domain.com/.../720p/index.m3u8
                    if (url.contains("/seek/thumbnails.vtt")) {
                        val rewrittenUrl = url.replace("/seek/thumbnails.vtt", "/720p/index.m3u8")
                        android.util.Log.d("IDMP_Rewrite", "Rewriting VTT to M3U8: $rewrittenUrl")
                        mediaEngine.processRequest(rewrittenUrl, reqReferer, userAgent)
                    }
                    // Handle generic thumbnail.vtt to index-f1-v1-a1.m3u8 for alternative CDNs like player.upn.one
                    if (url.contains("thumbnail.vtt")) {
                        // We attempt a generic fallback to see if an index.m3u8 or tt/index-f1-v1-a1.m3u8 exists.
                        // Using MediaEngine we can just throw the rewritten string as a candidate.
                        val rewrittenUrl2 = url.replace("thumbnail.vtt", "tt/index-f1-v1-a1.m3u8")
                        android.util.Log.d("IDMP_Rewrite", "Rewriting thumbnail.vtt to M3U8: $rewrittenUrl2")
                        mediaEngine.processRequest(rewrittenUrl2, reqReferer, userAgent)

                        val rewrittenUrl3 = url.replace("thumbnail.vtt", "index.m3u8")
                        mediaEngine.processRequest(rewrittenUrl3, reqReferer, userAgent)
                    }

                    // Safely process through MediaDetectionEngine on background thread
                    val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                    val detectionEnabled = prefs.getBoolean("MEDIA_DETECTION_ENABLED", true)

                    var engineCandidate: MediaCandidate? = null
                    if (detectionEnabled) {
                        engineCandidate = mediaEngine.processRequest(url, reqReferer, userAgent)
                    }

                    if (isUrlWhitelisted(url)) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (isAdDomain(url)) {
                        return createEmptyResponse()
                    }

                    // IFRAME FIX: If MediaDetectionEngine successfully parsed an HLS/DASH manifest or valid MediaCandidate,
                    // we must ensure it bypasses the legacy `isMediaUrl(url)` string matcher (which might fail on extensionless blob fallback URLs).
                    // As long as it is explicitly not an ad.
                    val isExplicitEngineMedia = engineCandidate != null && engineCandidate.isManifest

                    if (isMediaUrl(url) || isExplicitEngineMedia) {
                        if (isAdUrl(url)) {
                            android.util.Log.d("WebViewClient", "Ignoring AD request: $url")
                            return super.shouldInterceptRequest(view, request)
                        }
                        // Hook for detecting upstream network requests bypassing JS blobs.
                        // We register this internally so `CustomPlayerActivity` can use it when playing active streams.
                        if (url.contains(".m3u8", ignoreCase = true) || url.endsWith(".mp4") || url.contains("videoplayback")) {
                            currentVideoUrl = url
                            runOnUiThread {
                                // Map back through our advanced MediaDetectionEngine to check if we should actually show this in UI
                                val candidate = mediaEngine.getCandidate(url)
                                if (candidate != null && (candidate.adScore > 0 || candidate.finalScore < 0)) {
                                    // Skip adding to UI if engine strongly thinks it's an ad
                                } else {
                                    if (detectionEnabled) {
                                        webView.evaluateJavascript("if (window.AndroidMediaState && window.AndroidMediaState.onMediaDetected) { window.AndroidMediaState.onMediaDetected('$url', 'video'); }", null)
                                    }
                                }
                            }
                        }
                        try {
                            var category = MediaCategory.fromUrl(url)

                            // Audio Mime fallback check
                            val resMime = request?.requestHeaders?.get("Accept")?.lowercase()
                            if (resMime?.contains("audio/") == true && category == MediaCategory.UNKNOWN) {
                                category = MediaCategory.AUDIO
                            }

                            val isMainContent = isMainVideoContent(url)
                            if (category == MediaCategory.VIDEO && isMainContent) {
                                currentVideoUrl = url
                            }

                            val ext = if (category == MediaCategory.AUDIO) {
                                if (url.contains(".m4a", ignoreCase = true)) ".m4a"
                                else if (url.contains(".mp3", ignoreCase = true)) ".mp3"
                                else if (url.contains(".aac", ignoreCase = true)) ".aac"
                                else if (url.contains(".ogg", ignoreCase = true)) ".ogg"
                                else if (url.contains(".wav", ignoreCase = true)) ".wav"
                                else ".m4a" // Default fallback for generic audio progressive streams
                            } else detectVideoFormat(url).extension

                            val detectedFormat = detectVideoFormat(url)
                            val finalMime = if (category == MediaCategory.AUDIO) "audio/*" else detectedFormat.mimeType

                            val quality = extractQualityFromUrl(url)
                            val enhancedTitle = generateSmartFileName(url, ext, quality, category)
                            val fileSize = estimateFileSize(url, category)
                            val language = extractLanguageFromUrl(url)
                            val finalTitle = if (category == MediaCategory.SUBTITLE) SubtitleUtils.originalSubtitleFilename(url) ?: enhancedTitle else enhancedTitle
                            val mediaFile = MediaFile(
                                url = url,
                                title = finalTitle,
                                mimeType = finalMime,
                                quality = quality,
                                category = category,
                                fileSize = fileSize,
                                language = language,
                                isMainContent = isMainContent
                            )
                            val candidate = mediaEngine.getCandidate(url)
                            if (candidate != null && (candidate.adScore > 0 || candidate.finalScore < 0)) {
                                return super.shouldInterceptRequest(view, request)
                            }

                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(url) || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            // 60-Second Hard Eligibility Check
                            val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                            if (!existsAlready && isEligible) {
                                synchronized(detectedMediaFiles) {
                                    detectedMediaFiles.add(mediaFile)
                                }
                                runOnUiThread { updateFabVisibility() }
                                if (category == MediaCategory.SUBTITLE) {
                                    val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                                    if (prefs.getBoolean("AUTO_ANALYZE_MEDIA", false)) {
                                        fetchSubtitleSnippet(mediaFile)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error processing media URL: ${e.message}")
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                private fun createEmptyResponse(): WebResourceResponse {
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    // Mute standard benign Chromium noise like document.domain cluster isolation
                    if (consoleMessage?.message()?.contains("document.domain mutation is ignored") == true) {
                        return true
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    binding.progressBar.progress = newProgress
                    if (newProgress == 100) {
                        binding.progressBar.visibility = View.GONE
                    } else {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    if (newProgress >= 10 && isPageLoading) {
                         injectEarlyUserscripts(view?.url)
                    }
                    if (newProgress > 80 && view?.url?.contains("jules.google.com", ignoreCase = true) == true) {
                        injectJulesLongPress(view)
                    }
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank()) {
                        currentPageTitleSnapshot = title
                    }
                    if (currentTabIndex in tabs.indices && !title.isNullOrBlank()) {
                        tabs[currentTabIndex].title = title
                    }
                }
                override fun onPermissionRequest(request: PermissionRequest?) {
                    // No camera/microphone permissions are declared or runtime-granted by this app.
                    // Deny website requests by default instead of granting arbitrary WebView resources.
                    request?.deny()
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: android.webkit.WebChromeClient.FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val acceptedTypes = fileChooserParams?.acceptTypes
                        if (acceptedTypes != null && acceptedTypes.isNotEmpty()) {
                            putExtra(android.content.Intent.EXTRA_MIME_TYPES, acceptedTypes)
                        }
                        if (fileChooserParams?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(this@MainActivity, "Cannot open file chooser", android.widget.Toast.LENGTH_LONG).show()
                        return false
                    }
                    return true
                }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    android.util.Log.d("WEB_FULLSCREEN_TRACE", "[WEB_FULLSCREEN_TRACE] event=onShowCustomView state=entering")
                    if (fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    customViewCallback = callback

                    // No longer forcefully casting LayoutParams here.
                    // Let Android natively assign them when added to the FrameLayout decorView,
                    // or use a safe cast if we must modify margins.

                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.addView(fullscreenView)

                    // Add floating buttons back for WebMedia Fullscreen
                    fullscreenDownloadButton = android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_save)
                        setBackgroundResource(R.drawable.rounded_background)
                        setPadding(20, 20, 20, 20)
                        setOnClickListener {
                            showMediaListDialog()
                        }
                    }
                    val btnParams = android.widget.FrameLayout.LayoutParams(140, 140).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = 150
                        marginEnd = 50
                    }
                    decorView.addView(fullscreenDownloadButton, btnParams)

                    // Use modern WindowInsetsControllerCompat if possible to prevent brittle deprecated flag behavior
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } else {
                        window.decorView.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
                    }

                    // DO NOT attach a setOnSystemUiVisibilityChangeListener that forces state changes,
                    // as tapping the screen triggers system UI visibility changes which can confuse the WebView.
                    window.decorView.setOnSystemUiVisibilityChangeListener(null)
                    binding.mainContent.visibility = View.GONE
                }
                                override fun onHideCustomView() {
                    android.util.Log.d("WEB_FULLSCREEN_TRACE", "[WEB_FULLSCREEN_TRACE] event=onHideCustomView state=exiting")
                    if (fullscreenView == null) return

                    val decorView = window.decorView as android.view.ViewGroup
                    decorView.removeView(fullscreenView)
                    fullscreenView = null

                    if (fullscreenDownloadButton != null) {
                        decorView.removeView(fullscreenDownloadButton)
                        fullscreenDownloadButton = null
                    }

                    window.decorView.setOnSystemUiVisibilityChangeListener(null)
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
                        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }

                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    val rootParams = binding.rootContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    rootParams?.setMargins(0, 0, 0, 0)
                    binding.rootContainer.setPadding(0, 0, 0, 0)

                    val params = binding.mainContent.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    params?.setMargins(0, 0, 0, 0)
                    binding.mainContent.setPadding(0, 0, 0, 0)

                    binding.mainContent.visibility = View.VISIBLE

                    window.decorView.requestLayout()
                    binding.rootContainer.requestLayout()
                    binding.mainContent.requestLayout()

                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                    val currentUrl = view.url
                    val settingsPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
                    val blockPopups = settingsPrefs.getBoolean("BLOCK_ALL_POPUPS", true)

                    // Gate the entire blocking logic behind the master toggle
                    if (prefs.getBoolean("block_popups_redirects", true)) {
                        // Allow popups if user gesture is present OR if the URL is whitelisted
                        if (!isUserGesture && (currentUrl == null || !isUrlWhitelisted(currentUrl))) {
                            val showNotice = settingsPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
                            if (showNotice) {
                                Toast.makeText(applicationContext, "Malicious Pop-up blocked", Toast.LENGTH_SHORT).show()
                            }
                            return false // Aggressively kill the popup
                        }
                    }

                    val newWebView = WebView(this@MainActivity).apply {
                        settings.javaScriptEnabled = true
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.domStorageEnabled = true
                        settings.userAgentString = this@MainActivity.webView.settings.userAgentString
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    // For Google Sign-In and OAuth, the popup MUST be kept alive in the view hierarchy
                    // but we don't want it to overtake the screen initially.
                    val rootLayout = binding.rootContainer

                    // Create a wrapper layout for the popup if the user decides to open it
                    val popupWrapper = android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        visibility = View.GONE // Hidden by default
                    }

                    // Simple navigation bar for the popup
                    val popupNavBar = android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(16, 16, 16, 16)
                    }

                    val popupUrlTitle = android.widget.TextView(this@MainActivity).apply {
                        text = "Popup Window"
                        textSize = 16f
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    val popupCloseBtn = android.widget.ImageButton(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setOnClickListener {
                            popupWrapper.removeView(newWebView)
                            rootLayout.removeView(popupWrapper)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ newWebView.destroy() }, 500)
                        }
                    }

                    popupNavBar.addView(popupUrlTitle)
                    popupNavBar.addView(popupCloseBtn)

                    popupWrapper.addView(popupNavBar)
                    popupWrapper.addView(newWebView)

                    rootLayout.addView(popupWrapper)

                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = newWebView
                    resultMsg.sendToTarget()

                    // Show small notification box instead of overtaking screen
                    val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this@MainActivity)

                    val notifContainer = android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(32, 32, 32, 32)
                    }

                    val notifTitle = android.widget.TextView(this@MainActivity).apply {
                        text = "Pop-up Blocked"
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val notifMsg = android.widget.TextView(this@MainActivity).apply {
                        text = "A new window tried to open."
                        textSize = 14f
                        setPadding(0, 16, 0, 32)
                    }

                    val buttonContainer = android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.END
                    }

                    val closeBtn = android.widget.Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        text = "Close"
                        setOnClickListener {
                            bottomSheetDialog.dismiss()
                            popupWrapper.removeView(newWebView)
                            rootLayout.removeView(popupWrapper)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ newWebView.destroy() }, 500)
                        }
                    }

                    val openBtn = android.widget.Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        text = "Allow"
                        setOnClickListener {
                            bottomSheetDialog.dismiss()
                            popupWrapper.visibility = View.VISIBLE
                        }
                    }

                    buttonContainer.addView(closeBtn)
                    buttonContainer.addView(openBtn)

                    notifContainer.addView(notifTitle)
                    notifContainer.addView(notifMsg)
                    notifContainer.addView(buttonContainer)

                    bottomSheetDialog.setContentView(notifContainer)

                    val popupPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
                    val showNotice = popupPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
                    if (showNotice) {
                        bottomSheetDialog.show()
                    } else {
                        // Keep it invisible but alive if the user intends to block *notifications*
                        // but not necessarily the underlying functionality.
                        // However, since it is a pop-up block notice, and the user disabled notifications,
                        // it's safer to just kill the pop-up entirely to prevent invisible tracking windows.
                        popupWrapper.removeView(newWebView)
                        rootLayout.removeView(popupWrapper)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ newWebView.destroy() }, 500)
                    }

                    // Try to apply theme colors to this popup
                    val themeColorHex = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
                    val baseColor = try { android.graphics.Color.parseColor(themeColorHex) } catch(e: Exception) { android.graphics.Color.parseColor("#A0000000") }
                    val parsedColor = android.graphics.Color.rgb(
                        android.graphics.Color.red(baseColor),
                        android.graphics.Color.green(baseColor),
                        android.graphics.Color.blue(baseColor)
                    )

                    // Helper to get text color based on luminance
                    val luminance = (0.299 * android.graphics.Color.red(parsedColor) + 0.587 * android.graphics.Color.green(parsedColor) + 0.114 * android.graphics.Color.blue(parsedColor)) / 255
                    val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

                    val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(parsedColor)
                        cornerRadius = 24f * resources.displayMetrics.density
                    }

                    val parentView = notifContainer.parent as? View
                    parentView?.background = shapeDrawable
                    parentView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    notifContainer.background = shapeDrawable

                    notifTitle.setTextColor(textColor)
                    notifMsg.setTextColor(textColor)
                    closeBtn.setTextColor(textColor)
                    openBtn.setTextColor(textColor)

                    popupNavBar.setBackgroundColor(parsedColor)
                    popupUrlTitle.setTextColor(textColor)
                    val iconColorFilter = android.graphics.PorterDuffColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    popupCloseBtn.colorFilter = iconColorFilter

                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            runOnUiThread {
                                notifMsg.text = url
                                popupUrlTitle.text = url
                            }
                            // Let the popup handle its own redirects internally
                            return false
                        }
                    }

                    newWebView.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            popupWrapper.removeView(newWebView)
                            rootLayout.removeView(popupWrapper)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ newWebView.destroy() }, 500)
                        }
                    }

                    return true
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                try {
                    var fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    if (fileName.endsWith(".bin") || fileName.startsWith("downloadfile")) {
                        // Fallback 1: Extract from Content-Disposition header directly
                        if (!contentDisposition.isNullOrEmpty()) {
                            val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
                            if (match != null) {
                                fileName = match.groupValues[1]
                            }
                        }
                        // Fallback 2: Extract from the URL path if Content-Disposition failed or wasn't present
                        if (fileName.endsWith(".bin") || fileName.startsWith("downloadfile")) {
                            try {
                                val uriPath = java.net.URI(url).path
                                if (!uriPath.isNullOrEmpty()) {
                                    val lastSegment = uriPath.substringAfterLast("/")
                                    if (lastSegment.contains(".")) {
                                        fileName = java.net.URLDecoder.decode(lastSegment, "UTF-8")
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore parsing errors, keep the guessed file name
                            }
                        }
                    }

                    createThemedDialogBuilder(this@MainActivity)
                        .setTitle("Download File")
                        .setMessage("Do you want to download $fileName?")
                        .setPositiveButton("Download") { _, _ ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(url)).apply {
                                    setMimeType(mimetype)
                                    addRequestHeader("User-Agent", userAgent)
                                    val cookie = CookieManager.getInstance().getCookie(url)
                                    if (cookie != null) {
                                        addRequestHeader("Cookie", cookie)
                                    }
                                    val currentUrl = webView.url
                                    if (currentUrl != null) {
                                        addRequestHeader("Referer", currentUrl)
                                    }
                                    setDescription("Downloading file...")
                                    setTitle(fileName)
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                }
                                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(applicationContext, "Download Failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(applicationContext, "Download Check Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun openInCustomTab(url: String) {
        try {
            val builder = CustomTabsIntent.Builder()
            val color = ContextCompat.getColor(this, R.color.purple_500)
            builder.setToolbarColor(color)
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open the link.", Toast.LENGTH_LONG).show()
        }
    }
    inner class WebAPIPolyfill(private val context: Context) {
        private var tts: TextToSpeech? = null
        init {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    android.util.Log.e("WebAPIPolyfill", "TTS initialization failed.")
                }
            }
        }
        @JavascriptInterface
        fun speak(text: String, voiceName: String = "") {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId_${System.currentTimeMillis()}")
        }
        @JavascriptInterface
        fun getVoices(): String {
            return "[{\"name\":\"Default\",\"lang\":\"en-US\",\"default\":true}]"
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            this@MainActivity.copyToClipboard(text)
        }

        @JavascriptInterface
        fun saveBlob(base64Data: String, filename: String, mimeType: String) {
            try {
                val decodedBytes = android.util.Base64.decode(base64Data.substringAfter(","), android.util.Base64.DEFAULT)
                saveToDownloads(decodedBytes, filename, mimeType)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun showPreview(text: String, filename: String, mimeType: String, url: String?) {
            runOnUiThread {
                var currentFilename = filename
                val normalizedMime = mimeType.substringBefore(";").trim().lowercase()
                val filenameLower = filename.substringBefore("?").substringBefore("#").lowercase()
                val subtitleName = listOf(".vtt", ".srt", ".ass", ".ssa", ".ttml", ".dfxp", ".srv1", ".srv2", ".srv3")
                    .any { filenameLower.endsWith(it) }
                val subtitleMime = normalizedMime in setOf(
                    "text/vtt", "application/x-subrip", "application/ttml+xml",
                    "application/dfxp+xml", "text/x-ssa", "text/x-ass"
                )
                val previewLooksLikeSubtitle = text.trimStart().let { preview ->
                    preview.startsWith("WEBVTT", ignoreCase = true) ||
                        preview.startsWith("<tt", ignoreCase = true) ||
                        preview.startsWith("<?xml", ignoreCase = true) ||
                        preview.startsWith("<transcript", ignoreCase = true) ||
                        Regex("""(?m)^\s*\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->""").containsMatchIn(preview) ||
                        Regex("""(?m)^\s*\d{2}:\d{2}\s*-->""").containsMatchIn(preview)
                }
                val isSubtitlePreview = subtitleName || subtitleMime ||
                    (normalizedMime == "text/plain" && previewLooksLikeSubtitle)

                // If this is a subtitle, extract the snippet and update the Detection List title.
                // Preview payloads are intentionally capped for UI safety; never use them as the
                // bytes for a subtitle download when the original source URL is still available.
                if (mimeType.contains("text/") || subtitleName || subtitleMime) {
                    val result = SubtitleUtils.extractSnippet(text)
                    if (!result.snippet.isNullOrBlank()) {
                        val validatedLanguage = SubtitleUtils.languageDisplayName(result.language)
                        val originalFilename = url?.let { SubtitleUtils.originalSubtitleFilename(it) }
                            ?: filename.takeIf { it.substringAfterLast('.', "").lowercase() in setOf("vtt", "srt", "ass", "ssa", "ttml", "dfxp", "srv1", "srv2", "srv3") }
                            ?: "subtitle.${if (filename.endsWith(".srt", true)) "srt" else "vtt"}"
                        if (url != null) {
                            synchronized(detectedMediaFiles) {
                                val index = detectedMediaFiles.indexOfFirst {
                                    SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(url)
                                }
                                if (index != -1) {
                                    val existing = detectedMediaFiles[index]
                                    val language = existing.language?.let { SubtitleUtils.languageDisplayName(it) } ?: validatedLanguage
                                    detectedMediaFiles[index] = existing.copy(title = originalFilename, language = language)
                                }
                            }
                            refreshDetectedMediaSheet?.invoke()
                        }
                        // Keep the downloaded filename grounded in the source, not in snippet prose.
                        currentFilename = originalFilename
                    }
                }

                val scrollView = ScrollView(this@MainActivity)
                val textView = TextView(this@MainActivity).apply {
                    this.text = text
                    setPadding(32, 32, 32, 32)
                    setTextIsSelectable(true)
                }
                scrollView.addView(textView)

                createThemedDialogBuilder(this@MainActivity)
                    .setTitle("Content Preview")
                    .setView(scrollView)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Download") { _, _ ->
                        // Show rename prompt before saving. Prompt user with a blank input box
                        // so they can freely type the name rather than deleting the long snippet.
                        val input = EditText(this@MainActivity).apply {
                            hint = currentFilename.substringBeforeLast('.')
                            // Leave it empty so they don't have to backspace the long snippet
                        }
                        createThemedDialogBuilder(this@MainActivity)
                            .setTitle("Rename File")
                            .setView(input)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                val finalName = if (newName.isNotEmpty()) "$newName.${currentFilename.substringAfterLast('.')}" else currentFilename
                                val sourceUrl = url?.trim().orEmpty()
                                val hasFetchableSource = sourceUrl.startsWith("https://", ignoreCase = true) ||
                                    sourceUrl.startsWith("http://", ignoreCase = true) ||
                                    sourceUrl.startsWith("blob:", ignoreCase = true)
                                if (isSubtitlePreview && hasFetchableSource) {
                                    // The preview caller passes only the first 20,000 characters.
                                    // Re-enter the normal subtitle download path to fetch the whole
                                    // source (including KissKH tracks whose URL has no .vtt suffix).
                                    val sourceMime = when {
                                        subtitleMime -> normalizedMime
                                        filenameLower.endsWith(".srt") -> "application/x-subrip"
                                        else -> "text/vtt"
                                    }
                                    val subtitleDownload = MediaFile(
                                        url = sourceUrl,
                                        title = finalName,
                                        mimeType = sourceMime,
                                        quality = "Auto",
                                        category = MediaCategory.SUBTITLE,
                                        fileSize = "Unknown",
                                        language = null,
                                        isMainContent = false,
                                        referer = webView.url
                                    )
                                    downloadMediaFile(subtitleDownload)
                                } else {
                                    saveToDownloads(text.toByteArray(Charsets.UTF_8), finalName, mimeType)
                                }
                            }
                            .show()
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun onPreviewError(error: String) {
            runOnUiThread {
                Toast.makeText(context, "Preview failed: $error", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onBlobDownloadError(error: String) {
            runOnUiThread {
                Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onSubtitleSnippet(url: String, snippet: String, language: String?) {
            if (snippet.isNotBlank()) {
                val detectedFormat = detectVideoFormat(url)
                val ext = if (detectedFormat.extension == ".mp4") ".vtt" else detectedFormat.extension
                val prefix = if (!language.isNullOrBlank()) "[$language] " else ""
                val newTitle = "$prefix$snippet$ext"
                updateMediaTitle(url, newTitle)
            }
        }

        @JavascriptInterface
        fun onPreviewFallback(url: String, filename: String, mimeType: String) {
            if (url.startsWith("blob:")) {
                runOnUiThread {
                    Toast.makeText(context, "Preview failed: Blob URL inaccessible.", Toast.LENGTH_LONG).show()
                }
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    val userAgent = withContext(Dispatchers.Main) { webView.settings.userAgentString }
                    val cookie = CookieManager.getInstance().getCookie(url)
                    connection.setRequestProperty("User-Agent", userAgent)
                    if (cookie != null) {
                        connection.setRequestProperty("Cookie", cookie)
                    }

                    connection.connect()

                    val text = connection.inputStream.bufferedReader().use { it.readText() }

                    withContext(Dispatchers.Main) {
                        showPreview(text.take(20000), filename, mimeType, url)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Native Preview failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun injectAntiHijackingScripts(view: WebView?) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("block_popups_redirects", true)) return

        val url = view?.url ?: ""
        // Do not inject anti-hijacking script on whitelisted domains
        if (isUrlWhitelisted(url)) {
            return
        }

        val js = """
        javascript:(function() {
            // Override window.open to do nothing
            window.open = function() { return null; };

            // Keep ordinary site click handlers and links intact. Blocking popups via
            // window.open is less disruptive than rewriting page-owned DOM nodes.

            // Intercept and prevent click-jacking overlays
            document.addEventListener('click', function(e) {
                var target = e.target;
                if (target && target.tagName === 'DIV') {
                    var style = window.getComputedStyle(target);
                    // Check for invisible/transparent full-screen overlays often used for click-jacking
                    if (style.position === 'absolute' || style.position === 'fixed') {
                        if (style.zIndex > 1000 && (style.opacity < 0.1 || style.backgroundColor === 'rgba(0, 0, 0, 0)' || style.backgroundColor === 'transparent')) {
                            e.preventDefault();
                            e.stopPropagation();
                            target.remove(); // Remove the offending element
                            console.log('Intercepted and removed click-jack overlay.');
                        }
                    }
                }
            }, true);
        })();
        """
        view?.evaluateJavascript(js, null)
    }

    private fun injectPerchanceFixes(webView: WebView?) {
        val polyfillScript = """
            javascript:(function() {
                'use strict';
                if (typeof window.speechSynthesis === 'undefined') {
                    window.speechSynthesis = {
                        speak: function(utterance) {
                            if (utterance && utterance.text) {
                                AndroidWebAPI.speak(utterance.text, utterance.voice ? utterance.voice.name : '');
                            }
                        },
                        getVoices: function() { return JSON.parse(AndroidWebAPI.getVoices()); },
                        cancel: function() {},
                        pause: function() {},
                        resume: function() {}
                    };
                    window.SpeechSynthesisUtterance = function(text) { this.text = text; };
                }
                if (typeof window.addBackgroundToElement === 'undefined') {
                    window.addBackgroundToElement = function(element) {
                        return { setBackground: function() {}, removeBackground: function() {} };
                    };
                }
            })();
        """.trimIndent()
        webView?.loadUrl(polyfillScript)
    }

    private fun injectJulesLongPress(webView: WebView?) {
        val script = """
            (function() {
                if (window.julesLongPressInjected) return;
                window.julesLongPressInjected = true;

                // Create custom context menu container
                var menu = document.createElement('div');
                menu.id = 'jules-custom-context-menu';
                menu.style.position = 'fixed';
                menu.style.background = '#ffffff';
                menu.style.border = '1px solid #ccc';
                menu.style.borderRadius = '8px';
                menu.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)';
                menu.style.padding = '8px 0';
                menu.style.zIndex = '999999';
                menu.style.display = 'none';
                menu.style.fontFamily = 'sans-serif';
                menu.style.fontSize = '14px';
                menu.style.minWidth = '160px';

                var copyBtn = document.createElement('div');
                copyBtn.innerText = 'Copy Message';
                copyBtn.style.padding = '12px 16px';
                copyBtn.style.cursor = 'pointer';
                copyBtn.style.color = '#333';
                copyBtn.onmouseover = function() { this.style.backgroundColor = '#f0f0f0'; };
                copyBtn.onmouseout = function() { this.style.backgroundColor = 'transparent'; };

                var editBtn = document.createElement('div');
                editBtn.innerText = 'Copy to Input (Edit)';
                editBtn.style.padding = '12px 16px';
                editBtn.style.cursor = 'pointer';
                editBtn.style.color = '#333';
                editBtn.onmouseover = function() { this.style.backgroundColor = '#f0f0f0'; };
                editBtn.onmouseout = function() { this.style.backgroundColor = 'transparent'; };

                menu.appendChild(copyBtn);
                menu.appendChild(editBtn);
                document.body.appendChild(menu);

                var activeMessageText = "";

                document.addEventListener('click', function() {
                    menu.style.display = 'none';
                });

                document.addEventListener('contextmenu', function(e) {
                    var target = e.target;
                    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;
                    if (target.closest('a')) return;

                    var container = target;
                    var bestContainer = null;

                    while (container && container !== document.body) {
                        var role = container.getAttribute('data-message-author-role');
                        if (role === 'model' || role === 'user') {
                            bestContainer = container;
                            break;
                        }
                        if (container.classList.contains('model-turn') || container.classList.contains('user-turn') || container.classList.contains('assistant-message')) {
                            bestContainer = container;
                            break;
                        }
                        if (container.parentElement) {
                            var parentRole = container.parentElement.getAttribute('role');
                            if (parentRole === 'log' || parentRole === 'feed' || parentRole === 'list') {
                                bestContainer = container;
                                break;
                            }
                        }
                        container = container.parentElement;
                    }

                    if (!bestContainer) {
                        container = target;
                        while (container && container !== document.body) {
                            if (container.tagName === 'MAIN') break;
                            if (container.offsetWidth > (window.innerWidth * 0.8)) {
                                bestContainer = container;
                            }
                            container = container.parentElement;
                        }
                    }

                    var finalTarget = bestContainer || target;
                    var text = finalTarget.innerText;

                    if (text && text.trim().length > 0) {
                        e.preventDefault();
                        e.stopPropagation();

                        activeMessageText = text.trim();

                        // Position menu
                        menu.style.left = Math.min(e.clientX, window.innerWidth - 180) + 'px';
                        menu.style.top = Math.min(e.clientY, window.innerHeight - 100) + 'px';
                        menu.style.display = 'block';

                        finalTarget.style.outline = '2px solid #4CAF50';
                        setTimeout(function() { finalTarget.style.outline = ''; }, 200);
                    }
                }, true);

                copyBtn.onclick = function() {
                    if (window.AndroidWebAPI && window.AndroidWebAPI.copyToClipboard) {
                        window.AndroidWebAPI.copyToClipboard(activeMessageText);
                        menu.style.display = 'none';
                    }
                };

                editBtn.onclick = function() {
                    // Try to find the Jules input box. Usually it's a textarea or contenteditable div.
                    var inputArea = document.querySelector('textarea, [contenteditable="true"]');
                    if (inputArea) {
                        if (inputArea.tagName === 'TEXTAREA' || inputArea.tagName === 'INPUT') {
                            inputArea.value = activeMessageText;
                            // Trigger input event to resize textarea and enable send button
                            inputArea.dispatchEvent(new Event('input', { bubbles: true }));
                            inputArea.dispatchEvent(new Event('change', { bubbles: true }));
                        } else {
                            inputArea.innerText = activeMessageText;
                            inputArea.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        inputArea.focus();
                    } else {
                        // Fallback: Copy to clipboard if input not found
                        if (window.AndroidWebAPI && window.AndroidWebAPI.copyToClipboard) {
                            window.AndroidWebAPI.copyToClipboard(activeMessageText);
                        }
                    }
                    menu.style.display = 'none';
                };
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script, null)
    }
    private fun injectAdvancedMediaDetector() {
        val script = """
            javascript:(function() {
                'use strict';
                if (window.AndroidMediaDetector) return;

                // Network hooks are installed once by detector.initNetworkInterceptors().
                const detector = {
                    processedUrls: new Set(),

                    notify: function(url, type) {
                        if (!url || this.processedUrls.has(url)) return;
                        this.processedUrls.add(url);
                        if (window.AndroidMediaState && window.AndroidMediaState.onMediaDetected) {
                            window.AndroidMediaState.onMediaDetected(url, type);
                        }
                    },

                    checkUrl: function(url, type) {
                        if (!url || url.startsWith('data:')) return;
                    if (url.startsWith('blob:')) {
                        if (window.AndroidMediaState) window.AndroidMediaState.onMediaDetected(url, 'video');
                        return;
                    }
                        if (url.match(/\.m3u8/i)) {
                             this.notify(url, 'video');
                        }

                        // Basic extension check
                        if (url.match(/\.(mp4|mkv|webm|m3u8|mpd|mov|avi|flv|m4v)(\?|${'$'})/i)) {
                             this.notify(url, 'video');
                        } else if (url.match(/\.(mp3|aac|m4a|wav|ogg)(\?|${'$'})/i)) {
                             this.notify(url, 'audio');
                        } else if (url.match(/\.(vtt|srt|ass|ssa)(\?|${'$'})/i)) {
                             this.notify(url, 'subtitle');
                        } else if (url.includes('videoplayback') || url.includes('manifest')) {
                             this.notify(url, 'video');
                        }
                    },

                    initMutationObserver: function() {
                        const observer = new MutationObserver((mutations) => {
                            mutations.forEach((mutation) => {
                                if (mutation.type === 'childList') {
                                    mutation.addedNodes.forEach((node) => {
                                        if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                                            if (node.src) this.checkUrl(node.src, node.nodeName.toLowerCase());
                                            if (node.querySelectorAll) {
                                                node.querySelectorAll('source').forEach(src => this.checkUrl(src.src, node.nodeName.toLowerCase()));
                                            }
                                        }
                                    });
                                } else if (mutation.type === 'attributes' && (mutation.attributeName === 'src' || mutation.attributeName === 'href')) {
                                     const node = mutation.target;
                                     if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO' || node.nodeName === 'SOURCE') {
                                         const type = (node.closest('audio') || node.nodeName === 'AUDIO') ? 'audio' : 'video';
                                         this.checkUrl(node.src, type);
                                     }
                                }
                            });
                        });
                        observer.observe(document, { childList: true, subtree: true, attributes: true });
                    },

                    initNetworkInterceptors: function() {
                        const originalOpen = XMLHttpRequest.prototype.open;
                        const self = this;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            if (typeof url === 'string') {
                                self.checkUrl(url, 'unknown');
                            }
                            return originalOpen.apply(this, arguments);
                        };

                        const originalFetch = window.fetch;
                        window.fetch = function(input, init) {
                            let url;
                            if (typeof input === 'string') {
                                url = input;
                            } else if (input instanceof Request) {
                                url = input.url;
                            }
                            if (url) {
                                self.checkUrl(url, 'unknown');
                            }
                            return originalFetch.apply(this, arguments);
                        };
                    },

                    scanBase64Iframes: function() {
                        const selects = document.querySelectorAll('select.mirror, select[name="mirror"]');
                        selects.forEach(select => {
                            if (select.dataset.scanned) return;
                            select.dataset.scanned = "true";
                            if (select.value && window.AndroidMediaState && window.AndroidMediaState.onBase64IframeFound) {
                                window.AndroidMediaState.onBase64IframeFound(select.value);
                            }
                            select.addEventListener('change', (e) => {
                                if (e.target.value && window.AndroidMediaState && window.AndroidMediaState.onBase64IframeFound) {
                                    window.AndroidMediaState.onBase64IframeFound(e.target.value);
                                }
                            });
                        });
                    },

                    scanDOM: function() {
                        document.querySelectorAll('video, audio, source').forEach(el => {
                            if (el.src) {
                                const type = (el.closest('audio') || el.nodeName === 'AUDIO') ? 'audio' : 'video';
                                this.checkUrl(el.src, type);
                            }
                        });
                        this.scanBase64Iframes();
                    },

                    init: function() {
                        this.scanDOM();
                        this.initMutationObserver();
                        this.initNetworkInterceptors();
                    }
                };

                window.AndroidMediaDetector = detector;
                detector.init();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

private fun injectMediaStateDetector() {
    val script = """
        javascript:(function() {
            'use strict';
            if (window.AndroidMediaController) return;

            const controller = {
                lastInformedState: {},
                mediaElement: null,
                targetDocument: document,

                // Helper to find the best button from a list of selectors
                _findButton: function(selectors) {
                    for (const selector of selectors) {
                        try {
                            const button = this.targetDocument.querySelector(selector);
                            // The strict visibility check (offsetParent) was removed as it was too aggressive for some sites.
                            if (button) {
                                return button;
                            }
                        } catch (e) { /* Invalid selector, ignore */ }
                    }
                    return null;
                },

                findActiveMedia: function(doc) {
                    let allMedia = Array.from(doc.querySelectorAll('video, audio'));
                    let activeMedia = allMedia.find(m => !m.paused && m.currentTime > 0 && !m.muted && m.volume > 0);
                    if (activeMedia) return { media: activeMedia, doc: doc };
                    let longestMedia = allMedia.filter(m => m.duration > 0).sort((a, b) => b.duration - a.duration)[0];
                    if (longestMedia) return { media: longestMedia, doc: doc };
                    return allMedia.length > 0 ? { media: allMedia[0], doc: doc } : null;
                },

                findMediaElementInFrames: function() {
                    let mediaInfo = this.findActiveMedia(document);
                    if (mediaInfo) return mediaInfo;

                    try {
                        for (const frame of window.frames) {
                            if (frame.document) {
                                mediaInfo = this.findActiveMedia(frame.document);
                                if (mediaInfo) return mediaInfo;
                            }
                        }
                    } catch (e) { /* cross-origin frame error */ }
                    return null;
                },

                collectState: function() {
                    let mediaInfo = this.findMediaElementInFrames();
                    if (mediaInfo) {
                        this.mediaElement = mediaInfo.media;
                        this.targetDocument = mediaInfo.doc;
                    } else {
                        if (this.lastInformedState.isPlaying) {
                            AndroidMediaState.updateMediaPlaybackState(document.title, false, 0, 0, false, false);
                        }
                        this.lastInformedState = { isPlaying: false };
                        return;
                    }

                    const hasNextButton = !!this._findButton([
                        '[data-testid*="next" i]',
                        '[data-e2e="next-button"]',
                        'button[class*="next" i]',
                        '[aria-label="Playbar: Next Song button"]',
                        '[aria-label*="Next Song" i]',
                        '[aria-label*="Next track" i]',
                        '[aria-label*="Next Video" i]',
                        '[aria-label*="Skip" i]:not([aria-label*="Ad" i])',
                        '[title*="Next" i]:not([title*="Page" i])',
                        '[aria-label*="Next" i]:not([aria-label*="Page" i])'
                    ]);
                    const hasPreviousButton = !!this._findButton([
                        '[data-testid*="previous" i]',
                        '[data-e2e="previous-button"]',
                        'button[class*="previous" i]',
                        '[aria-label="Playbar: Previous Song button"]',
                        '[aria-label*="Previous Song" i]',
                        '[aria-label*="Previous track" i]',
                        '[aria-label*="Previous Video" i]',
                        '[aria-label*="replay" i]',
                        '[title*="Previous" i]:not([title*="Page" i])',
                        '[aria-label*="Previous" i]:not([aria-label*="Page" i])'
                    ]);

                    const currentState = {
                        title: this.targetDocument.title || document.title,
                        isPlaying: !this.mediaElement.paused,
                        currentTime: this.mediaElement.currentTime || 0,
                        duration: this.mediaElement.duration || 0,
                        hasNext: hasNextButton,
                        hasPrevious: hasPreviousButton
                    };

                    if (JSON.stringify(currentState) !== JSON.stringify(this.lastInformedState)) {
                        AndroidMediaState.updateMediaPlaybackState(
                            currentState.title, currentState.isPlaying, currentState.currentTime,
                            currentState.duration, currentState.hasNext, currentState.hasPrevious
                        );
                        if (this.mediaElement) {
                            let vidSrc = this.mediaElement.currentSrc || this.mediaElement.src;
                            if (vidSrc) {
                                if (this.mediaElement.duration > 0 && this.mediaElement.duration < 45) {
                                    console.log("Ignoring short video on auto-found (likely ad): " + this.mediaElement.duration + "s");
                                } else if (this.mediaElement.videoWidth && this.mediaElement.videoWidth < 100) {
                                    console.log("Ignoring tiny video on auto-found (likely ad).");
                                } else {
                                    AndroidMediaState.onVideoFound(vidSrc);
                                }
                            }
                        }
                        this.lastInformedState = currentState;
                    }
                },

                play: function() {
                    if (this.mediaElement && this.mediaElement.paused) {
                        this.mediaElement.play().catch(e => console.error("Play failed", e));
                    }
                },
                pause: function() {
                    if (this.mediaElement && !this.mediaElement.paused) {
                        this.mediaElement.pause();
                    }
                },
                next: function() {
                    const nextButton = this._findButton([
                        '[data-testid*="next" i]',
                        '[data-e2e="next-button"]',
                        'button[class*="next" i]',
                        '[aria-label="Playbar: Next Song button"]',
                        '[aria-label*="Next Song" i]',
                        '[aria-label*="Next track" i]',
                        '[aria-label*="Next Video" i]',
                        '[aria-label*="Skip" i]:not([aria-label*="Ad" i])',
                        '[title*="Next" i]:not([title*="Page" i])',
                        '[aria-label*="Next" i]:not([aria-label*="Page" i])'
                    ]);
                    if (nextButton) {
                        nextButton.click();
                    } else if (this.mediaElement) {
                        this.mediaElement.currentTime = Math.min(this.mediaElement.currentTime + 10, this.mediaElement.duration);
                    }
                },
                previous: function() {
                    const prevButton = this._findButton([
                        '[data-testid*="previous" i]',
                        '[data-e2e="previous-button"]',
                        'button[class*="previous" i]',
                        '[aria-label="Playbar: Previous Song button"]',
                        '[aria-label*="Previous Song" i]',
                        '[aria-label*="Previous track" i]',
                        '[aria-label*="Previous Video" i]',
                        '[aria-label*="replay" i]',
                        '[title*="Previous" i]:not([title*="Page" i])',
                        '[aria-label*="Previous" i]:not([aria-label*="Page" i])'
                    ]);
                    if (prevButton) {
                        prevButton.click();
                    } else if (this.mediaElement) {
                        this.mediaElement.currentTime = Math.max(this.mediaElement.currentTime - 10, 0);
                    }
                },

                init: function() {
                    setInterval(() => this.collectState(), 1000);
                },
                downloadActiveMedia: function() {
                    if (this.mediaElement) {
                        let src = this.mediaElement.currentSrc || this.mediaElement.src;
                        if (!src && this.mediaElement.querySelector('source')) {
                            src = this.mediaElement.querySelector('source').src;
                        }

                        // If it's a blob URL, we should intercept and ask Android for the real stream URL.
                        if (src && src.startsWith('blob:')) {
                            if (window.AndroidMediaState && window.AndroidMediaState.getCurrentVideoUrl) {
                                let upstreamUrl = window.AndroidMediaState.getCurrentVideoUrl();
                                if (upstreamUrl && !upstreamUrl.startsWith('blob:')) {
                                    src = upstreamUrl;
                                }
                            }
                        }

                        // Extract subtitle track if available
                        let subtitleUrl = "";
                        let tracks = this.mediaElement.querySelectorAll('track');
                        for (let i = 0; i < tracks.length; i++) {
                            if (tracks[i].src && (tracks[i].kind === 'subtitles' || tracks[i].kind === 'captions' || !tracks[i].kind)) {
                                subtitleUrl = tracks[i].src;
                                break;
                            }
                        }

                        // If no direct track, check if window.AndroidMediaState.lastDetectedSubtitle exists (from main detector)
                        if (!subtitleUrl && window.AndroidMediaState && window.AndroidMediaState.getLastDetectedSubtitle) {
                            subtitleUrl = window.AndroidMediaState.getLastDetectedSubtitle();
                        }

                        if (src) {
                            // Smart checks to ignore obvious pre-roll or hidden ads
                            if (this.mediaElement.duration > 0 && this.mediaElement.duration < 45) {
                                console.log("Ignoring short video (likely ad): " + this.mediaElement.duration + "s");
                                return;
                            }
                            if (this.mediaElement.videoWidth && this.mediaElement.videoWidth < 100) {
                                console.log("Ignoring tiny video (likely ad): " + this.mediaElement.videoWidth + "px wide");
                                return;
                            }

                            let type = this.mediaElement.tagName.toLowerCase();
                            let title = this.targetDocument.title || document.title;
                            if (window.AndroidMediaState && window.AndroidMediaState.onDownloadActiveMedia) {
                                window.AndroidMediaState.onDownloadActiveMedia(src, type, title, subtitleUrl);
                            }
                        } else {
                            console.error("No active media source found.");
                        }
                    }
                }
            };

            window.AndroidMediaController = controller;
            controller.init();
        })();
    """.trimIndent()
    webView.evaluateJavascript(script, null)
}

    private fun startOrUpdatePlaybackService() {
        val title = currentMediaTitle.takeIf { !it.isNullOrBlank() } ?: webView.title ?: "Web Media"
        val intent = Intent(this, MediaForegroundService::class.java).apply {
            putExtra(MediaForegroundService.EXTRA_TITLE, title)
            putExtra(MediaForegroundService.EXTRA_IS_PLAYING, isMediaPlaying)
            putExtra(MediaForegroundService.EXTRA_CURRENT_POSITION, currentPosition)
            putExtra(MediaForegroundService.EXTRA_DURATION, (duration * 1000).toLong())
            putExtra(MediaForegroundService.EXTRA_HAS_NEXT, hasNextMedia)
            putExtra(MediaForegroundService.EXTRA_HAS_PREVIOUS, hasPreviousMedia)
        }

        try {
            if (!hasStartedForegroundService) {
                ContextCompat.startForegroundService(this, intent)
                hasStartedForegroundService = true
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting foreground service: ${e.message}", e)
        }
    }

    fun stopPlaybackService() {
        if (hasStartedForegroundService) {
            val serviceIntent = Intent(this, MediaForegroundService::class.java)
            stopService(serviceIntent)
            hasStartedForegroundService = false
        }
    }
    private fun resumeMediaPlayback() {
        val resumeScript = "javascript:(function() { var video = document.querySelector('video'); if (video && video.paused && video.hasAttribute('data-was-playing')) { video.play().catch(function(e) {}); } })();"
        webView.loadUrl(resumeScript)
    }
    inner class MediaStateInterface(private val activity: MainActivity) {

        @JavascriptInterface
        fun onPlayerTelemetry(jsonStr: String) {
            try {
                val jsonArray = org.json.JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val url = obj.getString("src")
                    if (url.isNullOrEmpty() || url.startsWith("blob:")) continue

                    val telemetry = PlayerTelemetry(
                        url = url,
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0),
                        durationSec = obj.optDouble("duration", 0.0),
                        currentTimeSec = obj.optDouble("currentTime", 0.0),
                        paused = obj.optBoolean("paused", true),
                        muted = obj.optBoolean("muted", false),
                        autoplay = obj.optBoolean("autoplay", false),
                        fullscreen = obj.optBoolean("fullscreen", false),
                        visibleAreaRatio = obj.optDouble("visibleAreaRatio", 0.0),
                        displayedWidth = obj.optDouble("displayedWidth", 0.0),
                        displayedHeight = obj.optDouble("displayedHeight", 0.0)
                    )

                    activity.runOnUiThread {
                        activity.mediaEngine.updatePlayerTelemetry(url, telemetry)
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }

        private var lastSubtitleUrl: String = ""


        @JavascriptInterface
        fun onMSEActivityDetected(url: String) {
            activity.runOnUiThread {
                activity.mediaEngine.updateCandidateMSEActivity(url)
            }
        }

        @JavascriptInterface
        fun onDRMDetected(url: String) {
            activity.runOnUiThread {
                activity.mediaEngine.markCandidateDRM(url)
            }
        }

                @JavascriptInterface
        fun onApiSniffed(url: String, type: String) {
            val fullUrl = if (url.startsWith("/")) {
                val base = activity.webView.url
                if (base != null) java.net.URI(base).resolve(url).toString() else url
            } else {
                url
            }

            val logEntry = "[$type] $fullUrl"
            if (!activity.sniffedApiLogs.contains(logEntry)) {
                activity.sniffedApiLogs.add(0, logEntry) // add to top
            }
        }

        fun clearState() {
            lastSubtitleUrl = ""
            synchronized(processedIframes) {
                processedIframes.clear()
            }
        }

        @JavascriptInterface
        fun parseHtmlForHiddenM3u8(htmlSource: String) {
            try {
                // 1. Check for the MacCMS player_aaaa JSON variable
                val macCmsRegex = """"url"\s*:\s*"(https?:\\\\/\\\\/[^"]+\.m3u8[^"]*)"""".toRegex()
                val macCmsMatch = macCmsRegex.find(htmlSource)
                if (macCmsMatch != null) {
                    val rawUrl = macCmsMatch.groupValues[1]
                    // Unescape the URL
                    val cleanUrl = rawUrl.replace("\\/", "/")
                    onMediaDetected(cleanUrl, "application/x-mpegURL")
                }

                // 2. Fallback: Check for iframe src with a ?url= parameter containing m3u8
                val iframeRegex = """<iframe[^>]+src=["']([^"']*?\?url=(https?://[^"']+\.m3u8[^"']*?)?)["']""".toRegex(RegexOption.IGNORE_CASE)
                val iframeMatch = iframeRegex.find(htmlSource)
                if (iframeMatch != null && iframeMatch.groupValues.size >= 3) {
                    val cleanIframeUrl = iframeMatch.groupValues[2]
                    onMediaDetected(cleanIframeUrl, "application/x-mpegURL")
                }

                // Fallback for simple iframe src
                val iframeSrcRegex = """<iframe[^>]+src=["'](https?://[^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                iframeSrcRegex.findAll(htmlSource).forEach { matchResult ->
                    val url = matchResult.groupValues[1]
                    if (url.contains(".m3u8", ignoreCase = true) || url.contains("?url=", ignoreCase = true) || url.contains("player", ignoreCase = true)) {
                        activity.runOnUiThread { IframeSniffer(activity) { sniffedUrl -> onMediaDetected(sniffedUrl, "video") }.sniff(url) }
                    }
                }

                // 3. Fallback: Check for base64 encoded data-play attributes on iframes
                val dataPlayRegex = """data-play=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                dataPlayRegex.findAll(htmlSource).forEach { matchResult ->
                    val originalEncoded = matchResult.groupValues[1].trim()

                    if (originalEncoded.isNotEmpty()) {
                        // Sometimes strings are prepended with random characters like "WZna" where "aHR0" is the start of "http".
                        // Let's try decoding from different starting offsets (up to 5 characters)
                        for (i in 0..5) {
                            if (i >= originalEncoded.length) break
                            var encoded = originalEncoded.substring(i)
                            val padding = (4 - encoded.length % 4) % 4
                            encoded += "=".repeat(padding)
                            try {
                                val decodedUrl = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                                if (decodedUrl.startsWith("http")) {
                                    activity.runOnUiThread { IframeSniffer(activity) { sniffedUrl -> onMediaDetected(sniffedUrl, "video") }.sniff(decodedUrl) }
                                    break // Stop trying offsets if we found a valid URL
                                }
                            } catch (e: Exception) {
                                // Ignore decode exceptions and try next offset
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaStateInterface", "Error parsing HTML for hidden m3u8: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onBase64IframeFound(base64Str: String) {
            try {
                val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                val decodedString = String(decodedBytes)
                // Match <iframe src="URL" ...>
                val regex = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"'][^>]*>")
                val match = regex.find(decodedString)
                if (match != null) {
                    val iframeUrl = match.groupValues[1]
                    activity.runOnUiThread {
                        IframeSniffer(activity) { url ->
                            activity.runOnUiThread {
                                try {
                                    val category = MediaCategory.fromUrl(url)
                                    val isMainContent = isMainVideoContent(url)
                                    if (category == MediaCategory.VIDEO && isMainContent) {
                                        currentVideoUrl = url
                                    }
                                    val detectedFormat = activity.detectVideoFormat(url)

                                    val isVariantOfExistingMaster = synchronized(activity.detectedMediaFiles) {
                                        activity.detectedMediaFiles.any {
                                            it.url != url &&
                                            (it.mimeType?.contains("mpegurl", ignoreCase = true) == true || it.url.contains(".m3u8", ignoreCase = true)) &&
                                            it.url.substringBeforeLast("/") == url.substringBeforeLast("/")
                                        }
                                    }

                                    if (isVariantOfExistingMaster && (detectedFormat.mimeType?.contains("mpegurl", ignoreCase = true) == true || url.contains(".m3u8", ignoreCase = true))) {
                                        return@runOnUiThread // Skip adding variant playlist to the UI list
                                    }

                                    val quality = activity.extractQualityFromUrl(url)

                                    val ext = if (category == MediaCategory.AUDIO) {
                                        if (url.contains(".m4a", ignoreCase = true)) ".m4a"
                                        else if (url.contains(".mp3", ignoreCase = true)) ".mp3"
                                        else if (url.contains(".aac", ignoreCase = true)) ".aac"
                                        else if (url.contains(".ogg", ignoreCase = true)) ".ogg"
                                        else if (url.contains(".wav", ignoreCase = true)) ".wav"
                                        else ".m4a"
                                    } else detectedFormat.extension

                                    val finalMime = if (category == MediaCategory.AUDIO) "audio/*" else detectedFormat.mimeType

                                    val enhancedTitle = activity.generateSmartFileName(url, ext, quality, category)
                                    val fileSize = activity.estimateFileSize(url, category)
                                    val language = extractLanguageFromUrl(url)
                                    val finalTitle = if (category == MediaCategory.SUBTITLE) SubtitleUtils.originalSubtitleFilename(url) ?: enhancedTitle else enhancedTitle
                                    val mediaFile = MediaFile(
                                        url = url,
                                        title = finalTitle,
                                        mimeType = finalMime,
                                        quality = quality,
                                        category = category,
                                        fileSize = fileSize,
                                        language = language,
                                        isMainContent = isMainContent
                                    )
                                    val existsAlready = synchronized(detectedMediaFiles) {
                                        detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(url) }
                                    }
                                    if (!existsAlready) {
                                        synchronized(detectedMediaFiles) {
                                            detectedMediaFiles.add(mediaFile)
                                        }
                                        updateFabVisibility()
                                        if (category == MediaCategory.SUBTITLE) {
                                            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                                    if (prefs.getBoolean("AUTO_ANALYZE_MEDIA", false)) {
                                        fetchSubtitleSnippet(mediaFile)
                                    }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Error processing sniffed media URL: ${e.message}")
                                }
                            }
                        }.sniff(iframeUrl)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaStateInterface", "Error decoding Base64 iframe: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onSubtitleBlobExtracted(content: String, originalUrl: String, language: String?, label: String?) {
            if (content.isBlank()) return

            // Limit payload size to 2MB to prevent OOM from malicious blobs masquerading as subtitles
            val maxBytes = 2 * 1024 * 1024
            if (content.toByteArray().size > maxBytes) {
                android.util.Log.e("SUBTITLE_BLOB", "Subtitle blob rejected: exceeds 2MB limit.")
                return
            }

            activity.runOnUiThread {
                activity.handleExtractedSubtitleBlob(content, originalUrl, language ?: "und", label ?: "Subtitle")
            }
        }

        @JavascriptInterface
        fun getLastDetectedSubtitle(): String {
            return lastSubtitleUrl
        }

        @JavascriptInterface
        fun getCurrentVideoUrl(): String {
            return activity.currentVideoUrl ?: ""
        }

        private val processedIframes = mutableSetOf<String>()

        @JavascriptInterface
        fun onIframeFound(iframeUrl: String) {
            val trimmedUrl = iframeUrl.trim()
            if (trimmedUrl.isEmpty() || trimmedUrl == "about:blank" || trimmedUrl.startsWith("data:")) return

            // Normalize URL to prevent redundant hidden WebViews
            val normalizedUrl = try {
                val uri = java.net.URI(trimmedUrl)
                if (!uri.isAbsolute && activity.webView.url != null) {
                    java.net.URI(activity.webView.url).resolve(uri).toString()
                } else {
                    trimmedUrl
                }
            } catch (e: Exception) { trimmedUrl }

            synchronized(processedIframes) {
                if (processedIframes.contains(normalizedUrl)) return
                processedIframes.add(normalizedUrl)
            }

            android.util.Log.d("MediaStateInterface", "Discovered potential cross-origin player iframe: $normalizedUrl")

            // Route the iframe URL cleanly into the existing IframeSniffer on the UI thread
            activity.runOnUiThread {
                IframeSniffer(activity) { sniffedMediaUrl ->
                    onMediaDetected(sniffedMediaUrl, "video")
                }.sniff(normalizedUrl)
            }
        }

        @JavascriptInterface
        fun onActiveMediaFound(url: String, type: String, isBlob: Boolean) {
            activity.runOnUiThread {
                var bestPlayable = activity.mediaEngine.getBestCandidate()

                // If it's not a blob and we have a direct active URL, let's process it heavily
                if (!isBlob && url.isNotEmpty() && !url.startsWith("data:")) {
                    activity.mediaEngine.markCandidateAsActivePlayer(url, 0)
                    val activeCand = activity.mediaEngine.candidates[url]
                    if (activeCand != null) {
                        activeCand.playbackScore += 100 // Absolute highest priority
                        activeCand.referer = activity.lastUsedUrl
                        activeCand.userAgent = activity.cachedUserAgent
                        bestPlayable = activity.mediaEngine.getBestCandidate()
                    }
                } else if (isBlob) {
                     // If it IS a blob, we just trust the MediaDetectionEngine to have mapped the blob active state to the manifest
                     bestPlayable = activity.mediaEngine.getBestCandidate()
                }

                if (bestPlayable != null) {
                    // Update detection list with highest priority
                    val detectedFormat = activity.detectVideoFormat(bestPlayable.url)
                    val title = activity.generateSmartFileName(bestPlayable.url, detectedFormat.extension, activity.extractQualityFromUrl(bestPlayable.url), MediaCategory.VIDEO)

                    val mediaFile = MediaFile(
                        url = bestPlayable.url,
                        title = title + " (Active)",
                        mimeType = detectedFormat.mimeType,
                        quality = activity.extractQualityFromUrl(bestPlayable.url),
                        category = MediaCategory.VIDEO,
                        fileSize = "Unknown",
                        language = null,
                        isMainContent = true,
                        referer = activity.lastUsedUrl
                    )

                    synchronized(activity.detectedMediaFiles) {
                        activity.detectedMediaFiles.removeIf { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(mediaFile.url) }
                        activity.detectedMediaFiles.add(0, mediaFile)
                    }
                    activity.updateFabVisibility()
                    activity.currentMediaListAdapter?.notifyDataSetChanged()

                    android.widget.Toast.makeText(activity, "Active media found! Check the download list.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(activity, "No active media could be confidently detected.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun onActivePlayerFound(url: String, durationStr: String?) {
            if (url.startsWith("blob:") || mediaEngine.isAdUrl(url)) return

            activity.runOnUiThread {
                try {
                    val duration = durationStr?.toDoubleOrNull()?.toInt() ?: 0
                    activity.mediaEngine.markCandidateAsActivePlayer(url, duration)
                } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun onMediaDetectedWithHeaders(url: String, type: String, contentType: String?) {
            if (url.startsWith("blob:")) return
            if (isSubtitleMediaUrl(url, type, contentType)) {
                lastSubtitleUrl = url
            }
            activity.runOnUiThread {
                activity.mediaEngine.processRequest(url, activity.lastUsedUrl, activity.cachedUserAgent, contentType)
                handleIncomingMediaDetection(url, type, contentType)
            }
        }

        @JavascriptInterface
        fun onMediaDetected(url: String, type: String) {
            onMediaDetectedWithHeaders(url, type, null)
        }



    private fun isSubtitleMediaUrl(url: String, type: String, contentType: String? = null): Boolean {
            if (type.contains("subtitle", ignoreCase = true)) return true
            val clean = url.substringBefore("#")
            val path = runCatching { java.net.URL(clean).path.lowercase() }.getOrDefault(clean.substringBefore("?").lowercase())
            if (listOf(".vtt", ".srt", ".ttml", ".dfxp", ".srv1", ".srv2", ".srv3").any { path.endsWith(it) }) return true
            val mime = contentType?.substringBefore(";")?.trim()?.lowercase().orEmpty()
            if (mime in setOf("text/vtt", "application/x-subrip", "application/ttml+xml", "application/dfxp+xml")) return true
            return runCatching {
                val uri = java.net.URL(clean)
                val host = uri.host.lowercase()
                val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
                    host == "googlevideo.com" || host.endsWith(".googlevideo.com")
                isYouTubeHost && (uri.path.contains("/api/timedtext", ignoreCase = true) ||
                    uri.query.orEmpty().split("&").any { it.substringBefore("=").lowercase() == "fmt" })
            }.getOrDefault(false)
        }

        private fun handleIncomingMediaDetection(url: String, type: String, contentType: String? = null) {
            if (url.startsWith("blob:")) return
            if (type.contains("subtitle") || url.endsWith(".vtt") || url.endsWith(".srt")) {
                lastSubtitleUrl = url
            }
            // Already on UI thread from onMediaDetectedWithHeaders
                try {
                    if (url.isNotEmpty() && url != "about:blank" && !url.startsWith("data:") && !url.startsWith("blob:")) {
                         // Prevent UI flooding: Do not add an HLS variant playlist if a master playlist from the same host/path is already present.
                         val isVariantOfExistingMaster = synchronized(activity.detectedMediaFiles) {
                             activity.detectedMediaFiles.any {
                                 it.url != url &&
                                 (it.mimeType?.contains("mpegurl", ignoreCase = true) == true || it.url.contains(".m3u8", ignoreCase = true)) &&
                                 it.url.substringBeforeLast("/") == url.substringBeforeLast("/")
                             }
                         }

                         if (isVariantOfExistingMaster && (contentType?.contains("mpegurl", ignoreCase = true) == true || url.contains(".m3u8", ignoreCase = true))) {
                             return // Skip adding variant playlist to the UI list to force the user to pick the Master or use the FAB
                         }

                         val existsAlready = synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(url) }
                         }

                         if (!existsAlready) {
                             val category = if (isSubtitleMediaUrl(url, type, contentType)) {
                                 MediaCategory.SUBTITLE
                             } else if (type.contains("audio", true)) {
                                 MediaCategory.AUDIO
                             } else {
                                 MediaCategory.VIDEO
                             }

                             val detectedFormat = activity.detectVideoFormat(url)
                             val quality = activity.extractQualityFromUrl(url)

                             val ext = if (category == MediaCategory.AUDIO) {
                                 if (url.contains(".m4a", ignoreCase = true)) ".m4a"
                                 else if (url.contains(".mp3", ignoreCase = true)) ".mp3"
                                 else if (url.contains(".aac", ignoreCase = true)) ".aac"
                                 else if (url.contains(".ogg", ignoreCase = true)) ".ogg"
                                 else if (url.contains(".wav", ignoreCase = true)) ".wav"
                                 else ".m4a"
                             } else if (category == MediaCategory.SUBTITLE) ".vtt" else detectedFormat.extension

                             val finalMime = if (category == MediaCategory.AUDIO) "audio/*" else if (category == MediaCategory.SUBTITLE) "text/vtt" else detectedFormat.mimeType

                             val enhancedTitle = activity.generateSmartFileName(url, ext, quality, category)

                             val finalTitle = if (category == MediaCategory.SUBTITLE) SubtitleUtils.originalSubtitleFilename(url) ?: enhancedTitle else enhancedTitle
                             val mediaFile = MediaFile(
                                url = url,
                                title = finalTitle,
                                mimeType = finalMime,
                                quality = quality,
                                category = category,
                                fileSize = "Unknown",
                                language = null,
                                isMainContent = isMainVideoContent(url),
                                referer = activity.lastUsedUrl
                            )

                            synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.add(0, mediaFile)
                            }
                            activity.updateFabVisibility()
                            android.util.Log.d("MediaStateInterface", "Advanced detection found: $url")

                         }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaStateInterface", "Error in onMediaDetected: ${e.message}")
                }
        }

        @JavascriptInterface
        fun onDownloadActiveMedia(url: String, type: String, title: String) {
            onDownloadActiveMedia(url, type, title, "")
        }

        @JavascriptInterface
        fun onDownloadActiveMedia(url: String, type: String, title: String, subtitleUrl: String) {
            activity.runOnUiThread {
                var finalUrl = url
                if (url.startsWith("blob:")) {
                    val bestCand = activity.mediaEngine.getBestCandidate()
                    if (bestCand != null && !bestCand.url.startsWith("blob:")) {
                        finalUrl = bestCand.url
                    } else {
                        val mainMedia = synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.firstOrNull { it.isMainContent && !it.url.startsWith("blob:") }
                                ?: activity.detectedMediaFiles.firstOrNull { !it.url.startsWith("blob:") && it.category == MediaCategory.VIDEO }
                        }
                        if (mainMedia != null) {
                            finalUrl = mainMedia.url
                        }
                    }
                }

                if (finalUrl.isNotEmpty() && finalUrl != "about:blank" && !finalUrl.startsWith("data:") && !finalUrl.startsWith("blob:")) {
                    val category = if (isSubtitleMediaUrl(finalUrl, type)) {
                        MediaCategory.SUBTITLE
                    } else if (type.contains("audio", true)) {
                        MediaCategory.AUDIO
                    } else {
                        MediaCategory.VIDEO
                    }
                    val detectedFormat = activity.detectVideoFormat(finalUrl)
                    val quality = activity.extractQualityFromUrl(finalUrl)
                    val subtitle = isSubtitleMediaUrl(finalUrl, type)
                    val ext = if (subtitle) ".vtt" else detectedFormat.extension
                    val enhancedTitle = activity.generateSmartFileName(finalUrl, ext, quality, category)
                    val finalTitle = if (subtitle) SubtitleUtils.originalSubtitleFilename(finalUrl) ?: enhancedTitle else enhancedTitle
                    val mediaFile = MediaFile(
                        url = finalUrl,
                        title = finalTitle,
                        mimeType = detectedFormat.mimeType,
                        quality = quality,
                        category = category,
                        fileSize = "Unknown",
                        language = null,
                        isMainContent = true
                    )
                    val options = arrayOf("Download (Uses Data)", "Play in App (Saves Data Cache)")
                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("Video Action")
                        .setItems(options) { _, which ->
                            val input = android.widget.EditText(activity)
                            input.setText(mediaFile.title.substringBeforeLast("."))
                            androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setTitle("Rename File")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val newTitle = input.text.toString() + "." + mediaFile.title.substringAfterLast(".", "")
                                    val updatedMediaFile = mediaFile.copy(title = newTitle)
                                    when (which) {
                                        0 -> {
                                            Toast.makeText(activity, "Downloading Active Video...", Toast.LENGTH_SHORT).show()
                                            activity.downloadMediaFile(updatedMediaFile)
                                        }
                                        1 -> {
                                            val bestCandidate = activity.mediaEngine.getBestCandidate()
                                            activity.mediaEngine.logCandidatesState()
                                            if (bestCandidate != null && (bestCandidate.confidence == "HIGH" || bestCandidate.confidence == "MEDIUM")) {
                                                activity.launchPlayerWithCandidate(bestCandidate, newTitle, activity.lastUsedUrl?.toString())
                                            } else {
                                                // Start analyzing dialog
                                                val pd = android.app.ProgressDialog(activity).apply {
                                                    setMessage("Analyzing video... please wait.")
                                                    setCancelable(true)
                                                    show()
                                                }
                                                var elapsed = 0
                                                val maxWait = 4000 // wait max 4 seconds
                                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                                val checkRunnable = object : Runnable {
                                                    override fun run() {
                                                        val candidate = activity.mediaEngine.getBestCandidate()
                                                        if (candidate != null && !candidate.url.startsWith("blob:") && candidate.confidence == "HIGH") {
                                                            pd.dismiss()
                                                            activity.launchPlayerWithCandidate(candidate, newTitle, activity.lastUsedUrl?.toString())
                                                            return
                                                        }
                                                        elapsed += 500
                                                        if (elapsed >= maxWait) {
                                                            pd.dismiss()
                                                            val fallbackCand = activity.mediaEngine.getBestCandidate()
                                                            if (fallbackCand != null) {
                                                                activity.launchPlayerWithCandidate(fallbackCand, newTitle, activity.lastUsedUrl?.toString())
                                                            } else {
                                                                // Legacy fallback
                                                                if (url.startsWith("blob:")) {
                                                                    if (activity.currentVideoUrl != null && !activity.currentVideoUrl!!.startsWith("blob:")) {
                                                                        activity.launchLegacyPlayer(activity.currentVideoUrl!!, newTitle, activity.lastUsedUrl?.toString())
                                                                    } else {
                                                                        Toast.makeText(activity, "Cannot play Blob URLs directly. Please wait for the real video stream to be detected.", Toast.LENGTH_LONG).show()
                                                                    }
                                                                } else {
                                                                    activity.launchLegacyPlayer(url, newTitle, activity.lastUsedUrl?.toString())
                                                                }
                                                            }
                                                            return
                                                        }
                                                        handler.postDelayed(this, 500)
                                                    }
                                                }
                                                handler.postDelayed(checkRunnable, 500)
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .show()
                } else {
                    Toast.makeText(activity, "Cannot download this media source (it may be a blob or hidden).", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun updateMediaPlaybackState(
            title: String,
            isPlaying: Boolean,
            currentPosition: Double,
            duration: Double,
            hasNext: Boolean,
            hasPrevious: Boolean
        ) {
            activity.runOnUiThread {
                activity.currentMediaTitle = title
                activity.isMediaPlaying = isPlaying
                activity.currentPosition = (currentPosition * 1000).toLong()
                activity.duration = duration
                activity.hasNextMedia = hasNext
                activity.hasPreviousMedia = hasPrevious

                if (activity.hasStartedForegroundService || isPlaying) {
                    activity.startOrUpdatePlaybackService()
                }
            }
        }

        @JavascriptInterface
        fun onVideoFound(videoUrl: String) {
            if (videoUrl.startsWith("blob:")) {
                // If it's a blob, attempt to find and highlight the true stream from the existing sniffed list instead of creating a fake blob entry.
                activity.runOnUiThread {
                    synchronized(activity.detectedMediaFiles) {
                        val mainStream = activity.detectedMediaFiles.firstOrNull { it.category == MediaCategory.VIDEO && !it.url.startsWith("blob:") }
                        if (mainStream != null && !mainStream.isMainContent) {
                            mainStream.isMainContent = true
                            activity.currentVideoUrl = mainStream.url
                            activity.currentMediaListAdapter?.notifyDataSetChanged()
                        }
                    }
                }
                return
            }

            activity.runOnUiThread {
                if (videoUrl.isNotEmpty() && videoUrl != "about:blank") {
                    activity.currentVideoUrl = videoUrl
                    android.util.Log.d("MediaStateInterface", "Video found: $videoUrl")

                    var updated = false
                    synchronized(activity.detectedMediaFiles) {
                        val foundFile = activity.detectedMediaFiles.find { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(videoUrl) }
                        if (foundFile != null) {
                            if (!foundFile.isMainContent) {
                                foundFile.isMainContent = true
                                updated = true
                            }
                        } else {
                            // URL was not previously caught by shouldInterceptRequest but is playing, add it now
                            val cand = activity.mediaEngine.getCandidate(videoUrl)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (isEligible) {
                                activity.detectedMediaFiles.add(0, MediaFile(
                                    url = videoUrl, title = "Detected_Video_${System.currentTimeMillis()}",
                                    mimeType = "video/*", quality = "Auto", category = MediaCategory.VIDEO,
                                    fileSize = "Unknown", language = null, isMainContent = true,
                                    referer = activity.lastUsedUrl
                                ))
                                updated = true
                            }
                        }
                    }
                    if (updated) {
                        activity.currentMediaListAdapter?.notifyDataSetChanged()
                    }
                }
            }
        }
        @JavascriptInterface
        fun onMediaPlay() {
            activity.mediaEngine.isPlaybackActive = true
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaPlay called")
                activity.isMediaPlaying = true
                activity.startOrUpdatePlaybackService()
            }
        }

        @JavascriptInterface
        fun onMediaPause() {
            activity.mediaEngine.isPlaybackActive = false
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaPause called")
                activity.isMediaPlaying = false
                activity.startOrUpdatePlaybackService()
            }
        }

        @JavascriptInterface
        fun onMediaEnded() {
            activity.mediaEngine.isPlaybackActive = false
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaEnded called")
                activity.isMediaPlaying = false
                activity.currentVideoUrl = null
                activity.stopPlaybackService()
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            activity.runOnUiThread {
                android.util.Log.e("MediaStateInterface", "Media error: $error")
                activity.isMediaPlaying = false
                activity.currentVideoUrl = null
                activity.stopPlaybackService()
                Toast.makeText(activity, "Media playback error: $error", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    private fun loadLastUsedName() {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        lastUsedName = sharedPrefs.getString("LAST_FILENAME", "Video") ?: "Video"
    }
    private fun saveLastUsedName(name: String) {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("LAST_FILENAME", name).apply()
    }
    private fun detectVideoFormat(url: String): VideoFormat {
        val lowerUrl = url.lowercase()
        val cleanUrl = lowerUrl.substringBefore('?')
        return when {
            cleanUrl.endsWith(".m4a") -> VideoFormat(".m4a", "audio/mp4")
            cleanUrl.endsWith(".mp3") -> VideoFormat(".mp3", "audio/mpeg")
            cleanUrl.endsWith(".aac") -> VideoFormat(".aac", "audio/aac")
            cleanUrl.endsWith(".ogg") || cleanUrl.endsWith(".opus") -> VideoFormat(".ogg", "audio/ogg")
            cleanUrl.endsWith(".wav") -> VideoFormat(".wav", "audio/wav")
            cleanUrl.endsWith(".mp4") -> VideoFormat(".mp4", "video/mp4")
            cleanUrl.endsWith(".mkv") -> VideoFormat(".mkv", "video/x-matroska")
            cleanUrl.endsWith(".webm") -> VideoFormat(".webm", "video/webm")
            cleanUrl.endsWith(".avi") -> VideoFormat(".avi", "video/x-msvideo")
            cleanUrl.endsWith(".mov") -> VideoFormat(".mov", "video/quicktime")
            cleanUrl.endsWith(".flv") -> VideoFormat(".flv", "video/x-flv")
            lowerUrl.contains(".m3u8") -> VideoFormat(".m3u8", "application/vnd.apple.mpegurl")
            cleanUrl.endsWith(".m4v") -> VideoFormat(".m4v", "video/mp4")
            cleanUrl.endsWith(".vtt") -> VideoFormat(".vtt", "text/vtt")
            cleanUrl.endsWith(".srt") -> VideoFormat(".srt", "application/x-subrip")
            lowerUrl.contains("videoplayback") -> VideoFormat(".mp4", "video/mp4")
            else -> VideoFormat(".mp4", "video/mp4")
        }
    }
    private fun extractQualityFromUrl(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("2160p") || lowerUrl.contains("4k") -> "4K (2160p)"
            lowerUrl.contains("1440p") || lowerUrl.contains("2k") -> "2K (1440p)"
            lowerUrl.contains("1080p") || lowerUrl.contains("hd") -> "Full HD (1080p)"
            lowerUrl.contains("720p") -> "HD (720p)"
            lowerUrl.contains("480p") -> "SD (480p)"
            lowerUrl.contains("360p") -> "360p"
            lowerUrl.contains("240p") -> "240p"
            lowerUrl.contains("144p") -> "144p"
            else -> "Unknown Quality"
        }
    }
private fun generateSmartFileName(url: String, extension: String, quality: String, category: MediaCategory): String {
    val uri = Uri.parse(url)
    val lowerUrl = url.lowercase()
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())

    val categoryPrefix = when (category) {
        MediaCategory.VIDEO -> ""
        MediaCategory.AUDIO -> "Audio_"
        MediaCategory.SUBTITLE -> ""
        MediaCategory.THUMBNAIL -> "Thumb_"
        else -> "${category.displayName}_"
    }

    // Use the title snapshot maintained by WebChromeClient; never block a
    // download/detection thread waiting for the UI thread.
    val finalPageTitle = currentPageTitleSnapshot
        .replace(Regex("[^a-zA-Z0-9 -]"), "")
        .trim()
        .take(50)
        .takeIf { it.isNotBlank() }

    val fileNameFromUrl = uri.lastPathSegment?.substringBeforeLast("?")

    val baseName = when {
        !finalPageTitle.isNullOrBlank() && finalPageTitle.length > 3 -> {
            // Prioritize the Webpage Title (e.g. "Anime Episode 2") to make lists readable
            finalPageTitle.replace(" ", "_")
        }
        !fileNameFromUrl.isNullOrBlank() && fileNameFromUrl.contains('.') && fileNameFromUrl.length > 5 -> {
            fileNameFromUrl.substringBeforeLast('.')
        }
        lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> {
            "YouTube_${extractYouTubeVideoId(url)}"
        }
        else -> {
            val host = uri.host?.replace(".", "_")?.replace("www_", "") ?: "Media"
            "${host}_${timestamp}"
        }
    }

    val cleanQuality = if (category == MediaCategory.VIDEO && quality != "Unknown Quality") {
        "_${quality.replace("[^a-zA-Z0-9]".toRegex(), "_")}"
    } else {
        ""
    }

    return "${categoryPrefix}${baseName}${cleanQuality}${extension}"
}
    private fun estimateFileSize(url: String, category: MediaCategory): String { return "Unknown" }
    private fun extractLanguageFromUrl(url: String): String? =
        SubtitleUtils.extractTrustedLanguageFromUrl(url)?.let { SubtitleUtils.languageDisplayName(it) }
    private fun extractYouTubeVideoId(url: String): String {
        val patterns = listOf("(?<=watch\\?v=)[^&]+", "(?<=youtu.be/)[^?]+", "(?<=embed/)[^?]+", "(?<=v/)[^?]+]")
        for (pattern in patterns) {
            val match = Regex(pattern).find(url)
            if (match != null) return match.value.take(11)
        }
        return "UnknownVideo"
    }
    private fun addToBlockedList(url: String) {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val blockedUrls = sharedPrefs.getStringSet("BLOCKED_URLS", setOf())?.toMutableSet() ?: mutableSetOf()
        Uri.parse(url).host?.let { blockedUrls.add(it) }
        sharedPrefs.edit().putStringSet("BLOCKED_URLS", blockedUrls).apply()
    }
    private fun isAdDomain(url: String): Boolean {
        if (isUrlWhitelisted(url)) return false
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val path = uri.encodedPath?.lowercase().orEmpty()

        // Host matching cannot detect path-based endpoints such as youtube.com/pagead.
        val isKnownAdPath = (
            (host == "youtube.com" || host.endsWith(".youtube.com")) &&
                (path == "/pagead" || path.startsWith("/pagead/"))
        )
        return isKnownAdPath || suspiciousDomains.any { !it.contains("/") && host.contains(it) }
    }
    private fun isInBlockedList(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val blockedUrls = sharedPrefs.getStringSet("BLOCKED_URLS", setOf()) ?: setOf()
        return blockedUrls.any { blockedHost -> host == blockedHost || host.endsWith(".$blockedHost") }
    }
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }
    private fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val adKeywords = listOf(
            "vast", "preroll", "midroll", "postroll", "doubleclick", "googlesyndication",
            "adnxs", "adservice", "promo", "banner", "tracker", "analytics", "beacon",
            "ad.", "/ads/", "/ad/", "commercial", "sponsor", "pubmatic", "rubicon", "smartadserver"
        )
        return adKeywords.any { lowerUrl.contains(it) }
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        val cleanUrl = lower.substringBefore('?')
        if (isAdOrTrackingUrl(lower)) return false
        // CRITICAL FIX: Ignore raw YouTube chunk requests. They cause ExoPlayer "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
        // when passed as a main stream URL. YouTube is handled entirely by YoutubeExtractorHelper via onPageStarted.
        if (lower.contains("googlevideo.com/videoplayback")) return false

        val ext = cleanUrl.substringAfterLast('.', "")
        val nonMediaExtensions = setOf("css", "js", "gif", "jpg", "jpeg", "png", "svg", "webp", "woff", "woff2", "ttf", "ico", "html", "htm")
        if (nonMediaExtensions.contains(ext)) return false

        return cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".vtt") || cleanUrl.endsWith(".srt") || lower.contains("videoplayback") ||
               cleanUrl.endsWith(".mp3") || cleanUrl.endsWith(".m4a") || cleanUrl.endsWith(".aac") || cleanUrl.endsWith(".ogg") || cleanUrl.endsWith(".opus") || cleanUrl.endsWith(".wav")
    }
    private fun isAdOrTrackingUrl(url: String): Boolean {
        val adIndicators = listOf("googleads.", "doubleclick.net", "adsystem", "/ads/")
        return adIndicators.any { url.contains(it) }
    }
    private fun isMainVideoContent(url: String): Boolean {
        if (currentVideoUrl != null && currentVideoUrl == url) return true
        val lower = url.lowercase()
        return lower.contains("videoplayback") || lower.contains("manifest")
    }

    private fun isSubtitleAnalysisCandidate(file: MediaFile): Boolean {
        if (file.category == MediaCategory.SUBTITLE) return true
        val url = file.url.lowercase(java.util.Locale.ROOT)
        val title = file.title.substringBefore('\n').lowercase(java.util.Locale.ROOT)
        val mime = file.mimeType.lowercase(java.util.Locale.ROOT)
        val subtitleExtensions = setOf("vtt", "srt", "ass", "ssa", "ttml", "dfxp", "srv1", "srv2", "srv3")
        val extension = title.substringAfterLast('.', "").ifBlank {
            android.net.Uri.parse(file.url).lastPathSegment.orEmpty().substringAfterLast('.', "")
        }
        return extension in subtitleExtensions ||
            listOf("text/vtt", "subrip", "ttml", "dfxp").any { mime.contains(it) } ||
            listOf("/subtitle", "/subtitles", "/caption", "/captions", "timedtext").any { url.contains(it) } ||
            Regex("[?&](?:lang|tlang|subtitle|caption|subtitles|captions)(?:=|&|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(url)
    }

        private fun fetchSubtitleSnippet(mediaFile: MediaFile) {
        val canonicalUrl = SubtitleUtils.canonicalMediaUrl(mediaFile.url)
        if (!subtitleAnalysisInFlight.add(canonicalUrl)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val candidate = mediaEngine.getCandidate(mediaFile.url)
            val userAgent = candidate?.userAgent
                ?: withContext(Dispatchers.Main) { webView.settings.userAgentString }
            val referer = candidate?.referer
                ?: withContext(Dispatchers.Main) { webView.url }
            val cookie = candidate?.cookie ?: CookieManager.getInstance().getCookie(mediaFile.url)

            fun readLimitedText(url: String, maxBytes: Int = 262144): String {
                val connection = java.net.URL(url).openConnection()
                try {
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    if (connection is java.net.HttpURLConnection) {
                        connection.instanceFollowRedirects = true
                        userAgent.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("User-Agent", it) }
                        referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
                        cookie?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                        connection.setRequestProperty("Accept", "text/vtt, application/x-subrip, application/ttml+xml, application/vnd.apple.mpegurl, */*")
                        connection.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                        val status = connection.responseCode
                        if (status !in 200..299) throw java.io.IOException("HTTP $status")
                    }
                    val bytes = connection.getInputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var remaining = maxBytes
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.toByteArray()
                    }
                    return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                } finally {
                    (connection as? java.net.HttpURLConnection)?.disconnect()
                }
            }

            try {
                var text = readLimitedText(mediaFile.url)
                val trimmedPayload = text.trimStart().removePrefix("\uFEFF").trimStart()

                if (trimmedPayload.startsWith("#EXTM3U", ignoreCase = true)) {
                    val tracks = SubtitleUtils.parseSubtitleTracks(text, mediaFile.url)
                    if (tracks.isNotEmpty()) {
                        val firstTrack = tracks.first()
                        val normalized = SubtitleUtils.normalizeLanguageCode(firstTrack.language)
                            ?.takeUnless { it == "und" }
                        val language = normalized?.let { SubtitleUtils.languageDisplayName(it) }
                            ?: firstTrack.name.takeIf { it.isNotBlank() && !it.equals("und", true) }
                        synchronized(detectedMediaFiles) {
                            val index = detectedMediaFiles.indexOfFirst {
                                SubtitleUtils.canonicalMediaUrl(it.url) == canonicalUrl
                            }
                            if (index >= 0) {
                                val file = detectedMediaFiles[index]
                                detectedMediaFiles[index] = file.copy(language = language)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            refreshDetectedMediaSheet?.invoke()
                            Toast.makeText(
                                this@MainActivity,
                                if (language != null) "Subtitle analyzed: $language" else "Found ${tracks.size} subtitle tracks",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }

                    // A subtitle media playlist contains segment URLs, not cue text.
                    // Fetch a few opening segments and merge their WebVTT cues for analysis.
                    val segmentUrls = SubtitleUtils.parseHlsSubtitlePlaylistUris(text, mediaFile.url).take(4)
                    val segments = segmentUrls.mapNotNull { segmentUrl ->
                        runCatching { readLimitedText(segmentUrl, 131072) }.getOrNull()
                    }
                    text = SubtitleUtils.mergeHlsSubtitleSegments(segments)
                    if (text.isBlank()) {
                        withContext(Dispatchers.Main) {
                            refreshDetectedMediaSheet?.invoke()
                            Toast.makeText(this@MainActivity, "Subtitle playlist found, but its caption segments could not be read.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                val result = SubtitleUtils.extractSnippet(text)
                val validPayload = SubtitleUtils.isValidSubtitleContent(text) &&
                    !text.contains("<script", true) && !text.contains("<iframe", true) &&
                    !text.contains("<!DOCTYPE", true)
                if (!validPayload) {
                    withContext(Dispatchers.Main) {
                        refreshDetectedMediaSheet?.invoke()
                        Toast.makeText(this@MainActivity, "Could not analyze this URL as subtitle text. The detected item was kept.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val inferredCode = SubtitleUtils.normalizeLanguageCode(
                    result.language ?: SubtitleUtils.extractTrustedLanguageFromUrl(mediaFile.url)
                )?.takeUnless { it == "und" }
                val language = inferredCode?.let { SubtitleUtils.languageDisplayName(it) }
                    ?: mediaFile.language?.takeUnless {
                        it.equals("Unknown language", true) || it.equals("und", true)
                    }
                    ?: inferSubtitleLanguageFromName(mediaFile.title, mediaFile.url)

                val changed = synchronized(detectedMediaFiles) {
                    val index = detectedMediaFiles.indexOfFirst {
                        SubtitleUtils.canonicalMediaUrl(it.url) == canonicalUrl
                    }
                    if (index < 0) false else {
                        val file = detectedMediaFiles[index]
                        detectedMediaFiles[index] = file.copy(language = language)
                        true
                    }
                }
                withContext(Dispatchers.Main) {
                    if (changed) {
                        updateFabVisibility()
                        refreshDetectedMediaSheet?.invoke()
                        currentMediaListAdapter?.notifyDataSetChanged()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (language != null) "Subtitle analyzed: $language" else "Subtitle content found, but language metadata is missing.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Subtitle analysis failed for ${mediaFile.url}: ${e.javaClass.simpleName}", e)
                withContext(Dispatchers.Main) {
                    refreshDetectedMediaSheet?.invoke()
                    Toast.makeText(
                        this@MainActivity,
                        "Subtitle analysis failed: ${e.message ?: e.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                subtitleAnalysisInFlight.remove(canonicalUrl)
            }
        }
    }

    private fun inferSubtitleLanguageFromName(title: String, url: String): String? {
        val candidates = listOf(title, android.net.Uri.parse(url).lastPathSegment.orEmpty())
        for (candidate in candidates) {
            val bracketCode = Regex("(?i)\\[([a-z]{2,3})\\]").find(candidate)?.groupValues?.getOrNull(1)
            val separatedCode = Regex("(?i)(?:^|[^a-z])(en|eng|id|ind|ja|jpn|ko|kor|zh|zho|es|spa|fr|fra|de|deu|pt|por|ms|msa|th|tha|vi|vie|ar|ara|hi|hin|ru|rus|fil|tl|km)(?:$|[^a-z])")
                .find(candidate)?.groupValues?.getOrNull(1)
            val code = SubtitleUtils.normalizeLanguageCode(bracketCode ?: separatedCode)
                ?.takeUnless { it == "und" }
            if (code != null) return SubtitleUtils.languageDisplayName(code)
        }
        return null
    }

inner class YouTubeInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun onVideoData(json: String) {
            val video = YouTubeHelper.parseVideoData(json)
            if (video != null && video.formats.isNotEmpty()) {
                activity.runOnUiThread {
                    showYouTubeQualityDialog(video)
                }
            } else {
                onError("No downloadable video found.")
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, "YouTube Error: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class YouTubeOption(val label: String, val videoFormat: YouTubeFormat?, val audioFormat: YouTubeFormat?)

    private fun showYouTubeQualityDialog(video: YouTubeVideo) {
        val videoFormats = video.formats.filter { it.mimeType.startsWith("video/") }
        val audioFormats = video.formats.filter { it.mimeType.startsWith("audio/") }
        val bestAudio = audioFormats.maxByOrNull { it.averageBitrate ?: 0 }

        val options = mutableListOf<YouTubeOption>()

        // 1. Video Options (Grouped by Height)
        val groupedVideos = videoFormats.groupBy { it.height }
        groupedVideos.keys.sortedDescending().forEach { height ->
             if (height <= 0) return@forEach
             val formatsAtHeight = groupedVideos[height]!!
             // Prefer MP4, then highest bitrate
             val bestFormat = formatsAtHeight.sortedWith(compareByDescending<YouTubeFormat> {
                 it.mimeType.contains("mp4")
             }.thenByDescending {
                 it.averageBitrate ?: 0
             }).first()

             // Check if it's combined or adaptive
             val isAdaptive = bestFormat.audioQuality == null
             val needsMuxing = isAdaptive && bestAudio != null

             val label = "${height}p ${if (needsMuxing) "(Video+Audio)" else if (isAdaptive) "(Video Only)" else ""}"
             options.add(YouTubeOption(label, bestFormat, if (needsMuxing) bestAudio else null))
        }

        // 2. Audio Only Option
        if (bestAudio != null) {
             val bitrate = (bestAudio.averageBitrate ?: 0) / 1000
             options.add(YouTubeOption("Audio Only (${bitrate}kbps)", null, bestAudio))
        }

        val displayItems = options.map { it.label }.toTypedArray()

        createThemedDialogBuilder(this)
            .setTitle("Download: ${video.title}")
            .setItems(displayItems) { _, which ->
                val selected = options[which]
                startYouTubeDownload(video.title, selected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startYouTubeDownload(title: String, option: YouTubeOption) {
        val intent = Intent(this, YouTubeDownloadService::class.java).apply {
            action = YouTubeDownloadService.ACTION_START_DOWNLOAD
            putExtra(YouTubeDownloadService.EXTRA_TITLE, title)

            val userAgent = webView.settings.userAgentString
            putExtra(YouTubeDownloadService.EXTRA_USER_AGENT, userAgent)
            putExtra(YouTubeDownloadService.EXTRA_REFERER, "https://www.youtube.com/")

            if (option.videoFormat != null) {
                putExtra(YouTubeDownloadService.EXTRA_VIDEO_URL, option.videoFormat.url)
                if (option.audioFormat != null) {
                     putExtra(YouTubeDownloadService.EXTRA_AUDIO_URL, option.audioFormat.url)
                }
                putExtra(YouTubeDownloadService.EXTRA_MIME_TYPE, option.videoFormat.mimeType.substringBefore(";"))
            } else if (option.audioFormat != null) {
                 // Audio only - pass as videoUrl to service logic
                 putExtra(YouTubeDownloadService.EXTRA_VIDEO_URL, option.audioFormat.url)
                 putExtra(YouTubeDownloadService.EXTRA_MIME_TYPE, option.audioFormat.mimeType.substringBefore(";"))
            }
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show()
    }


    fun showMediaInfoDialog(mediaFile: MediaFile) {
        val candidate = mediaEngine.getCandidate(mediaFile.url)

        val infoText = StringBuilder()
        infoText.append("Title: ${mediaFile.title}\n")
        infoText.append("URL: ${mediaFile.url}\n\n")
        infoText.append("Category: ${mediaFile.category}\n")
        infoText.append("MIME: ${mediaFile.mimeType ?: "Unknown"}\n")
        infoText.append("Quality: ${mediaFile.quality}\n")
        infoText.append("Language: ${mediaFile.language ?: "N/A"}\n")
        infoText.append("Size: ${mediaFile.fileSize}\n")
        infoText.append("Is Main Content: ${mediaFile.isMainContent}\n\n")

        if (candidate != null) {
            infoText.append("--- Native Engine Candidate ---\n")
            infoText.append("Type: ${candidate.type}\n")
            infoText.append("Manifest: ${candidate.isManifest}\n")
            infoText.append("Duration: ${candidate.durationSec}s\n")
            infoText.append("Request Count: ${candidate.requestCount}\n")
            infoText.append("Active Player: ${candidate.isActivePlayer}\n")
            infoText.append("MSE Activity: ${candidate.hasMSEActivity}\n")
            infoText.append("Ad Score: ${candidate.adScore}\n")
            infoText.append("Playback Score: ${candidate.playbackScore}\n")
            infoText.append("Final Score: ${candidate.finalScore}\n")
            infoText.append("Confidence: ${candidate.confidence}\n")
            if (candidate.telemetry != null) {
                infoText.append("Resolution: ${candidate.telemetry!!.width}x${candidate.telemetry!!.height}\n")
            }
        } else {
            infoText.append("No active engine tracking for this URL.\n")
        }

        createThemedDialogBuilder(this)
            .setTitle("Media Info")
            .setMessage(infoText.toString())
            .setPositiveButton("Close", null)
            .show()
    }

private fun showMediaListDialog() {
        if (detectedMediaFiles.isEmpty()) {
            android.widget.Toast.makeText(this, "No media files detected.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        var mediaFilesCopy = synchronized(detectedMediaFiles) {
            detectedMediaFiles.distinctBy { SubtitleUtils.canonicalMediaUrl(it.url) }.toMutableList()
        }
        val dialogBinding = com.omymaxz.download.databinding.DialogMediaListBinding.inflate(layoutInflater)
        var selectedFilter = "ALL"
        var searchQuery = ""
        var sheetDismissed = false
        var sheetAdapterOrNull: MediaListAdapter? = null

        fun updateMediaFilter(preserveScroll: Boolean = false) {
            if (sheetDismissed) return
            val recycler = dialogBinding.mediaRecyclerView
            val manager = recycler.layoutManager as? LinearLayoutManager
            val firstVisible = if (preserveScroll) manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION else RecyclerView.NO_POSITION
            val firstOffset = if (firstVisible != RecyclerView.NO_POSITION) manager?.findViewByPosition(firstVisible)?.top ?: 0 else 0

            // The detected list is authoritative; a long-lived sheet must not render its opening snapshot.
            mediaFilesCopy = synchronized(detectedMediaFiles) {
                detectedMediaFiles.distinctBy { SubtitleUtils.canonicalMediaUrl(it.url) }.toMutableList()
            }
            val filterText = searchQuery.trim()
            val filtered = mediaFilesCopy.filter { file ->
                val categoryMatches = when (selectedFilter) {
                    "VIDEO" -> file.category == MediaCategory.VIDEO
                    "AUDIO" -> file.category == MediaCategory.AUDIO
                    "SUBTITLE" -> file.category == MediaCategory.SUBTITLE ||
                        file.title.endsWith(".vtt", ignoreCase = true) || file.title.endsWith(".srt", ignoreCase = true)
                    "OTHER" -> file.category !in setOf(MediaCategory.VIDEO, MediaCategory.AUDIO, MediaCategory.SUBTITLE)
                    else -> true
                }
                categoryMatches && (filterText.isBlank() ||
                    file.title.contains(filterText, ignoreCase = true) ||
                    file.url.contains(filterText, ignoreCase = true) ||
                    file.quality.contains(filterText, ignoreCase = true) ||
                    (file.language?.contains(filterText, ignoreCase = true) == true))
            }
            sheetAdapterOrNull?.let {
                it.mediaFiles = filtered.toMutableList()
                it.notifyDataSetChanged()
            }
            dialogBinding.mediaEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            dialogBinding.mediaRecyclerView.visibility = if (filtered.isEmpty()) View.INVISIBLE else View.VISIBLE
            dialogBinding.mediaEmptyState.text = if (mediaFilesCopy.isEmpty()) {
                "No detected media on this page"
            } else {
                "No media matches this filter"
            }
            dialogBinding.mediaSheetSummary.text = if (filterText.isBlank() && selectedFilter == "ALL") {
                "${mediaFilesCopy.size} items detected on this page"
            } else {
                "${filtered.size} of ${mediaFilesCopy.size} items"
            }
            dialogBinding.clearListButton.isEnabled = mediaFilesCopy.isNotEmpty()

            if (preserveScroll && firstVisible != RecyclerView.NO_POSITION && filtered.isNotEmpty()) {
                recycler.post {
                    if (!sheetDismissed) {
                        (recycler.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(firstVisible.coerceAtMost(filtered.lastIndex), firstOffset)
                    }
                }
            }
        }

        dialogBinding.root.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.md_surface)
        )
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.DetectedMediaBottomSheetTheme
        )
        // The binding is inflated manually, so attach its root to the dialog explicitly.
        // Without this, the dialog window can appear blank while intercepting browser touches.
        dialog.setContentView(dialogBinding.root)

        val sheetAdapter = MediaListAdapter(mediaFilesCopy, { mediaFile ->
            // Tap to download (do not dismiss to keep scroll position)
            showRenameDialog(mediaFile)
        }, { mediaFile ->
            // Long press to "Open With" (do not dismiss to keep scroll position)
            openMediaWith(mediaFile)
        }, { mediaFile, position ->
            val cand = mediaEngine.getCandidate(mediaFile.url)
            if (cand != null) {
                mediaEngine.parseMetadataAsync(cand, isManual = true) { updatedCand ->
                    // Store manifest/variant evidence explicitly so the media list can
                    // recolor the row after analysis instead of guessing from subtitle presence.
                    // The manifest parser only sets this when actual HLS variant
                    // entries prove that more than one video rendition is selectable.
                    mediaFile.hasMultipleQualities = updatedCand.hasMultipleQualities
                    // Update MediaFile info from Candidate
                    if (updatedCand.durationSec > 0) {
                        val hours = updatedCand.durationSec / 3600
                        val mins = (updatedCand.durationSec % 3600) / 60
                        val secs = updatedCand.durationSec % 60
                        val durStr = if (hours > 0) "${hours}h${mins}m${secs}s" else "${mins}m${secs}s"
                        if (!mediaFile.title.contains("Duration:")) {
                            mediaFile.title += "\nDuration: $durStr"
                        }
                    }
                    if (updatedCand.resolution != null && !mediaFile.title.contains("Resolution:")) {
                        mediaFile.title += " | Resolution: ${updatedCand.resolution}"
                    }
                    if (updatedCand.estimatedSize != null) {
                        val mb = updatedCand.estimatedSize!! / (1024.0 * 1024.0)
                        mediaFile.fileSize = "~%.1fMB".format(mb)
                    } else {
                        mediaFile.fileSize = "Unknown"
                    }
                    currentMediaListAdapter?.notifyItemChanged(position)
                    updateMediaFilter()
                }
            } else {
                Toast.makeText(this, "Could not find underlying candidate to analyze", Toast.LENGTH_SHORT).show()
                currentMediaListAdapter?.notifyItemChanged(position)
            }
        }, { mediaFile ->
            val candidate = mediaEngine.getCandidate(mediaFile.url)
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                val title = if (mediaFile.title.contains("\n")) mediaFile.title.substringBefore("\n") else mediaFile.title
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, title)
                if (mediaFile.mimeType != null) putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, candidate?.userAgent ?: webView.settings.userAgentString)
                putExtra(CustomPlayerActivity.EXTRA_REFERER, candidate?.referer ?: webView.url)
                val cookie = candidate?.cookie ?: CookieManager.getInstance().getCookie(mediaFile.url)
                if (!cookie.isNullOrBlank()) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        })
        sheetAdapterOrNull = sheetAdapter
        currentMediaListAdapter = sheetAdapter
        val refreshThisSheet: () -> Unit = { updateMediaFilter(preserveScroll = true) }
        refreshDetectedMediaSheet = refreshThisSheet
        dialog.setOnDismissListener {
            sheetDismissed = true
            if (refreshDetectedMediaSheet === refreshThisSheet) refreshDetectedMediaSheet = null
            if (currentMediaListAdapter === sheetAdapter) currentMediaListAdapter = null
        }

        dialogBinding.clearListButton.setOnClickListener {
            synchronized(detectedMediaFiles) {
                detectedMediaFiles.clear()
            }
            updateMediaFilter()
            updateFabVisibility()
            dialog.dismiss()
            Toast.makeText(this, "Media list cleared", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.analyzeSubtitlesButton.setOnClickListener {
            val subs = synchronized(detectedMediaFiles) {
                detectedMediaFiles.filter { isSubtitleAnalysisCandidate(it) }
            }
            if (subs.isEmpty()) {
                Toast.makeText(this, "No subtitles found to analyze.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Analyzing ${subs.size} subtitles...", Toast.LENGTH_SHORT).show()
                for (sub in subs) {
                    fetchSubtitleSnippet(sub)
                }
            }
        }

        dialogBinding.mediaFilterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFilter = when (checkedIds.firstOrNull()) {
                R.id.filterVideo -> "VIDEO"
                R.id.filterAudio -> "AUDIO"
                R.id.filterSubtitle -> "SUBTITLE"
                R.id.filterOther -> "OTHER"
                else -> "ALL"
            }
            updateMediaFilter()
        }
        dialogBinding.mediaSearchInput?.doAfterTextChanged { editable ->
            searchQuery = editable?.toString().orEmpty()
            updateMediaFilter()
        }

        dialogBinding.mediaRecyclerView.layoutManager = LinearLayoutManager(this)
        dialogBinding.mediaRecyclerView.adapter = sheetAdapter
        updateMediaFilter()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun updateMediaTitle(url: String, newTitle: String) {
        synchronized(detectedMediaFiles) {
            val index = detectedMediaFiles.indexOfFirst { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(url) }
            if (index != -1) {
                val oldFile = detectedMediaFiles[index]
                if (oldFile.title != newTitle) {
                    val newFile = oldFile.copy(title = newTitle)
                    detectedMediaFiles[index] = newFile
                    runOnUiThread {
                        // Update the adapter's snapshot list explicitly before notifying
                        currentMediaListAdapter?.mediaFiles?.let { snapshot ->
                            val snapshotIndex = snapshot.indexOfFirst { it.url == url }
                            if (snapshotIndex != -1) {
                                snapshot[snapshotIndex] = newFile
                                currentMediaListAdapter?.notifyItemChanged(snapshotIndex)
                            }
                        }
                    }
                }
            }
        }
    }
    private fun openMediaWith(mediaFile: MediaFile) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(mediaFile.url), mediaFile.mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with")
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No application found to handle this file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInExternalPlayer(videoUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                setDataAndType(Uri.parse(videoUrl), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }
    private fun getBlobContentScript(url: String, asBase64: Boolean, callbackFunction: String, errorFunction: String): String {
        return """
            (function() {
                var callback = $callbackFunction;
                var errorCallback = $errorFunction;

                function formatTime(seconds) {
                    var date = new Date(0);
                    date.setMilliseconds(seconds * 1000);
                    return date.toISOString().substr(11, 12);
                }
                function serializeCues(cues) {
                    var output = "WEBVTT\n\n";
                    for (var i = 0; i < cues.length; i++) {
                        var cue = cues[i];
                        output += formatTime(cue.startTime) + " --> " + formatTime(cue.endTime) + "\n";
                        output += cue.text + "\n\n";
                    }
                    return output;
                }
                function sendResult(data) {
                    if ($asBase64) {
                        try {
                            var b64 = btoa(unescape(encodeURIComponent(data)));
                            callback("data:text/vtt;base64," + b64);
                        } catch(e) {
                            errorCallback("Encoding failed: " + e.toString());
                        }
                    } else {
                        callback(data);
                    }
                }

                var targetUrl = "$url";
                var processedFrames = new Set();

                function findTrack(win) {
                    if (processedFrames.has(win)) return null;
                    processedFrames.add(win);
                    try {
                        var tracks = win.document.querySelectorAll('track');
                        for (var i = 0; i < tracks.length; i++) {
                            if (tracks[i].src === targetUrl) return tracks[i];
                        }
                        for (var i = 0; i < win.frames.length; i++) {
                            var t = findTrack(win.frames[i]);
                            if (t) return t;
                        }
                    } catch (e) { }
                    return null;
                }

                var track = findTrack(window);
                
                function doFetch(contextWindow) {
                    console.log("Falling back to fetch for " + targetUrl);
                    var fetcher = contextWindow ? contextWindow.fetch : fetch;
                    fetcher(targetUrl).then(res => {
                        if ($asBase64) {
                            return res.blob().then(blob => {
                                var reader = new FileReader();
                                reader.onloadend = function() { callback(reader.result); }
                                reader.readAsDataURL(blob);
                            });
                        } else {
                            return res.text().then(text => callback(text));
                        }
                    }).catch(err => {
                        console.error("Fetch failed", err);
                        errorCallback("Preview failed. Track found: " + (!!track) + ". Fetch error: " + err.toString());
                    });
                }

                if (track) {
                    console.log("Found track element");
                    if (track.track.mode === 'disabled') {
                        console.log("Enabling track mode");
                        track.track.mode = 'hidden';
                    }
                    
                    var attempts = 0;
                    function checkCues() {
                        if (track.track.cues && track.track.cues.length > 0) {
                             console.log("Cues found");
                             sendResult(serializeCues(track.track.cues));
                        } else if (attempts < 20) {
                             attempts++;
                             setTimeout(checkCues, 100);
                        } else {
                             console.warn("No cues found after waiting");
                             doFetch(track.ownerDocument.defaultView);
                        }
                    }
                    checkCues();
                } else {
                    console.warn("Track element not found in DOM");
                    doFetch(window);
                }
            })();
        """
    }

        private fun sameEpisodePageUrl(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        fun normalized(value: String): String = runCatching {
            android.net.Uri.parse(value.trim()).normalizeScheme().toString().trimEnd('/')
        }.getOrDefault(value.trim().trimEnd('/'))
        return normalized(left) == normalized(right)
    }

    private fun subtitleMetadataPayload(files: List<MediaFile>): ArrayList<String> =
        ArrayList(files.distinctBy { SubtitleUtils.canonicalMediaUrl(it.url) }.map { file ->
            // Keep signed URLs byte-for-byte unchanged; language is carried separately.
            val url64 = android.util.Base64.encodeToString(
                file.url.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val title64 = file.title.trim().takeIf { it.isNotEmpty() }?.let {
                android.util.Base64.encodeToString(
                    it.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
            } ?: "-"
            val language = SubtitleUtils.normalizeLanguageCode(file.language)?.takeUnless { it == "und" } ?: "-"
            "$url64${CustomPlayerActivity.SUBTITLE_META_SEPARATOR}$language${CustomPlayerActivity.SUBTITLE_META_SEPARATOR}$title64"
        })

        private fun launchPlayerWithCandidate(candidate: MediaCandidate, title: String, fallbackReferer: String?) {

        // Prevent playback of pure segment groups without a manifest
        if (candidate.isSegmentGroup) {
            android.widget.Toast.makeText(this, "Stream requires manifest for playback. Segments can only be exported.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // INTERCEPT YOUTUBE PLAY IN APP
        // If the candidate is a raw googlevideo chunk, do NOT pass it to CustomPlayerActivity natively
        // Find the actual YouTube URL and send it to NewPipe extraction instead.
        if (candidate.url.contains("googlevideo.com/videoplayback")) {
            val ytUrl = candidate.referer ?: fallbackReferer ?: webView.url?.toString()
            if (ytUrl != null && (ytUrl.contains("youtube.com") || ytUrl.contains("youtu.be"))) {
                android.util.Log.d("PlayInApp", "Intercepted googlevideo.com raw URL. Rerouting to YoutubeExtractorHelper using: $ytUrl")
                Toast.makeText(this, "Extracting YouTube stream...", Toast.LENGTH_SHORT).show()
                checkForYouTube(ytUrl, force = true, autoPlay = true)
                return
            } else {
                android.util.Log.e("PlayInApp", "Detected googlevideo.com chunk but could not find a valid YouTube origin URL to extract. Fallback referrer was: $ytUrl")
                Toast.makeText(this, "Cannot play raw YouTube data chunk. Please reload page.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val intent = Intent(this, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP);
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, candidate.url)
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, title)

            // Infer audio MIME before generic progressive fallback: Suno commonly serves audio-only .m4a URLs.
            // Keep the full URL unchanged because query parameters may be signed/required.
            var mimeType: String? = null
            val ctLower = candidate.contentType?.substringBefore(";")?.trim()?.lowercase().orEmpty()
            val pathLower = android.net.Uri.parse(candidate.url).path.orEmpty().lowercase()
            mimeType = when {
                ctLower.contains("mpegurl") || candidate.url.contains(".m3u8", ignoreCase = true) ->
                    androidx.media3.common.MimeTypes.APPLICATION_M3U8
                ctLower.contains("dash+xml") || candidate.url.contains(".mpd", ignoreCase = true) ->
                    androidx.media3.common.MimeTypes.APPLICATION_MPD
                ctLower.startsWith("audio/") -> ctLower
                pathLower.endsWith(".m4a") -> androidx.media3.common.MimeTypes.AUDIO_MP4
                pathLower.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                pathLower.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
                pathLower.endsWith(".ogg") || pathLower.endsWith(".opus") -> androidx.media3.common.MimeTypes.AUDIO_OGG
                pathLower.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
                ctLower.startsWith("video/") -> ctLower
                pathLower.endsWith(".mp4") || pathLower.endsWith(".m4v") -> androidx.media3.common.MimeTypes.VIDEO_MP4
                pathLower.endsWith(".webm") -> androidx.media3.common.MimeTypes.VIDEO_WEBM
                pathLower.endsWith(".mkv") -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
                candidate.isProgressiveFinal -> null // Let Media3 sniff unknown progressive containers.
                else -> null
            }

            if (mimeType != null) {
                putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mimeType)
            }

            val safeCandidateUri = android.net.Uri.parse(candidate.url)
            android.util.Log.d("SUNO_SOURCE_TEST", "[SUNO_SOURCE_TEST] handoff host=${safeCandidateUri.host} path=${safeCandidateUri.path} queryPresent=${!safeCandidateUri.encodedQuery.isNullOrEmpty()} kind=${candidate.mediaKind} type=${candidate.type} contentType=${candidate.contentType} durationSec=${candidate.durationSec} active=${candidate.isActivePlayer} requests=${candidate.requestCount} mimeType=${mimeType ?: "MEDIA3_SNIFF"} refererHost=${android.net.Uri.parse(candidate.referer ?: fallbackReferer ?: webView.url.orEmpty()).host} userAgentPresent=${candidate.userAgent != null} cookiePresent=${candidate.cookie != null}")

            putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, candidate.userAgent ?: webView.settings.userAgentString)

            val refererToUse = candidate.referer ?: fallbackReferer ?: webView.url
            putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)

            // KissKH media URLs often live on a separate CDN host, so their own URL may
            // have no cookies even while the main KissKH page has an active session.
            // Prefer the candidate's captured cookie, then check the media host and page
            // origins. Never log the cookie value.
            val cookieManager = CookieManager.getInstance()
            val mediaUrl = candidate.url
            val pageUrl = webView.url
            val cookie = candidate.cookie?.takeIf { it.isNotBlank() }
                ?: cookieManager.getCookie(mediaUrl)?.takeIf { it.isNotBlank() }
                ?: pageUrl?.let { cookieManager.getCookie(it) }?.takeIf { it.isNotBlank() }
                ?: refererToUse?.let { cookieManager.getCookie(it) }?.takeIf { it.isNotBlank() }
            if (!cookie.isNullOrBlank()) {
                putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
            }

                        val allSubtitleUrls = synchronized(detectedMediaFiles) {
                detectedMediaFiles
                    .filter { (it.category == MediaCategory.SUBTITLE || it.title.endsWith(".vtt") || it.title.endsWith(".srt")) }
                    // Filter subtitles strictly based on the current page's URL to prevent mixing KissKH episodes
                    .filter { sameEpisodePageUrl(it.referer, webView.url?.toString()) }
                    .map { it.url }
                    .distinct().toMutableList()
            }
            if (allSubtitleUrls.isNotEmpty()) {
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(allSubtitleUrls))
                val subtitleMetadataFiles = synchronized(detectedMediaFiles) {
                    detectedMediaFiles.filter { file ->
                        (file.category == MediaCategory.SUBTITLE || file.title.endsWith(".vtt", true) || file.title.endsWith(".srt", true)) &&
                            sameEpisodePageUrl(file.referer, webView.url?.toString())
                    }
                }
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_METADATA, subtitleMetadataPayload(subtitleMetadataFiles))
            }

                val subtitleMetadataFiles = synchronized(detectedMediaFiles) {
                    val pagePath = webView.url?.toString().orEmpty().substringBefore("?").substringBefore("#")
                    detectedMediaFiles.filter { file ->
                        val refPath = file.referer?.substringBefore("?")?.substringBefore("#").orEmpty()
                        (file.category == MediaCategory.SUBTITLE || file.title.endsWith(".vtt", true) || file.title.endsWith(".srt", true)) &&
                            (refPath == pagePath || (file.referer == candidate.referer && candidate.referer != null))
                    }
                }
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_METADATA, subtitleMetadataPayload(subtitleMetadataFiles))
        }
        startActivity(intent)
    }

    private fun launchLegacyPlayer(url: String, title: String, fallbackReferer: String?, mimeType: String? = null) {
        // INTERCEPT YOUTUBE PLAY IN APP
        if (url.contains("googlevideo.com/videoplayback")) {
            val ytUrl = fallbackReferer ?: webView.url?.toString()
            if (ytUrl != null && (ytUrl.contains("youtube.com") || ytUrl.contains("youtu.be"))) {
                android.util.Log.d("PlayInApp", "Intercepted googlevideo.com raw URL. Rerouting to YoutubeExtractorHelper using: $ytUrl")
                Toast.makeText(this, "Extracting YouTube stream...", Toast.LENGTH_SHORT).show()
                checkForYouTube(ytUrl, force = true, autoPlay = true)
                return
            } else {
                android.util.Log.e("PlayInApp", "Detected googlevideo.com chunk but could not find a valid YouTube origin URL to extract. Fallback referrer was: $ytUrl")
                Toast.makeText(this, "Cannot play raw YouTube data chunk. Please reload page.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val intent = Intent(this, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }.apply {
            if (mimeType != null) {
                putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mimeType)
            }
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, title)
            putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)

            val refererToUse = fallbackReferer ?: webView.url
            putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)

            val cookie = CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance().getCookie(refererToUse)
            if (cookie != null) {
                putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
            }

                        val allSubtitleUrls = synchronized(detectedMediaFiles) {
                detectedMediaFiles
                    .filter { (it.category == MediaCategory.SUBTITLE || it.title.endsWith(".vtt") || it.title.endsWith(".srt")) }
                    // Filter subtitles strictly based on the current page's URL to prevent mixing KissKH episodes
                    .filter { sameEpisodePageUrl(it.referer, webView.url?.toString()) }
                    .map { it.url }
                    .distinct().toMutableList()
            }
            if (allSubtitleUrls.isNotEmpty()) {
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(allSubtitleUrls))
            }
        }
        startActivity(intent)
    }


    fun launchCustomPlayer(mediaFile: MediaFile) {
        val finalName = mediaFile.title
        if (mediaFile.url.contains("googlevideo.com") && (mediaFile.mimeType == "application/dash+xml" || mediaFile.mimeType == "application/x-mpegURL")) {
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (mediaFile.mimeType != null) {
                    putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                }
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                val refererToUse = mediaFile.referer ?: webView.url
                putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)

                val allSubtitleUrls = synchronized(detectedMediaFiles) {
                    detectedMediaFiles
                        .filter { (it.category == MediaCategory.SUBTITLE || it.title.endsWith(".vtt") || it.title.endsWith(".srt")) }
                        // Filter subtitles strictly based on the current page's URL to prevent mixing KissKH episodes
                        .filter { sameEpisodePageUrl(it.referer, webView.url?.toString()) }
                    .map { it.url }
                    .distinct().toMutableList()
                }
                if (allSubtitleUrls.isNotEmpty()) {
                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(allSubtitleUrls))
                    val subtitleMetadataFiles = synchronized(detectedMediaFiles) {
                        val pagePath = webView.url?.toString().orEmpty().substringBefore("?").substringBefore("#")
                        detectedMediaFiles.filter { file ->
                            val refPath = file.referer?.substringBefore("?")?.substringBefore("#").orEmpty()
                            (file.category == MediaCategory.SUBTITLE || file.title.endsWith(".vtt", true) || file.title.endsWith(".srt", true)) &&
                                (refPath == pagePath || (file.referer == mediaFile.referer && mediaFile.referer != null))
                        }
                    }
                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_METADATA, subtitleMetadataPayload(subtitleMetadataFiles))
                }

                    val subtitleMetadataFiles = synchronized(detectedMediaFiles) {
                    val pagePath = webView.url?.toString().orEmpty().substringBefore("?").substringBefore("#")
                    detectedMediaFiles.filter { file ->
                        val refPath = file.referer?.substringBefore("?")?.substringBefore("#").orEmpty()
                        (file.category == MediaCategory.SUBTITLE || file.title.endsWith(".vtt", true) || file.title.endsWith(".srt", true)) &&
                            (refPath == pagePath || (file.referer == mediaFile.referer && mediaFile.referer != null))
                    }
                }
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_METADATA, subtitleMetadataPayload(subtitleMetadataFiles))
            }
            startActivity(intent)
            return
        }

        if (mediaFile.url.contains("youtube.com") || mediaFile.url.contains("youtu.be") || mediaFile.url.contains("googlevideo.com") || mediaFile.url.contains("manifest/dash") || mediaFile.mimeType == "application/dash+xml") {
            val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (mediaFile.mimeType != null) {
                    putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                }
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                val refererToUse = mediaFile.referer ?: webView.url
                putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
                this@MainActivity.configureYouTubeAudioSource(this, mediaFile)
                if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                    val taggedUrls = mediaFile.subtitleUrls!!.map { url ->
                        if (mediaFile.language != null && !url.contains("lang=")) {
                            val joiner = if (url.contains("?")) "&lang=${mediaFile.language}" else "?lang=${mediaFile.language}"
                            url + joiner
                        } else url
                    }
                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(taggedUrls))
                }
            }
            startActivity(intent)
            return
        }

        val exactCandidate = mediaEngine.candidates[mediaFile.url]
        if (exactCandidate != null) {
            // Need to fix launchPlayerWithCandidate inside MainActivity to also append SINGLE_TOP
            launchPlayerWithCandidate(exactCandidate, finalName, mediaFile.referer)
        } else {
            launchLegacyPlayer(mediaFile.url, finalName, mediaFile.referer, mediaFile.mimeType)
        }
    }

private fun showRenameDialog(mediaFile: MediaFile) {
        val input = EditText(this).apply {
            setText(mediaFile.title.substringBeforeLast('.'))
            selectAll()
        }
        val builder = createThemedDialogBuilder(this)
            .setTitle("Download File")
            .setMessage("Quality: ${mediaFile.quality}")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val newName = input.text.toString().trim()
                val finalName = if (newName.isNotEmpty()) "$newName.${mediaFile.title.substringAfterLast('.')}" else mediaFile.title
                downloadMediaFile(mediaFile.copy(title = finalName))
            }

        val isSubtitle = mediaFile.category == MediaCategory.SUBTITLE || mediaFile.title.endsWith(".vtt") || mediaFile.title.endsWith(".srt")

        // YouTube caption responses can be XML/TTML despite having a misleading media filename.
        // Offer an explicit conversion action that runs the normal subtitle fetch/normalization path.
        if (isYoutubeCaptionMedia(mediaFile)) {
            builder.setNeutralButton("Convert to VTT") { _, _ ->
                val baseName = mediaFile.title.substringBeforeLast('.', mediaFile.title)
                downloadMediaFile(mediaFile.copy(
                    title = "$baseName.vtt",
                    mimeType = "text/vtt",
                    category = MediaCategory.SUBTITLE
                ))
            }
        }

        if (isSubtitle) {
            builder.setNegativeButton("Add to Player") { _, _ ->
                if (CustomPlayerActivity.activePlayer != null) {
                    val activePlayerUrl = CustomPlayerActivity.activePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
                    val intent = Intent(this, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        // Make sure we carry over the actual language if we found one
                        val langParam = if (mediaFile.language != null) "?lang=${mediaFile.language}" else ""
                        val joiner = if (mediaFile.url.contains("?")) "&lang=${mediaFile.language}" else langParam
                        val finalUrl = if (mediaFile.language != null && !mediaFile.url.contains("lang=")) mediaFile.url + joiner else mediaFile.url

                        putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, arrayListOf(finalUrl))
                        // We must explicitly NOT pass EXTRA_VIDEO_URL or EXTRA_VIDEO_TITLE here,
                        // because if we do, onNewIntent will see `newUrl != videoUrl` (because of missing query params or slight changes)
                        // and forcefully restart the entire video.
                        // By leaving it null, CustomPlayerActivity detects it as a pure subtitle injection and just hot-swaps it.
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Subtitle sent to Custom Player", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please play a video in the Custom Player first.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // For videos/audio, add Play in App button
            builder.setNegativeButton("Play in App") { _, _ ->
                val newName = input.text.toString().trim()
                val finalName = if (newName.isNotEmpty()) "$newName.${mediaFile.title.substringAfterLast('.')}" else mediaFile.title

                // Skip MediaDetectionEngine if this is already an extracted YouTube or directly injected media file
                if (mediaFile.url.contains("googlevideo.com") && (mediaFile.mimeType == "application/dash+xml" || mediaFile.mimeType == "application/x-mpegURL")) {
                    android.util.Log.d("PlayInApp", "Launching extracted YouTube DASH/HLS media directly bypassing engine: ${mediaFile.url}")
                    // DO NOT go back through launchLegacyPlayer's googlevideo interceptor which would bounce it back to extraction!
                    val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (mediaFile.mimeType != null) {
                            putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                        }
                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                        putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                        val refererToUse = mediaFile.referer ?: webView.url
                        putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                        val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                        if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
                this@MainActivity.configureYouTubeAudioSource(this, mediaFile)
                if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                    val taggedUrls = mediaFile.subtitleUrls!!.map { url ->
                        if (mediaFile.language != null && !url.contains("lang=")) {
                            val joiner = if (url.contains("?")) "&lang=${mediaFile.language}" else "?lang=${mediaFile.language}"
                            url + joiner
                        } else url
                    }
                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(taggedUrls))
                }
                    }
                    startActivity(intent)
                    return@setNegativeButton
                }

                if (mediaFile.url.contains("youtube.com") || mediaFile.url.contains("youtu.be") || mediaFile.url.contains("googlevideo.com") || mediaFile.url.contains("manifest/dash") || mediaFile.mimeType == "application/dash+xml") {
                    android.util.Log.d("PlayInApp", "Launching extracted media directly bypassing engine: ${mediaFile.url}")

                    // Directly construct intent to avoid googlevideo interception loop inside launchLegacyPlayer!
                    val intent = android.content.Intent(this@MainActivity, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (mediaFile.mimeType != null) {
                            putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
                        }
                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, finalName)
                        putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                        val refererToUse = mediaFile.referer ?: webView.url
                        putExtra(CustomPlayerActivity.EXTRA_REFERER, refererToUse)
                        val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaFile.url) ?: android.webkit.CookieManager.getInstance().getCookie(refererToUse)
                        if (cookie != null) putExtra(CustomPlayerActivity.EXTRA_COOKIE, cookie)
                this@MainActivity.configureYouTubeAudioSource(this, mediaFile)
                if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                    val taggedUrls = mediaFile.subtitleUrls!!.map { url ->
                        if (mediaFile.language != null && !url.contains("lang=")) {
                            val joiner = if (url.contains("?")) "&lang=${mediaFile.language}" else "?lang=${mediaFile.language}"
                            url + joiner
                        } else url
                    }
                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, ArrayList(taggedUrls))
                }
                    }
                    startActivity(intent)
                    return@setNegativeButton
                }

                // The user explicitly selected THIS specific mediaFile from the download list.
                // We MUST launch this specific URL, not the engine's generic "best candidate", otherwise it just resumes the old video!
                val exactCandidate = mediaEngine.candidates[mediaFile.url]
                if (exactCandidate != null) {
                    android.util.Log.d("PlayInApp", "Launching user-selected candidate directly: ${exactCandidate.url}")
                    launchPlayerWithCandidate(exactCandidate, finalName, mediaFile.referer)
                } else {
                    // Fallback if it's a manually injected file without a candidate
                    android.util.Log.d("PlayInApp", "No exact candidate found, launching legacy player: ${mediaFile.url}")
                    launchLegacyPlayer(mediaFile.url, finalName, mediaFile.referer, mediaFile.mimeType)
                }
            }
        }

        if (mediaFile.url.startsWith("blob:") || mediaFile.mimeType.startsWith("text/") || isSubtitle) {
            builder.setNeutralButton("Preview") { _, _ ->
                val safeUrl = mediaFile.url.replace("\"", "\\\"")
                val safeTitle = mediaFile.title.replace("\"", "\\\"")
                val safeMime = mediaFile.mimeType.replace("\"", "\\\"")
                val js = getBlobContentScript(
                    mediaFile.url,
                    false,
                    "function(text) { AndroidWebAPI.showPreview(text.substring(0, 20000), \"$safeTitle\", \"$safeMime\", \"$safeUrl\"); }",
                    "function(err) { AndroidWebAPI.onPreviewFallback(\"$safeUrl\", \"$safeTitle\", \"$safeMime\"); }"
                )
                webView.evaluateJavascript(js, null)
            }
        }

        builder.show()
    }

    private fun saveToDownloads(bytes: ByteArray, filename: String, mimeType: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(bytes)
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to create file in Downloads", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(downloadsDir, filename)
                java.io.FileOutputStream(file).use { outputStream ->
                    outputStream.write(bytes)
                }
                runOnUiThread {
                    Toast.makeText(this, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isYoutubeCaptionMedia(mediaFile: MediaFile): Boolean {
        val url = mediaFile.url
        val path = runCatching { java.net.URL(url).path.lowercase() }.getOrDefault(url.substringBefore("?").lowercase())
        if (path.contains("/api/timedtext") ||
            listOf(".vtt", ".srt", ".ttml", ".dfxp", ".srv1", ".srv2", ".srv3").any { path.endsWith(it) }) return true
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrDefault("")
        val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com")
        return youtubeHost && (url.contains("fmt=", ignoreCase = true) ||
            mediaFile.title.endsWith(".xml", ignoreCase = true) ||
            mediaFile.mimeType.contains("xml", ignoreCase = true) ||
            mediaFile.mimeType.contains("ttml", ignoreCase = true))
    }

    private fun downloadMediaFile(mediaFile: MediaFile) {
        if (mediaFile.url.startsWith("blob:")) {
            val safeTitle = mediaFile.title.replace("\"", "\\\"")
            val safeMime = mediaFile.mimeType.replace("\"", "\\\"")
            val js = getBlobContentScript(
                mediaFile.url,
                true,
                "function(b64) { AndroidWebAPI.saveBlob(b64, \"$safeTitle\", \"$safeMime\"); }",
                "function(err) { AndroidWebAPI.onBlobDownloadError(err); }"
            )
            webView.evaluateJavascript(js, null)
            Toast.makeText(this, "Processing blob download...", Toast.LENGTH_SHORT).show()
            return
        }

        // Download subtitle responses ourselves: YouTube may return TTML/XML behind a .vtt URL.
        val isSubtitleDownload = mediaFile.category == MediaCategory.SUBTITLE ||
            mediaFile.title.endsWith(".vtt", ignoreCase = true) ||
            mediaFile.title.endsWith(".srt", ignoreCase = true) ||
            mediaFile.url.substringBefore("?").substringBefore("#").let { path ->
                path.endsWith(".vtt", true) || path.endsWith(".srt", true) ||
                    path.endsWith(".ttml", true) || path.endsWith(".dfxp", true) ||
                    path.endsWith(".srv1", true) || path.endsWith(".srv2", true) || path.endsWith(".srv3", true)
            }
        if (isSubtitleDownload) {
            // Capture WebView/session state on the main thread before doing network I/O.
            val requestUserAgent = webView.settings.userAgentString
            val requestCookie = CookieManager.getInstance().getCookie(mediaFile.url)
            val requestReferer = mediaFile.referer ?: webView.url ?: "https://www.youtube.com"
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val connection = java.net.URL(mediaFile.url).openConnection() as? java.net.HttpURLConnection
                        ?: throw java.io.IOException("Subtitle URL must use HTTP or HTTPS")
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", requestUserAgent)
                    connection.setRequestProperty("Accept", "text/vtt, application/ttml+xml, application/xml, text/plain, */*")
                    if (!requestCookie.isNullOrBlank()) connection.setRequestProperty("Cookie", requestCookie)
                    if (requestReferer.isNotBlank()) connection.setRequestProperty("Referer", requestReferer)
                    val firstResponse = try {
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            val detail = runCatching {
                                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(300)
                            }.getOrNull().orEmpty()
                            throw java.io.IOException("Subtitle server returned HTTP $status" +
                                if (detail.isNotBlank()) ": $detail" else "")
                        }
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                    // A detected subtitle row can itself point to an HLS playlist. The
                    // playlist is an index, not subtitle text: expand every segment before
                    // normalization so Save writes the whole track, never just the index.
                    val raw = if (firstResponse.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                            .startsWith("#EXTM3U", ignoreCase = true)
                    ) {
                        HlsDownloadHelper.fetchCompleteSubtitleTrackContent(
                            mediaFile.url, requestUserAgent, requestReferer, requestCookie
                        ) ?: throw java.io.IOException("Could not download the complete HLS subtitle track")
                    } else {
                        firstResponse
                    }
                    val normalized = SubtitleUtils.normalizeForDownload(raw)
                    val isVtt = normalized.trimStart().startsWith("WEBVTT", ignoreCase = true)
                    val isSrt = Regex("""(?s).*\d{2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{2}:\d{2}:\d{2},\d{3}.*""")
                        .matches(normalized.trim())
                    if (!isVtt && !isSrt) {
                        val preview = raw.trimStart().take(160).replace(Regex("""\s+"""), " ")
                        throw java.io.IOException("Response is not a recognized subtitle format. Server returned: $preview")
                    }
                    val baseName = mediaFile.title.substringBeforeLast('.', mediaFile.title)
                    val outputName = if (isVtt) "$baseName.vtt" else "$baseName.srt"
                    val outputMime = if (isVtt) "text/vtt" else "application/x-subrip"
                    withContext(Dispatchers.Main) {
                        saveToDownloads(normalized.toByteArray(Charsets.UTF_8), outputName, outputMime)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SubtitleDownload", "Subtitle download/conversion failed for ${mediaFile.url}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Subtitle download failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            Toast.makeText(this, "Downloading and converting subtitle…", Toast.LENGTH_SHORT).show()
            return
        }

        // Adaptive HLS/DASH masters can contain several video resolutions. Ask for
        // an explicit quality before downloading rather than silently accepting the default.
        val path = Uri.parse(mediaFile.url).path.orEmpty().lowercase()
        val candidateForDownload = mediaEngine.getCandidate(mediaFile.url)
        val isHls = path.endsWith(".m3u8") ||
            mediaFile.url.contains("format=m3u8", ignoreCase = true) ||
            mediaFile.url.contains("type=hls", ignoreCase = true) ||
            path.endsWith("/master.txt") || path.contains("/hls/") ||
            mediaFile.mimeType.contains("mpegurl", ignoreCase = true) ||
            candidateForDownload?.mediaKind == MediaKind.HLS_MANIFEST
        val isDash = path.endsWith(".mpd") ||
            mediaFile.url.contains("format=dash", ignoreCase = true) ||
            mediaFile.url.contains("type=dash", ignoreCase = true) ||
            mediaFile.mimeType.contains("dash+xml", ignoreCase = true) ||
            mediaFile.quality.contains("dash", ignoreCase = true) ||
            candidateForDownload?.mediaKind == MediaKind.DASH_MANIFEST

        if (isHls || isDash) {
            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(mediaFile.url)
            HlsDownloadHelper.downloadAdaptive(
                context = this,
                url = mediaFile.url,
                title = mediaFile.title,
                userAgent = userAgent,
                referer = mediaFile.referer ?: webView.url,
                cookie = cookie,
                mimeType = if (isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    else androidx.media3.common.MimeTypes.APPLICATION_MPD
            )
            return
        }

        // Explicit handling for audio URLs to validate them instead of blindly downloading HTTP errors via DownloadManager
        if (mediaFile.category == MediaCategory.AUDIO) {
            android.util.Log.d("AUDIO_DOWNLOAD", "Routing audio download to Progressive Audio Service: ${mediaFile.url}")
            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(mediaFile.url)
            val intent = Intent(this, ProgressiveAudioDownloadService::class.java).apply {
                putExtra(ProgressiveAudioDownloadService.EXTRA_URL, mediaFile.url)
                putExtra(ProgressiveAudioDownloadService.EXTRA_TITLE, mediaFile.title)
                putExtra(ProgressiveAudioDownloadService.EXTRA_USER_AGENT, userAgent)
                putExtra(ProgressiveAudioDownloadService.EXTRA_REFERER, mediaFile.referer ?: webView.url)
                putExtra(ProgressiveAudioDownloadService.EXTRA_COOKIE, cookie)
            }
            startService(intent)
            Toast.makeText(this, "Audio download started: ${mediaFile.title}", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(mediaFile.url))
                .setTitle(mediaFile.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, mediaFile.title)

            // Add User-Agent and Cookies
            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(mediaFile.url)
            request.addRequestHeader("User-Agent", userAgent)
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie)
            }
            if (mediaFile.referer != null) {
                request.addRequestHeader("Referer", mediaFile.referer)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started: ${mediaFile.title}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun addToHistory(url: String) {
        val title = webView.title ?: "No Title"
        val newItem = HistoryItem(url = url, title = title)
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val historyJson = sharedPrefs.getString("HISTORY_V2", "[]")
        val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
        val history: MutableList<HistoryItem> = Gson().fromJson(historyJson, type)
        history.removeAll { it.url == url }
        history.add(0, newItem)
        val newHistoryJson = Gson().toJson(history.take(100))
        sharedPrefs.edit().putString("HISTORY_V2", newHistoryJson).apply()
    }
    private fun showHistory() {
        val intent = Intent(this, HistoryActivity::class.java)
        historyResultLauncher.launch(intent)
    }
    private fun saveTabsState() {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        val gson = Gson()
        saveCurrentTabState()

        // Truncate history before saving to prevent SharedPreferences bloat over time
        for (tab in tabs) {
            if (tab.historyStack.size > 20) {
                tab.historyStack = tab.historyStack.takeLast(20).distinct().toMutableList()
            }
        }

        val tabsJson = gson.toJson(tabs)
        editor.putString("TABS_LIST", tabsJson)
        editor.putInt("CURRENT_TAB_INDEX", currentTabIndex)
        editor.apply()
    }
    private fun loadTabsState(): Boolean {
        val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
        val tabsJson = sharedPrefs.getString("TABS_LIST", null) ?: return false
        return try {
            val type = object : TypeToken<MutableList<Tab>>() {}.type
            val loadedTabs: MutableList<Tab> = Gson().fromJson(tabsJson, type)
            if (loadedTabs.isNotEmpty()) {
                tabs = loadedTabs
                currentTabIndex = sharedPrefs.getInt("CURRENT_TAB_INDEX", 0).coerceIn(0, tabs.size - 1)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }


    private fun showMediaDetectionSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("MEDIA_DETECTION_ENABLED", true)
        val isAutoAnalyze = prefs.getBoolean("AUTO_ANALYZE_MEDIA", false)

        val view = layoutInflater.inflate(R.layout.dialog_media_detection_settings, null)
        val switchDetection = view.findViewById<android.widget.Switch>(R.id.switch_media_detection)
        val switchAutoAnalyze = view.findViewById<android.widget.Switch>(R.id.switch_auto_analyze)

        switchDetection.isChecked = isEnabled
        switchAutoAnalyze.isChecked = isAutoAnalyze

        createThemedDialogBuilder(this)
            .setTitle("Media Detection Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().apply {
                    putBoolean("MEDIA_DETECTION_ENABLED", switchDetection.isChecked)
                    putBoolean("AUTO_ANALYZE_MEDIA", switchAutoAnalyze.isChecked)
                    apply()
                }
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFloatingDetectorSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("FLOATING_DETECTOR_ENABLED", true)
        val minDuration = prefs.getInt("FLOATING_DETECTOR_MIN_DURATION", 0)

        val view = layoutInflater.inflate(R.layout.dialog_floating_detector_settings, null)
        val switchEnabled = view.findViewById<android.widget.Switch>(R.id.switch_floating_detector)
        val editMinDuration = view.findViewById<android.widget.EditText>(R.id.edit_min_duration)

        switchEnabled.isChecked = isEnabled
        editMinDuration.setText(minDuration.toString())

        createThemedDialogBuilder(this)
            .setTitle("Floating Detector Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val duration = editMinDuration.text.toString().toIntOrNull() ?: 0
                prefs.edit().apply {
                    putBoolean("FLOATING_DETECTOR_ENABLED", switchEnabled.isChecked)
                    putInt("FLOATING_DETECTOR_MIN_DURATION", duration)
                    apply()
                }
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                updateFabVisibility() // Apply changes immediately
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomOverflowMenu(anchor: View) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val savedColor = try {
            android.graphics.Color.parseColor(hexColor)
        } catch (e: Exception) {
            android.graphics.Color.BLACK
        }
        val bgColor = android.graphics.Color.rgb(
            android.graphics.Color.red(savedColor),
            android.graphics.Color.green(savedColor),
            android.graphics.Color.blue(savedColor)
        )
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(bgColor)
        val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val mutedColor = if (luminance > 0.5) android.graphics.Color.DKGRAY else android.graphics.Color.LTGRAY
        val density = resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        data class MenuAction(val title: String, val iconRes: Int, val onClick: () -> Unit)
        data class MenuCategory(val title: String, val iconRes: Int, val actions: List<MenuAction>)

        val settingsPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val noticeOn = settingsPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)

        val categories = listOf(
            MenuCategory("Browser", android.R.drawable.ic_menu_view, listOf(
                MenuAction("Back", R.drawable.ic_arrow_back) { navigateBack() },
                MenuAction("Forward", R.drawable.ic_arrow_forward) { navigateForward() },
                MenuAction("History", R.drawable.ic_public) { showHistory() },
                MenuAction("Add Bookmark", R.drawable.ic_add) { addCurrentPageToBookmarks() },
                MenuAction("Add Link", R.drawable.ic_download) { showManualAddLinkDialog() },
                MenuAction("Open in External Browser", R.drawable.ic_public) { openCurrentPageInExternalBrowser() }
            )),
            MenuCategory("Tools", R.drawable.ic_translate, listOf(
                MenuAction(if (isAutoTranslateEnabled) "Auto-Translate: On" else "Auto-Translate: Off", R.drawable.ic_translate) { toggleAutoTranslate() },
                MenuAction("Ask Gemini About This Page", R.drawable.ic_ai_sparkle) { openGeminiForCurrentPage() },
                MenuAction("User Scripts", R.drawable.ic_translate) { startActivity(Intent(this, UserScriptManagerActivity::class.java)) },
                MenuAction("Urban VPN", R.drawable.ic_public) { showUrbanVpnDialog() },
                MenuAction(getString(R.string.proxy_settings), R.drawable.ic_public) { showProxySettingsDialog() },
                MenuAction("Block Ads / Traps on This Page", R.drawable.ic_close) { nukeAdsAndTraps() },
                MenuAction(
                    if (getSharedPreferences("Settings", Context.MODE_PRIVATE).getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)) "YouTube Quick Refresh: On" else "YouTube Quick Refresh: Off",
                    android.R.drawable.ic_popup_sync
                ) { toggleYouTubeQuickRefresh() }
            )),
            MenuCategory("Media & Appearance", R.drawable.ic_play_arrow, listOf(
                MenuAction("Media Detection Settings", R.drawable.ic_play_arrow) { showMediaDetectionSettingsDialog() },
                MenuAction("Floating Detector Settings", R.drawable.ic_play_arrow) { showFloatingDetectorSettingsDialog() },
                MenuAction("Theme Color", android.R.drawable.ic_menu_gallery) { showThemeColorPickerDialog() },
                MenuAction(if (noticeOn) "Popup Notices: On" else "Popup Notices: Off", android.R.drawable.ic_dialog_alert) {
                    val current = settingsPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
                    settingsPrefs.edit().putBoolean("SHOW_POPUP_BLOCKED_NOTICE", !current).apply()
                    Toast.makeText(this, "Popup block notices " + if (!current) "enabled" else "disabled", Toast.LENGTH_SHORT).show()
                }
            )),
            MenuCategory("Developer", android.R.drawable.ic_menu_info_details, listOf(
                MenuAction("API Network Sniffer", R.drawable.ic_public) { launchApiSniffer() },
                MenuAction("Debug Site", android.R.drawable.ic_menu_info_details) { showSiteDebuggingOptions() },
                MenuAction("Debug Page", android.R.drawable.ic_menu_search) { showPageSource() }
            ))
        )

        // Edge-inspired quick actions: the most-used destinations are visible immediately.
        val quickActions = listOf(
            MenuAction("History", R.drawable.ic_public) { showHistory() },
            MenuAction("Add Bookmark", R.drawable.ic_add) { addCurrentPageToBookmarks() },
            MenuAction("Detected Media", R.drawable.ic_download) { showMediaListDialog() },
            MenuAction("Settings", android.R.drawable.ic_menu_manage) { showMasterSettingsDialog() }
        )
        val quickActionTitles = quickActions.map { it.title }.toSet()
        val menuDialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this,
            R.style.TransparentBottomSheetDialogTheme
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(18f, 18f, 18f, 18f, 0f, 0f, 0f, 0f)
                    .map { it * density }.toFloatArray()
            }
        }

        val title = TextView(this).apply {
            text = "Menu"
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        val dragHandle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (luminance > 0.5) 0x55000000 else 0x66FFFFFF)
                cornerRadius = dp(2).toFloat()
            }
            contentDescription = "Drag to resize or dismiss"
        }
        root.addView(dragHandle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun makeTile(action: MenuAction): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                minimumHeight = dp(92)
                setPadding(dp(4), dp(8), dp(4), dp(6))
                isClickable = true
                isFocusable = true
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this@MainActivity,
                    android.R.drawable.list_selector_background
                )

                val icon = android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(action.iconRes)
                    setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    contentDescription = action.title
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }
                addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    bottomMargin = dp(7)
                })

                val label = TextView(this@MainActivity).apply {
                    text = action.title
                    textSize = 12.5f
                    setTextColor(textColor)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = android.view.Gravity.CENTER
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(label, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener {
                    menuDialog.dismiss()
                    action.onClick()
                }
            }
        }

        fun addGrid(actions: List<MenuAction>) {
            actions.chunked(4).forEach { group ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.TOP
                }
                group.forEach { action ->
                    row.addView(makeTile(action), LinearLayout.LayoutParams(0, dp(98), 1f))
                }
                repeat(4 - group.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(98), 1f))
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }

        fun addSectionHeader(label: String) {
            val divider = View(this).apply {
                setBackgroundColor(if (luminance > 0.5) 0x22000000 else 0x33FFFFFF)
            }
            body.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            })
            val heading = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(mutedColor)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(4))
            }
            body.addView(heading, LinearLayout.LayoutParams(-1, -2))
        }

        addSectionHeader("Quick access")
        addGrid(quickActions)

        categories.forEach { category ->
            val remainingActions = category.actions.filterNot { action ->
                action.title in quickActionTitles ||
                    (category.title == "Settings" && action.title == "App Settings")
            }
            if (remainingActions.isNotEmpty()) {
                addSectionHeader(category.title)
                addGrid(remainingActions)
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        menuDialog.setContentView(root)
        menuDialog.setOnShowListener {
            val sheet = menuDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let { bottomSheet ->
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                }
                bottomSheet.requestLayout()
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                // Let the nested ScrollView own vertical gestures instead of dragging the whole sheet.
                behavior.isDraggable = true
            }
        }

        menuDialog.show()
    }




    private fun isYouTubeUrl(url: String): Boolean {
        val host = try { Uri.parse(url).host.orEmpty().lowercase() } catch (_: Exception) { "" }
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
    }

    private fun toggleYouTubeQuickRefresh() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val enabled = !prefs.getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)
        prefs.edit().putBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", enabled).apply()
        Toast.makeText(
            this,
            if (enabled) "YouTube quick refresh enabled" else "YouTube quick refresh disabled",
            Toast.LENGTH_SHORT
        ).show()

        if (enabled) {
            startYouTubeQuickRefreshMonitor(refreshCurrentVideo = true)
        } else {
            quickRefreshMonitorRunnable?.let(handler::removeCallbacks)
            quickRefreshMonitorRunnable = null
            lastQuickRefreshVideoKey = null
        }
    }

    /**
     * YouTube changes videos with client-side navigation, which does not reliably invoke
     * WebViewClient.onPageStarted(). Poll the WebView URL while enabled and refresh once
     * when the canonical video ID changes. Keeping the video key stable prevents reload loops.
     */
    private fun startYouTubeQuickRefreshMonitor(refreshCurrentVideo: Boolean = false) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        if (!getSharedPreferences("Settings", Context.MODE_PRIVATE)
                .getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)) return
        if (quickRefreshMonitorRunnable != null) return

        val currentUrl = webView.url.orEmpty()
        if (isYouTubeVideoUrl(currentUrl)) {
            lastQuickRefreshVideoKey = youtubeVideoKey(currentUrl)
            if (refreshCurrentVideo && webView.visibility == View.VISIBLE) {
                scheduleYouTubeQuickRefresh(currentUrl)
            }
        } else {
            lastQuickRefreshVideoKey = null
        }

        val monitor = object : Runnable {
            override fun run() {
                if (!::webView.isInitialized || isFinishing || isDestroyed) {
                    quickRefreshMonitorRunnable = null
                    return
                }
                if (!getSharedPreferences("Settings", Context.MODE_PRIVATE)
                        .getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)) {
                    quickRefreshMonitorRunnable = null
                    return
                }

                val url = webView.url.orEmpty()
                if (isYouTubeVideoUrl(url)) {
                    val videoKey = youtubeVideoKey(url)
                    if (videoKey != lastQuickRefreshVideoKey) {
                        lastQuickRefreshVideoKey = videoKey
                        if (webView.visibility == View.VISIBLE) {
                            scheduleYouTubeQuickRefresh(url)
                        }
                    }
                } else {
                    lastQuickRefreshVideoKey = null
                }
                handler.postDelayed(this, 350L)
            }
        }
        quickRefreshMonitorRunnable = monitor
        handler.postDelayed(monitor, 350L)
    }

    private fun scheduleYouTubeQuickRefresh(targetUrl: String) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)) return
        if (!isYouTubeUrl(targetUrl)) return
        val uri = Uri.parse(targetUrl)
        if (uri.scheme != "https" && uri.scheme != "http") return

        webView.postDelayed({
            if (isFinishing || isDestroyed || !::webView.isInitialized) return@postDelayed
            val stillEnabled = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                .getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)
            val latestUrl = webView.url.orEmpty()
            if (stillEnabled && webView.visibility == View.VISIBLE &&
                latestUrl == targetUrl && isYouTubeUrl(latestUrl)) {
                webView.reload()
            }
        }, 250L)
    }

    private fun makeMenuRow(
        title: String,
        iconRes: Int,
        textColor: Int,
        density: Float,
        categoryRow: Boolean
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = ((if (categoryRow) 56 else 48) * density).toInt()
            setPadding((12 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
            background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            val icon = android.widget.ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                contentDescription = null
            }
            addView(icon, LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginEnd = (16 * density).toInt()
            })
            val label = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            if (categoryRow) {
                val chevron = android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_media_next)
                    setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    contentDescription = "Open $title"
                }
                addView(chevron, LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()))
            }
        }
    }


    private fun injectTranslateScript(view: WebView?) {

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val targetLang = prefs.getString("translate_target_lang", "en") ?: "en"

        // For sites like dcinside that block translate DOM injections due to CSP or isolated origins,
        // we can force the standard Google Translate URL proxy if the DOM injection fails.
        // However, setting the cookie and hash is the standard way to force the native translation to kick in.

        val translateScript = """
            (function() {
                // DCInside and similar strict CSP domains need explicit googtrans cookies to translate on reload
                if (window.location.host.includes("dcinside.com") && document.cookie.indexOf('googtrans=') === -1) {
                    document.cookie = 'googtrans=/auto/${targetLang}; path=/; domain=.' + window.location.host;
                    document.cookie = 'googtrans=/auto/${targetLang}; path=/';
                    window.location.reload();
                    return;
                }
                // Add CSS to hide the Translate UI and banner
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = `
                    #google_translate_element, .skiptranslate, .goog-te-banner-frame {
                        display: none !important;
                    }
                    body {
                        top: 0 !important;
                    }
                    .goog-tooltip {
                        display: none !important;
                    }
                    .goog-tooltip:hover {
                        display: none !important;
                    }
                    .goog-text-highlight {
                        background-color: transparent !important;
                        border: none !important;
                        box-shadow: none !important;
                    }
                `;
                document.head.appendChild(style);

                if (document.getElementById('google_translate_element')) return;

                var translateDiv = document.createElement('div');
                translateDiv.id = 'google_translate_element';
                // Hidden via CSS above
                document.body.appendChild(translateDiv);

                window.googleTranslateElementInit = function() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'auto',
                        includedLanguages: '${targetLang}',
                        layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                        autoDisplay: true
                    }, 'google_translate_element');

                    // Auto-trigger translation
                    setTimeout(function() {
                        var select = document.querySelector('.goog-te-combo');
                        if (select) {
                            select.value = '${targetLang}';
                            // React-compatible event dispatching for Google Translate widget
                            select.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                            // Sometimes firing change isn't enough for the Google translate widget to catch it if loaded late
                            var event = document.createEvent('HTMLEvents');
                            event.initEvent('change', true, false);
                            select.dispatchEvent(event);

                            // For highly-secure SPAs like dcinside, we must explicitly push the hash update into the URL
                            // or invoke the native translate iframe hash hack.
                            if (window.location.host.includes("dcinside.com")) {
                                window.location.hash = "googtrans(auto|${targetLang})";
                                location.reload();
                            }
                        } else {
                            // If it doesn't exist yet, try to poll for it
                            var poller = setInterval(function() {
                                var retrySelect = document.querySelector('.goog-te-combo');
                                if (retrySelect) {
                                    clearInterval(poller);
                                    retrySelect.value = '${targetLang}';
                                    retrySelect.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));

                                    if (window.location.host.includes("dcinside.com")) {
                                        window.location.hash = "googtrans(auto|${targetLang})";
                                        location.reload();
                                    }
                                }
                            }, 500);
                            // Stop polling after 5 seconds to prevent memory leaks
                            setTimeout(function() { clearInterval(poller); }, 5000);
                        }
                    }, 1000);
                };

                var script = document.createElement('script');
                script.type = 'text/javascript';
                script.src = '//translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                document.head.appendChild(script);
            })();
        """.trimIndent()
        view?.evaluateJavascript(translateScript, null)
    }

    private fun nukeAdsAndTraps() {
        val script = """
            (function() {
                try {
                    // 1. Remove sneaky/tracking iframes, but preserve main content iframes (like video players)
                    document.querySelectorAll('iframe').forEach(iframe => {
                        const style = window.getComputedStyle(iframe);
                        const isTiny = iframe.offsetWidth < 50 || iframe.offsetHeight < 50;
                        const isHidden = style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.1;
                        if (isTiny || isHidden) {
                            iframe.remove();
                        }
                    });

                    // 2. Kill transparent/overlay divs acting as click-traps (while protecting video players)
                    document.querySelectorAll('div').forEach(div => {
                        const style = window.getComputedStyle(div);
                        const isOverlay = style.position === 'absolute' || style.position === 'fixed';
                        const isTransparent = parseFloat(style.opacity) < 0.1 || style.backgroundColor === 'rgba(0, 0, 0, 0)' || style.backgroundColor === 'transparent';
                        const hasHighZIndex = parseInt(style.zIndex, 10) > 1000;

                        if (isOverlay && (isTransparent || hasHighZIndex)) {
                            // Don't remove if it contains a video, iframe, or is a video control container
                            if (div.querySelector('video') || div.querySelector('iframe') || div.className.toLowerCase().includes('vjs') || div.className.toLowerCase().includes('jwplayer')) {
                                return;
                            }
                            // Also don't remove if it's explicitly part of the media player structure
                            if (div.closest('video') || div.closest('iframe')) {
                                return;
                            }

                            // Only remove large invisible overlays (click traps) or obtrusive ads
                            if (div.offsetWidth > window.innerWidth * 0.5 && div.offsetHeight > window.innerHeight * 0.5) {
                                div.remove();
                            } else if (hasHighZIndex && isTransparent) {
                                div.remove();
                            }
                        }
                    });

                    // 3. Force all links to open in the SAME tab to prevent popup redirects, and strip inline onClick handlers
                    document.querySelectorAll('a').forEach(link => {
                        if (link.hasAttribute('target')) {
                            link.removeAttribute('target');
                        }
                        if (link.hasAttribute('onclick')) {
                            link.removeAttribute('onclick');
                        }
                        // Replace node to kill jQuery/React bound click listeners
                        const clone = link.cloneNode(true);
                        link.parentNode.replaceChild(clone, link);
                    });

                    return "success";
                } catch(e) {
                    return "error";
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Toast.makeText(this, "Nuked Ads and Traps from page!", Toast.LENGTH_SHORT).show()
        }
    }







    private fun injectTelemetryScript() {
        val script = """
            (function () {
                if (window.telemetryIntervalId) return;
                window.telemetryIntervalId = setInterval(() => {
                    try {
                        const videos = Array.from(document.querySelectorAll("video"));
                        if (videos.length === 0) return;

                        const telemetryData = videos.map((video, index) => {
                            const rect = video.getBoundingClientRect();
                            const style = window.getComputedStyle(video);

                            const visibleWidth = Math.max(0, Math.min(rect.right, window.innerWidth) - Math.max(rect.left, 0));
                            const visibleHeight = Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
                            const visibleArea = visibleWidth * visibleHeight;
                            const viewportArea = window.innerWidth * window.innerHeight;

                            return {
                                index: index,
                                src: video.currentSrc || video.src || "",
                                width: video.videoWidth || 0,
                                height: video.videoHeight || 0,
                                duration: Number.isFinite(video.duration) ? video.duration : 0,
                                currentTime: video.currentTime || 0,
                                paused: video.paused,
                                muted: video.muted,
                                autoplay: video.autoplay,
                                controls: video.controls,
                                fullscreen: document.fullscreenElement === video,
                                visibleAreaRatio: viewportArea > 0 ? visibleArea / viewportArea : 0,
                                displayedWidth: rect.width,
                                displayedHeight: rect.height,
                                display: style.display,
                                visibility: style.visibility,
                                opacity: parseFloat(style.opacity || "1")
                            };
                        });

                        if (window.AndroidMediaState && window.AndroidMediaState.onPlayerTelemetry) {
                            window.AndroidMediaState.onPlayerTelemetry(JSON.stringify(telemetryData));
                        }
                    } catch (e) {
                        console.error("Telemetry error", e);
                    }
                }, 1500);
            })();
        """
        webView.evaluateJavascript(script, null)
    }

    private fun injectStandardMediaDetector() {
        injectTelemetryScript()
        val script = """
            (function() {
                const media = [];


                function isAdOrPreview(el) {
                    if (!el) return false;
                    if (el.offsetWidth > 0 && el.offsetWidth < 100) return true;
                    if (el.offsetHeight > 0 && el.offsetHeight < 100) return true;
                    if (el.duration && el.duration > 0 && el.duration < 45) return true;
                    let curr = el;
                    while (curr && curr !== document.body) {
                        const style = window.getComputedStyle(curr);
                        if (style.display === 'none' || style.visibility === 'hidden') return true;
                        const className = (curr.className && typeof curr.className === 'string') ? curr.className.toLowerCase() : '';
                        const id = (curr.id && typeof curr.id === 'string') ? curr.id.toLowerCase() : '';
                        if (className.includes('video-thumb-wrapper') || className.includes('thumb') || className.includes('preview')) return true;
                        if (id.includes('msgnativewidget') || className.includes('widget') || className.includes('ad-')) return true;
                        curr = curr.parentElement;
                    }
                    return false;
                }

                function extractMediaUrls(str) {
                    if (!str) return;
                    try {
                        const decoded = decodeURIComponent(str);
                        const candidates = [str, decoded];
                        if (str.includes(".m3u8")) {
                            const regexM3u8 = /(https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*)/i;
                            let match = regexM3u8.exec(str);
                            if (match) { if(window.AndroidMediaState) { window.AndroidMediaState.onMediaDetected(match[1], 'video'); } }
                        }
                        const regex = /https?:\/\/[^"'\s<>]+\.(m3u8|mp4|mkv|webm|mpd)([^"'\s<>]*)/gi;

                        candidates.forEach(s => {
                            let match;
                            while ((match = regex.exec(s)) !== null) {
                                if (window.AndroidMediaState) { window.AndroidMediaState.onMediaDetected(match[0], 'video'); }
                            }
                        });
                    } catch(e) {}
                }

                // 1. Scan all iframes in the current document for embedded media URLs
                document.querySelectorAll('iframe').forEach(iframe => {
                    let iframeSrc = iframe.src || iframe.getAttribute('data-src') || iframe.getAttribute('data-link');
                    if (iframeSrc && iframeSrc !== 'about:blank' && !iframeSrc.includes('recaptcha') && !iframeSrc.includes('facebook.com') && !iframeSrc.includes('twitter.com')) {
                        if (window.AndroidMediaState && window.AndroidMediaState.onIframeFound) {
                            window.AndroidMediaState.onIframeFound(iframeSrc);
                        }
                    }
                    extractMediaUrls(iframe.src);
                });

                // 2. Recursive scan for media elements
                function addMedia(url, title, type, language) {
                     if(!url) return;
                     url = url.trim();
                     if(url.startsWith('//')) url = 'https:' + url;
                     if(window.AndroidMediaState) {
                         window.AndroidMediaState.onMediaDetected(url, type || 'video');
                     }
                }

                // 1. Variable Scan (MacCMS / player_aaaa)
                try {
                    if (window.player_aaaa && window.player_aaaa.url) {
                        addMedia(window.player_aaaa.url, (window.player_aaaa.vod_data && window.player_aaaa.vod_data.vod_name) || document.title, 'video');
                    }
                     if (window.player_aaaa && window.player_aaaa.url_next) {
                        addMedia(window.player_aaaa.url_next, ((window.player_aaaa.vod_data && window.player_aaaa.vod_data.vod_name) || document.title) + " - Next", 'video');
                    }
                } catch(e) {}

                // 2. Iframe Src Query Param Scan
                try {
                    document.querySelectorAll('iframe').forEach(iframe => {
                        const src = iframe.src;
                        if (src) {
                            const match = src.match(/(https?%3A%2F%2F|https?:\/\/)[^&"'<>\s]+(\.m3u8|\.mp4|\.mkv)[^&"'<>\s]*/i);
                            if (match) {
                                let extractedUrl = match[0];
                                if (extractedUrl.includes('%3A') || extractedUrl.includes('%2F')) {
                                    extractedUrl = decodeURIComponent(extractedUrl);
                                }
                                addMedia(extractedUrl, document.title + " (Embedded)", 'video');
                            }
                        }
                    });
                } catch(e) {}

                // 3. Script Tag Regex Scan
                try {
                     const urlRegex = /["'](https?:\/\/[^"']+\.(?:m3u8|mp4|mkv)(?:\?[^"']*)?)["']/gi;
                     document.querySelectorAll('script').forEach(script => {
                         if (script.textContent) {
                             let match;
                             while ((match = urlRegex.exec(script.textContent)) !== null) {
                                 let extractedUrl = match[1];
                                 if (!extractedUrl.includes('example.com')) {
                                     addMedia(extractedUrl, document.title + " (Script Scan)", 'video');
                                 }
                             }
                         }
                     });
                } catch(e) {}

                // 4. Targeted scan for the specific video iframe
                const kisscloudFrame = Array.from(document.querySelectorAll('iframe')).find(f => f.src.includes('kisscloud.online'));
                if (kisscloudFrame && kisscloudFrame.src) {
                     addMedia(kisscloudFrame.src, document.title, 'video');
                }

                // 5. General, recursive scan for all other media, especially subtitles
                const processedFrames = new Set();
                function searchFrames(win) {
                    if (processedFrames.has(win)) return;
                    processedFrames.add(win);
                    try {
                        // Scan for standard video/audio/source tags
                        win.document.querySelectorAll('video, audio, source').forEach(el => {
                            if (isAdOrPreview(el)) return;
                            if (el.src && typeof el.src === 'string' && el.src.trim() !== '') {
                                try {
                                    const absUrl = new URL(el.src, win.document.baseURI).href;
                                     addMedia(absUrl, el.title || win.document.title, 'video');
                                } catch(e) {}
                            }
                        });
                        // Scan specifically for subtitle tracks
                        win.document.querySelectorAll('track').forEach(track => {
                            if (track.src && typeof track.src === 'string' && track.src.trim() !== '' && (track.kind === 'subtitles' || track.kind === 'captions')) {
                                try {
                                    const lang = track.srclang || '';
                                    const label = track.label || 'Subtitle';

                                    if (track.src.startsWith("blob:")) {
                                        // Use a Set to avoid redundant fetching
                                        if (!window._processedBlobSubtitles) window._processedBlobSubtitles = new Set();
                                        if (!window._processedBlobSubtitles.has(track.src)) {
                                            window._processedBlobSubtitles.add(track.src);
                                            fetch(track.src).then(res => {
                                                if (res.ok) return res.text();
                                                throw new Error("Blob fetch failed");
                                            }).then(text => {
                                                if (text && window.AndroidMediaState && window.AndroidMediaState.onSubtitleBlobExtracted) {
                                                    // Pass the actual content over the bridge
                                                    window.AndroidMediaState.onSubtitleBlobExtracted(text, track.src, lang, label);
                                                }
                                            }).catch(err => {
                                                console.error("[SUBTITLE_BLOB] Failed to extract blob subtitle: " + track.src + " - " + err.message);
                                                window._processedBlobSubtitles.delete(track.src); // Allow retry if page recovers
                                            });
                                        }
                                    } else {
                                        const absUrl = new URL(track.src, win.document.baseURI).href;
                                        addMedia(absUrl, label, 'subtitle', lang);
                                    }
                                } catch (e) {}
                            }
                        });

                        for (let i = 0; i < win.frames.length; i++) {
                            searchFrames(win.frames[i]);
                        }
                    } catch (e) {}
                }
                searchFrames(window);

                // De-duplicate results
                const uniqueMedia = Array.from(new Map(media.map(item => [item.url, item])).values());
                return JSON.stringify(uniqueMedia);
            })();
        """

        webView.evaluateJavascript(script) { result ->

            // Second press: Gathers results from the now-active script.
            try {
                val unescapedResult = result.substring(1, result.length - 1).replace("\\\"", "\"")

                val gson = Gson()
                val type = object : TypeToken<List<Map<String, String>>>() {}.type
                val foundMedia: List<Map<String, String>> = gson.fromJson(unescapedResult, type)

                if (foundMedia.isNotEmpty()) {
                    val newMediaFiles = foundMedia.mapNotNull {
                        val url = it["url"] ?: return@mapNotNull null
                        val title = it["title"] ?: "Untitled"
                        val type = it["type"] ?: "video"
                        val language = it["language"]

                        val category = if (type == "video") {
                            if (url.endsWith(".vtt") || url.endsWith(".srt") || url.contains(".vtt?") || url.contains(".srt?")) {
                                MediaCategory.SUBTITLE
                            } else {
                                MediaCategory.VIDEO
                            }
                        } else MediaCategory.SUBTITLE
                        var detectedFormat = detectVideoFormat(url)

                        if (category == MediaCategory.SUBTITLE && (url.startsWith("blob:") || detectedFormat.extension == ".mp4")) {
                            detectedFormat = VideoFormat(".vtt", "text/vtt")
                        }

                        val quality = extractQualityFromUrl(url)

                        val finalTitle = if (category == MediaCategory.SUBTITLE && title != "Untitled" && title != "Subtitle") {
                             // Use the subtitle label as filename
                             "$title${detectedFormat.extension}"
                        } else {
                             generateSmartFileName(url, detectedFormat.extension, quality, category)
                        }

                        if (category == MediaCategory.SUBTITLE) {
                            if (url.startsWith("blob:")) {
                                // Inject script to fetch blob content
                                val js = """
                                    (function() {
                                        fetch("$url").then(r => r.text()).then(text => {
                                            var snippet = "";
                                            var language = "";
                                            var lines = text.split('\n');
                                            for(var i=0; i<lines.length; i++) {
                                                var line = lines[i].replace(/^\uFEFF/, '').trim();
                                                if(!line) continue;

                                                if(line.toLowerCase().includes("webvtt")) continue;

                                                var langMatch = line.match(/^Language:\s*([a-zA-Z-]+)/i);
                                                if(langMatch) {
                                                    language = langMatch[1];
                                                    continue;
                                                }

                                                if(line.match(/^(Kind|Style|Region|NOTE):/i)) continue;
                                                if(line.match(/^\d+$/)) continue; // Index
                                                if(line.includes("-->")) continue; // Timestamp

                                                snippet = line.replace(/<[^>]*>/g, '').replace(/[♪♫]/g, '').trim();
                                                if(snippet) break;
                                            }
                                            if(snippet.length > 50) snippet = snippet.substring(0, 50) + "...";
                                            if(snippet) AndroidWebAPI.onSubtitleSnippet("$url", snippet, language);
                                        });
                                    })();
                                """
                                runOnUiThread { webView.evaluateJavascript(js, null) }
                            } else {
                                // For HTTP URLs, we can use the native fetcher
                                // But we need to do it after adding to the list
                                // The native fetcher is called below in the synchronized block logic?
                                // No, manualMediaScan logic constructs the list and adds it.
                                // So we need to trigger it after adding.
                            }
                        }

                        MediaFile(
                            url = url,
                            title = finalTitle,
                            mimeType = detectedFormat.mimeType,
                            quality = quality,
                            category = category,
                            fileSize = "Unknown",
                            language = language,
                            isMainContent = isMainVideoContent(url)
                        )
                    }

                    var addedCount = 0
                    synchronized(detectedMediaFiles) {
                        val existingUrls = detectedMediaFiles.map { SubtitleUtils.canonicalMediaUrl(it.url) }.toMutableSet()
                        newMediaFiles.forEach {
                            if (existingUrls.add(SubtitleUtils.canonicalMediaUrl(it.url))) {
                                detectedMediaFiles.add(it)
                                addedCount++
                                // Trigger http fetch if needed
                                if (it.category == MediaCategory.SUBTITLE) {
                                    if (!it.url.startsWith("blob:")) {
                                        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
                                        if (prefs.getBoolean("AUTO_ANALYZE_MEDIA", false)) {
                                            fetchSubtitleSnippet(it)
                                        }
                                    } else {
                                        // For blobs found via Manual Scan, we need to re-inject the fetcher
                                        // because the previous loop only injected it for *newly constructed* objects
                                        // Wait, the previous loop injected it immediately upon construction.
                                        // But that was before it was added to detectedMediaFiles.
                                        // Actually, the previous injection in the mapNotNull block handles the blob fetching
                                        // because it runs regardless of whether the file is "new" to the list or not.
                                        // However, mapNotNull creates the MediaFile object.
                                        // The injection block I added in the previous step is inside mapNotNull loop.
                                        // So it runs for every candidate found by JS.
                                        // This is correct.
                                        // But if the file *already existed* in the list, we might want to refresh it?
                                        // The current logic says: if !existingUrls.contains(it.url) -> add it.
                                        // If it already exists, we do nothing.
                                        // So if we run scan twice, the second time we don't re-fetch. This is efficient.
                                    }
                                }
                            }
                        }
                    }

                    runOnUiThread {
                        if (addedCount > 0) {
                            Toast.makeText(this, "$addedCount new media item(s) found!", Toast.LENGTH_SHORT).show()
                            if (isManualScanPending) {
                                showMediaListDialog()
                                isManualScanPending = false
                            }
                        } else {
                            if (isManualScanPending) {
                                Toast.makeText(this, "No new media found.", Toast.LENGTH_SHORT).show()
                                showMediaListDialog()
                                isManualScanPending = false
                            }
                        }
                    }
                } else {
                    // Suppressed "No media detected" toast to avoid spamming user on every page load
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error parsing media scan result", e)
                runOnUiThread {
                    Toast.makeText(this, "Error scanning for media.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }



    private fun launchApiSniffer() {
        Toast.makeText(this, "Injecting API Sniffer...", Toast.LENGTH_SHORT).show()
        val script = """
            (function() {
                if (window._apiSnifferInjected) {
                    alert("API Sniffer is already running! Look at the logs.");
                    return;
                }
                window._apiSnifferInjected = true;

                // Override Fetch
                const originalFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : 'unknown');
                    try {
                        if (window.AndroidMediaState && window.AndroidMediaState.onApiSniffed) {
                            window.AndroidMediaState.onApiSniffed(url, "fetch");
                        }
                    } catch(e) {}
                    return originalFetch.apply(this, args);
                };

                // Override XHR
                const originalOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._url = url;
                    return originalOpen.apply(this, arguments);
                };

                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function(...args) {
                    try {
                        if (this._url && window.AndroidMediaState && window.AndroidMediaState.onApiSniffed) {
                            window.AndroidMediaState.onApiSniffed(this._url, "xhr");
                        }
                    } catch(e) {}
                    return originalSend.apply(this, args);
                };

                alert("API Sniffer Injected! Make some requests on the page, then check your logs.");
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)

        // Open the Sniffer Log Dialog automatically
        showApiSnifferLogDialog()
    }

    private val sniffedApiLogs = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun showApiSnifferLogDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentHex = prefs.getString("glossy_theme_color", "#A0000000")
        val textColor = if (androidx.core.graphics.ColorUtils.calculateLuminance(android.graphics.Color.parseColor(currentHex)) > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        val listView = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, sniffedApiLogs)
            setOnItemClickListener { _, _, position, _ ->
                val fullText = sniffedApiLogs[position]
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("API URL", fullText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = createThemedDialogBuilder(this)
            .setTitle("Intercepted APIs (Click to Copy)")
            .setView(listView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                sniffedApiLogs.clear()
            }
            .show()

        // We will keep a reference to auto-update the list if new APIs come in while dialog is open?
        // Let's just let them re-open it or we could notifyDataSetChanged on the adapter.
    }

    private fun showPageSource() {
        val script = """
            (function() {
                let html = 'Main Page Source:\\n\\n' + document.documentElement.outerHTML;
                const frames = document.querySelectorAll('iframe');
                frames.forEach((frame, index) => {
                    try {
                        html += '\\n\\n' + '--- Iframe ' + index + ' Source: ---' + '\\n\\n' + frame.contentDocument.documentElement.outerHTML;
                    } catch(e) {
                        html += '\\n\\n' + '--- Iframe ' + index + ' Source (Cross-origin): ---' + '\\n\\n' + 'Could not access content.';
                    }
                });
                return html;
            })();
        """
        webView.evaluateJavascript(script) { html ->
            val unescapedHtml = html?.substring(1, html.length - 1)?.replace("\\u003C", "<")?.replace("\\n", "\n")?.replace("\\t", "\t")?.replace("\\\"", "\"")
            showDebugInfoDialog(unescapedHtml ?: "Could not retrieve page source.")
        }
    }

    private fun showDebugInfoDialog(source: String) {
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = source
            setTextIsSelectable(true)
            setPadding(40, 40, 40, 40)
        }
        scrollView.addView(textView)

        createThemedDialogBuilder(this)
            .setTitle("Page Debug Info")
            .setView(scrollView)
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Page Source", source)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Source copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showManualAddLinkDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val urlInput = android.widget.EditText(this).apply {
            hint = "https://example.com/video.mp4"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(urlInput)

        val titleInput = android.widget.EditText(this).apply {
            hint = "Optional Filename / Title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }
        layout.addView(titleInput)

        createThemedDialogBuilder(this)
            .setTitle("Add Link")
            .setView(layout)
            .setPositiveButton("Add / Download") { _, _ ->
                val rawUrl = urlInput.text.toString().trim()
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    Toast.makeText(this, "Invalid URL scheme. Must be http/https", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val title = titleInput.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?: rawUrl.substringAfterLast("/").substringBefore("?")
                    .takeIf { it.isNotEmpty() } ?: "Manual_Download_${System.currentTimeMillis()}"

                val category = MediaCategory.fromUrl(rawUrl)
                val isManifest = rawUrl.lowercase().contains(".m3u8") || rawUrl.lowercase().contains(".mpd")

                val manualFile = MediaFile(
                    url = rawUrl,
                    title = title,
                    mimeType = if (isManifest) "application/x-mpegurl" else "video/mp4",
                    quality = "Auto",
                    category = if (category == MediaCategory.UNKNOWN && isManifest) MediaCategory.VIDEO else category,
                    fileSize = "Unknown",
                    language = null,
                    isMainContent = true
                )

                // Add to our standard detected list so it shows in the UI and can be downloaded normally
                if (!detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(manualFile.url) }) {
                    detectedMediaFiles.add(0, manualFile)
                    updateFabVisibility()
                }

                // Immediately trigger the download prompt
                showRenameDialog(manualFile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addCurrentPageToBookmarks() {
        if (webView.visibility == View.VISIBLE && !webView.url.isNullOrEmpty()) {
            val url = webView.url!!
            val title = webView.title ?: "No Title"
            lifecycleScope.launch(Dispatchers.IO) {
                db.bookmarkDao().insert(Bookmark(title = title, url = url))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                    loadBookmarks()
                }
            }
        } else {
            Toast.makeText(this, "No page loaded to bookmark", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUrbanVpnDialog() {
        val connected = UrbanVpnManager.status()
        if (connected != null) {
            createThemedDialogBuilder(this)
                .setTitle("Urban VPN")
                .setMessage("Connected to ${connected.countryName}\nExit proxy: ${connected.exitProxy}\n\nThis prototype currently routes WebView traffic only.")
                .setPositiveButton("Disconnect") { _, _ ->
                    UrbanVpnManager.disconnect()
                    applyProxy()
                    Toast.makeText(this, "Urban VPN disconnected", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val loading = createThemedDialogBuilder(this)
            .setTitle("Urban VPN")
            .setMessage("Loading country servers from Urban VPN…")
            .setNegativeButton("Cancel", null)
            .show()
        lifecycleScope.launch {
            try {
                val locations = withContext(Dispatchers.IO) { UrbanVpnManager.fetchLocations() }
                if (isFinishing || isDestroyed) return@launch
                loading.dismiss()
                val labels = locations.map { "${it.countryName} (${it.countryCode})" }.toTypedArray()
                createThemedDialogBuilder(this@MainActivity)
                    .setTitle("Choose Urban VPN country")
                    .setItems(labels) { _, index ->
                        val selected = locations[index]
                        val progress = createThemedDialogBuilder(this@MainActivity)
                            .setTitle("Connecting to ${selected.countryName}")
                            .setMessage("Requesting proxy credentials and starting the local relay…")
                            .setNegativeButton("Cancel", null)
                            .show()
                        lifecycleScope.launch {
                            try {
                                val verified = withContext(Dispatchers.IO) {
                                    val info = UrbanVpnManager.connect(selected)
                                    try { info to UrbanVpnManager.verifyExitIp() }
                                    catch (error: Exception) { UrbanVpnManager.disconnect(); throw error }
                                }
                                applyProxy()
                                progress.dismiss()
                                createThemedDialogBuilder(this@MainActivity)
                                    .setTitle("Urban VPN connected")
                                    .setMessage("Selected: ${verified.first.countryName}\nExit IP: ${verified.second}\n\nWebView traffic is routed through the local relay. Native download/player routing is not yet included.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } catch (e: Exception) {
                                withContext(Dispatchers.IO) { UrbanVpnManager.disconnect() }
                                applyProxy()
                                progress.dismiss()
                                createThemedDialogBuilder(this@MainActivity)
                                    .setTitle("Urban VPN connection failed")
                                    .setMessage(e.message ?: "The Urban VPN backend rejected the request or the local proxy could not start.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                loading.dismiss()
                if (!isFinishing && !isDestroyed) {
                    createThemedDialogBuilder(this@MainActivity)
                        .setTitle("Urban VPN unavailable")
                        .setMessage(e.message ?: "Could not load Urban VPN country servers.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showProxySettingsDialog() {
        val sharedPrefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        val currentHost = sharedPrefs.getString("proxy_host", "")
        val currentPort = sharedPrefs.getInt("proxy_port", -1)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val explanation = TextView(this).apply {
            text = "Enter the hostname or IP address and port from a proxy provider. This does not connect to Urban VPN by itself. The current setting targets WebView traffic; native downloads and player requests may use separate network clients."
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        dialogView.addView(
            explanation,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        )

        val hostInput = EditText(this).apply {
            hint = "Proxy host or IP (without http://)"
            setText(currentHost)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dialogView.addView(
            hostInput,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val portInput = EditText(this).apply {
            hint = "Proxy port (1–65535)"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            if (currentPort in 1..65535) setText(currentPort.toString())
        }
        dialogView.addView(
            portInput,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        createThemedDialogBuilder(this)
            .setTitle("WebView Proxy Settings")
            .setView(dialogView)
            .setPositiveButton("Save and Apply", null)
            .setNegativeButton("Disable Proxy") { _, _ ->
                sharedPrefs.edit()
                    .remove("proxy_host")
                    .remove("proxy_port")
                    .apply()
                applyProxy()
                Toast.makeText(this, "WebView proxy disabled", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val host = hostInput.text.toString().trim()
                        val portText = portInput.text.toString().trim()
                        val port = portText.toIntOrNull()
                        val invalidHost = host.isBlank() ||
                            host.contains("://") ||
                            host.any { it.isWhitespace() || it == '/' || it == '\\' || it == '@' || it == ':' }

                        if (invalidHost || port == null || port !in 1..65535) {
                            Toast.makeText(
                                this,
                                "Enter a hostname/IP without a scheme and a port from 1 to 65535.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }

                        sharedPrefs.edit()
                            .putString("proxy_host", host)
                            .putInt("proxy_port", port)
                            .apply()
                        dialog.dismiss()
                        applyProxy()
                    }
                }
                dialog.show()
            }
    }
    private fun showMasterSettingsDialog() {
        data class SettingsAction(val title: String, val iconRes: Int, val onClick: () -> Unit)

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val hexColor = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val savedColor = try { android.graphics.Color.parseColor(hexColor) } catch (e: Exception) { android.graphics.Color.BLACK }
        val bgColor = android.graphics.Color.rgb(
            android.graphics.Color.red(savedColor),
            android.graphics.Color.green(savedColor),
            android.graphics.Color.blue(savedColor)
        )
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(bgColor)
        val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        val mutedColor = if (luminance > 0.5) android.graphics.Color.DKGRAY else android.graphics.Color.LTGRAY
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.TransparentBottomSheetDialogTheme)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(18f,18f,18f,18f,0f,0f,0f,0f).map { it * density }.toFloatArray()
            }
        }
        val title = TextView(this).apply {
            text = "Settings"
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        val dragHandle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (luminance > 0.5) 0x55000000 else 0x66FFFFFF)
                cornerRadius = dp(2).toFloat()
            }
            contentDescription = "Drag to resize or dismiss"
        }
        root.addView(dragHandle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun addSettingsHeader(label: String) {
            val divider = View(this@MainActivity).apply {
                setBackgroundColor(if (luminance > 0.5) 0x22000000 else 0x33FFFFFF)
            }
            body.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            })
            body.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(mutedColor)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(4))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        fun addSettingsAction(label: String, iconRes: Int, onClick: () -> Unit) {
            val row = makeMenuRow(label, iconRes, textColor, density, false)
            row.minimumHeight = dp(48)
            row.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        fun addInlineSwitch(
            label: String,
            iconRes: Int,
            checkedInitially: Boolean,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                isClickable = true
                isFocusable = true
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this@MainActivity,
                    android.R.drawable.list_selector_background
                )
            }
            val icon = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                contentDescription = null
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(14)
            })
            val textView = TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(textColor)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            row.addView(textView, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = com.google.android.material.switchmaterial.SwitchMaterial(this@MainActivity).apply {
                isChecked = checkedInitially
                contentDescription = label
                setOnCheckedChangeListener { _, checked -> onChanged(checked) }
            }
            row.addView(toggle, LinearLayout.LayoutParams(-2, -2))
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        // Keep a small number of descriptive sections, but don't hide actions behind category screens.

            addSettingsHeader("Quick toggles")
            addInlineSwitch(
                "Floating media detector",
                android.R.drawable.ic_menu_view,
                prefs.getBoolean("FLOATING_DETECTOR_ENABLED", true)
            ) { checked ->
                prefs.edit().putBoolean("FLOATING_DETECTOR_ENABLED", checked).apply()
                updateFabVisibility()
                Toast.makeText(this, if (checked) "Floating detector enabled" else "Floating detector disabled", Toast.LENGTH_SHORT).show()
            }
            addInlineSwitch(
                "YouTube quick refresh",
                android.R.drawable.ic_popup_sync,
                prefs.getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false)
            ) { checked ->
                if (prefs.getBoolean("YOUTUBE_QUICK_REFRESH_ENABLED", false) != checked) {
                    toggleYouTubeQuickRefresh()
                }
            }
            val blockerPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
            addInlineSwitch(
                "Popup blocked notices",
                android.R.drawable.ic_dialog_alert,
                blockerPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
            ) { checked ->
                blockerPrefs.edit().putBoolean("SHOW_POPUP_BLOCKED_NOTICE", checked).apply()
                Toast.makeText(this, if (checked) "Popup notices enabled" else "Popup notices disabled", Toast.LENGTH_SHORT).show()
            }

            addSettingsHeader("Privacy & Blocking")
            addSettingsAction("Content Blocking", android.R.drawable.ic_menu_close_clear_cancel) { showContentBlockingDialog() }
            addSettingsAction("Manage Blocked Sites", android.R.drawable.ic_delete) { showBlockedSitesDialog() }
            addSettingsAction("Manage Whitelist", android.R.drawable.ic_menu_agenda) { showWhitelistManagementDialog() }
            addSettingsAction("Popup & Redirect Blocker", android.R.drawable.ic_dialog_alert) { showPopupBlockerSettingsDialog() }
            addSettingsAction("Confirm Navigation", android.R.drawable.ic_menu_set_as) { showConfirmNavigationSettingsDialog() }

            addSettingsHeader("Media & Downloads")
            addSettingsAction("Media Detection", android.R.drawable.ic_media_play) { showMediaDetectionSettingsDialog() }
            addSettingsAction("Floating Detector", android.R.drawable.ic_menu_view) { showFloatingDetectorSettingsDialog() }
            addSettingsAction("Background Loading", android.R.drawable.ic_media_ff) { showBackgroundLoadingDialog() }
            addSettingsAction("Clear Video Cache", android.R.drawable.ic_menu_delete) { HlsDownloadHelper.clearUnifiedCache(this)
                    Toast.makeText(this, "Video cache cleared", Toast.LENGTH_SHORT).show() }

            addSettingsHeader("AI & Personalization")
            addSettingsAction("Gemini AI Settings", android.R.drawable.ic_menu_edit) { showGeminiSettingsDialog() }

            addSettingsHeader("Data & Diagnostics")
            addSettingsAction("Backup & Restore", android.R.drawable.ic_menu_save) { showBackupRestoreDialog() }
            addSettingsAction("View App Logs", android.R.drawable.ic_menu_info_details) { startActivity(Intent(this, LogcatViewerActivity::class.java)) }
            addSettingsAction("View Export Logs", android.R.drawable.ic_menu_search) { showExportLogsDialog() }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { bottomSheet ->
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                }
                bottomSheet.requestLayout()
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = true
            }
        }
        dialog.show()
    }


    private fun showConfirmNavigationSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isConfirmNavEnabled = prefs.getBoolean("CONFIRM_NAVIGATION_ENABLED", false)

        val message = "When enabled, the browser will ask for your permission before navigating to a completely different top-level website.\n\nThis is highly effective at stopping silent invisible ad redirects, but may be slightly annoying on sites with frequent legitimate outgoing links.\n\nCurrent state: " + if (isConfirmNavEnabled) "ON" else "OFF"

        createThemedDialogBuilder(this)
            .setTitle("Confirm Navigation Settings")
            .setMessage(message)
            .setPositiveButton(if (isConfirmNavEnabled) "Disable" else "Enable") { _, _ ->
                prefs.edit().putBoolean("CONFIRM_NAVIGATION_ENABLED", !isConfirmNavEnabled).apply()
                android.widget.Toast.makeText(this, "Confirm Navigation " + if (!isConfirmNavEnabled) "enabled" else "disabled", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showExportLogsDialog() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val logFile = java.io.File(filesDir, "export_logs.txt")
            val lines = if (logFile.exists()) logFile.readLines().takeLast(1500) else emptyList()
            val entries = mutableListOf<String>()
            val current = StringBuilder()
            val startPattern = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\]\s+Starting export for:""")

            lines.forEach { line ->
                if (startPattern.containsMatchIn(line) && current.isNotEmpty()) {
                    entries.add(current.toString().trim())
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
            if (current.isNotEmpty()) entries.add(current.toString().trim())

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val density = resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val scroll = android.widget.ScrollView(this@MainActivity).apply {
                    clipToPadding = false
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val content = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

                if (entries.isEmpty()) {
                    content.addView(android.widget.TextView(this@MainActivity).apply {
                        text = if (logFile.exists()) "No export entries found." else "No export logs found."
                        setPadding(dp(12), dp(16), dp(12), dp(16))
                    })
                } else {
                    entries.asReversed().forEachIndexed { reverseIndex, entry ->
                        val firstLine = entry.lineSequence().firstOrNull().orEmpty()
                        val label = Regex("""Starting export for:\s*(.*?)(?:,\s*URL:|$)""")
                            .find(firstLine)?.groupValues?.getOrNull(1)?.trim()
                            .orEmpty().ifBlank { "Export " + (entries.size - reverseIndex) }
                        val card = android.widget.LinearLayout(this@MainActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.argb(20, 128, 128, 128))
                                cornerRadius = dp(12).toFloat()
                                setStroke(dp(1), android.graphics.Color.argb(64, 128, 128, 128))
                            }
                        }
                        val header = android.widget.LinearLayout(this@MainActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                        header.addView(android.widget.TextView(this@MainActivity).apply {
                            text = label
                            textSize = 14f
                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                        header.addView(android.widget.Button(this@MainActivity).apply {
                            text = "Copy"
                            isAllCaps = false
                            setOnClickListener {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Export log: " + label, entry))
                                Toast.makeText(this@MainActivity, "Export log copied", Toast.LENGTH_SHORT).show()
                            }
                        }, android.widget.LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
                        card.addView(header)
                        card.addView(android.widget.TextView(this@MainActivity).apply {
                            text = entry
                            setPadding(0, dp(8), 0, dp(2))
                            textSize = 11f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setTextIsSelectable(true)
                            maxLines = 18
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        content.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = dp(10)
                        })
                    }
                }
                scroll.addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
                createThemedDialogBuilder(this@MainActivity)
                    .setTitle("Export Logs (" + entries.size + ")")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Clear Logs") { _, _ ->
                        if (logFile.exists()) logFile.delete()
                        Toast.makeText(this@MainActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }

    private fun showPopupBlockerSettingsDialog() {
        val settingsPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = settingsPrefs.getBoolean("block_popups_redirects", true)

        val switchView = com.google.android.material.switchmaterial.SwitchMaterial(this)
        switchView.text = "Block pop-ups & forced redirects"
        switchView.isChecked = isEnabled

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (20 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        switchView.layoutParams = params
        container.addView(switchView)

        createThemedDialogBuilder(this)
            .setTitle("Popup & Redirect Blocker")
            .setMessage("Turn this off to allow login popups (like Google Sign-In) to work properly on some sites.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                settingsPrefs.edit().putBoolean("block_popups_redirects", switchView.isChecked).apply()
                Toast.makeText(this, "Settings saved. Reload page to apply fully.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeminiSettingsDialog() {
        val sharedPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentKey = sharedPrefs.getString("gemini_api_key", "") ?: ""

        val input = EditText(this)
        input.hint = "Enter Gemini API Key"
        input.setText(currentKey)

        val savedModel = sharedPrefs.getString("gemini_model", null)
        val currentModel = when (savedModel) {
            null, "", "gemini-1.5-flash", "gemini-flash-latest", "gemini-1.5-flash-latest" -> "gemini-2.5-flash"
            else -> savedModel
        }

        val modelSpinner = android.widget.Spinner(this)
        val models = arrayOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-1.5-pro",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-3.8-flash"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        modelSpinner.adapter = adapter
        modelSpinner.setSelection(models.indexOf(currentModel).takeIf { it >= 0 } ?: 2)

        val modelLabel = TextView(this)
        modelLabel.text = "Select Model:"
        modelLabel.setPadding(0, 30, 0, 10)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)

        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        input.layoutParams = layoutParams
        modelLabel.layoutParams = layoutParams
        modelSpinner.layoutParams = layoutParams

        layout.addView(input)
        layout.addView(modelLabel)
        layout.addView(modelSpinner)

        createThemedDialogBuilder(this)
            .setTitle("Gemini AI Settings")
            .setMessage("Please enter your Google Gemini API Key and select your preferred model.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newKey = input.text.toString().trim()
                val selectedModel = modelSpinner.selectedItem.toString()
                sharedPrefs.edit()
                    .putString("gemini_api_key", newKey)
                    .putString("gemini_model", selectedModel)
                    .apply()
                Toast.makeText(this, "Gemini Settings Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackgroundLoadingDialog() {
        val settingsPrefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = settingsPrefs.getBoolean("background_loading_enabled", false)

        val switchView = com.google.android.material.switchmaterial.SwitchMaterial(this)
        switchView.text = "Enable Background Loading"
        switchView.isChecked = isEnabled

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (20 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        switchView.layoutParams = params
        container.addView(switchView)

        createThemedDialogBuilder(this)
            .setTitle("Background Loading")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val editor = settingsPrefs.edit()
                editor.putBoolean("background_loading_enabled", switchView.isChecked)
                editor.apply()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackupRestoreDialog() {
        val items = arrayOf("Backup Data", "Restore Data")
        createThemedDialogBuilder(this)
            .setTitle("Backup and Restore")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> checkStoragePermissionAndBackup()
                    1 -> checkStoragePermissionAndRestore()
                }
            }
            .show()
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted. Please try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission is required for backup and restore.", Toast.LENGTH_LONG).show()
        }
    }

    private val manageExternalStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkManageExternalStoragePermission()
    }

    private fun checkManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Manage storage permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Manage storage permission denied. Backup/restore may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkStoragePermissionAndBackup() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    backupData()
                } else {
                    showManageStoragePermissionDialog()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    backupData()
                } else {
                    requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            else -> {
                backupData()
            }
        }
    }

    private fun checkStoragePermissionAndRestore() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    restoreData()
                } else {
                    showManageStoragePermissionDialog()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    restoreData()
                } else {
                    requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            else -> {
                restoreData()
            }
        }
    }

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val backupManager = BackupRestoreManager(this@MainActivity)
                    val backupJson = backupManager.createBackup()
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(backupJson.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Backup successful!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun backupData() {
        backupLauncher.launch("Mydownloader_backup.json")
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val backupJson = contentResolver.openInputStream(it)?.bufferedReader().use { it?.readText() }
                    if (backupJson != null) {
                        val backupManager = BackupRestoreManager(this@MainActivity)
                        backupManager.restoreBackup(backupJson)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Restore successful! Please restart the app.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun restoreData() {
        restoreLauncher.launch("application/json")
    }
    private fun showContentBlockingDialog() {
        val settingsPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val items = arrayOf("Enable Ad Blocker", "Block Suspicious Redirects", "Block All Popups", "Show pop-up blocked notice")
        val checkedItems = booleanArrayOf(
            settingsPrefs.getBoolean("AD_BLOCKER_ENABLED", true),
            settingsPrefs.getBoolean("BLOCK_REDIRECTS", true),
            settingsPrefs.getBoolean("BLOCK_ALL_POPUPS", true),
            settingsPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
        )
        createThemedDialogBuilder(this)
            .setTitle("Content Blocking")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val editor = settingsPrefs.edit()
                editor.putBoolean("AD_BLOCKER_ENABLED", checkedItems[0])
                editor.putBoolean("BLOCK_REDIRECTS", checkedItems[1])
                editor.putBoolean("BLOCK_ALL_POPUPS", checkedItems[2])
                editor.putBoolean("SHOW_POPUP_BLOCKED_NOTICE", checkedItems[3])
                editor.apply()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                if (webView.visibility == View.VISIBLE) {
                    webView.reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun showBlockedSitesDialog() {
        val blockedSites = getBlockedSites()
        if (blockedSites.isEmpty()) {
            Toast.makeText(this, "No sites have been blocked yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, blockedSites)
        createThemedDialogBuilder(this)
            .setTitle("Blocked Sites")
            .setAdapter(adapter) { dialog, which ->
                val siteToUnblock = blockedSites[which]
                showUnblockConfirmationDialog(siteToUnblock)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }
    private fun showUnblockConfirmationDialog(hostname: String) {
        createThemedDialogBuilder(this)
            .setTitle("Unblock Site?")
            .setMessage("Are you sure you want to unblock '$hostname'?")
            .setPositiveButton("Unblock") { _, _ ->
                unblockSite(hostname)
                showBlockedSitesDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun getBlockedSites(): List<String> {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        return (sharedPrefs.getStringSet("BLOCKED_URLS", setOf()) ?: setOf()).sorted()
    }
    private fun unblockSite(hostname: String) {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val blockedUrls = sharedPrefs.getStringSet("BLOCKED_URLS", setOf()) ?: setOf()
        val newBlockedUrls = blockedUrls.toMutableSet()
        newBlockedUrls.remove(hostname)
        sharedPrefs.edit().putStringSet("BLOCKED_URLS", newBlockedUrls).apply()
        Toast.makeText(this, "'$hostname' has been unblocked.", Toast.LENGTH_SHORT).show()
    }
    private fun showWhitelistManagementDialog() {
        val whitelistedSites = getWhitelist()
        if (whitelistedSites.isEmpty()){
            Toast.makeText(this, "Whitelist is empty. Add a site to get started.", Toast.LENGTH_SHORT).show()
            showWhitelistDialog()
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelistedSites)
        createThemedDialogBuilder(this)
            .setTitle("Whitelisted Sites")
            .setAdapter(adapter) { _, which ->
                val siteToRemove = whitelistedSites[which]
                createThemedDialogBuilder(this)
                    .setTitle("Remove from Whitelist?")
                    .setMessage("Remove '$siteToRemove'?")
                    .setPositiveButton("Remove") { _, _ ->
                        removeFromWhitelist(siteToRemove)
                        showWhitelistManagementDialog()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Add New") {_, _ ->
                showWhitelistDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }
    private fun getWhitelist(): List<String> {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        return (sharedPrefs.getStringSet("WHITELIST_URLS", setOf()) ?: setOf()).sorted()
    }
    private fun addToWhitelist(domain: String) {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val whitelist = sharedPrefs.getStringSet("WHITELIST_URLS", setOf())?.toMutableSet() ?: mutableSetOf()
        whitelist.add(domain.lowercase())
        sharedPrefs.edit().putStringSet("WHITELIST_URLS", whitelist).apply()
        Toast.makeText(this, "'$domain' added to whitelist.", Toast.LENGTH_SHORT).show()
    }
    private fun removeFromWhitelist(domain: String) {
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val whitelist = sharedPrefs.getStringSet("WHITELIST_URLS", setOf()) ?: setOf()
        val newWhitelist = whitelist.toMutableSet()
        newWhitelist.remove(domain)
        sharedPrefs.edit().putStringSet("WHITELIST_URLS", newWhitelist).apply()
        Toast.makeText(this, "'$domain' removed from whitelist.", Toast.LENGTH_SHORT).show()
    }
    private fun showWhitelistDialog() {
        val input = EditText(this).apply {
            hint = "e.g., example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val margin = (20 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        input.layoutParams = params
        container.addView(input)
        createThemedDialogBuilder(this)
            .setTitle("Add Site to Whitelist")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val domain = input.text.toString().trim()
                if (domain.isNotEmpty()) {
                    addToWhitelist(domain)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun openCurrentPageInExternalBrowser() {
        val currentUrl = webView.url
        if (!currentUrl.isNullOrEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun showThemeColorPickerDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentHex = prefs.getString("glossy_theme_color", "#A0000000")

        val input = EditText(this).apply {
            hint = "e.g., #AAFF0000 for semi-transparent red"
            setText(currentHex)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(input)
        }

        createThemedDialogBuilder(this)
            .setTitle("Set Theme Color (HEX)")
            .setMessage("Enter an 8-character HEX code (AARRGGBB) for a glossy effect.")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val newColor = input.text.toString().trim()
                if (newColor.matches(Regex("^#[0-9a-fA-F]{8}$")) || newColor.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                    prefs.edit().putString("glossy_theme_color", newColor).apply()
                    applyGlossyTheme()
                    Toast.makeText(this, "Theme applied!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid HEX code. Use #AARRGGBB format.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Presets") { _, _ ->
                val presetColors = arrayOf("Dark Glass (#A0000000)", "Light Glass (#90FFFFFF)", "Red Glass (#80FF0000)", "Blue Glass (#800000FF)", "Green Glass (#8000FF00)")
                val presetHexCodes = arrayOf("#A0000000", "#90FFFFFF", "#80FF0000", "#800000FF", "#8000FF00")

                createThemedDialogBuilder(this)
                    .setTitle("Choose Preset")
                    .setItems(presetColors) { _, which ->
                        prefs.edit().putString("glossy_theme_color", presetHexCodes[which]).apply()
                        applyGlossyTheme()
                        Toast.makeText(this, "Theme applied!", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSiteDebuggingOptions() {
        val options = arrayOf("Change User Agent", "Add to Whitelist", "Enable Remote Debugging", "Clear Cookies")
        createThemedDialogBuilder(this)
            .setTitle("Site Debugging")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUserAgentDialog()
                    1 -> {
                        webView.url?.let { url ->
                            Uri.parse(url).host?.let { host ->
                                addToWhitelist(host)
                                webView.reload()
                            }
                        }
                    }
                    2 -> {
                        WebView.setWebContentsDebuggingEnabled(true)
                        Toast.makeText(this, "Remote debugging enabled for this session.", Toast.LENGTH_LONG).show()
                    }
                    3 -> clearCookies()
                }
            }
            .show()
    }
private fun showUserAgentDialog() {
    val userAgents = arrayOf("Default Mobile", "Desktop Chrome", "iPad Safari")
    val settings = webView.settings

    createThemedDialogBuilder(this)
        .setTitle("Change Browser Identity")
        .setItems(userAgents) { _, which ->
            val newUserAgent: String
            when (which) {
               1, 2 -> { 
    isDesktopMode = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.textZoom = 100

                    newUserAgent = if (which == 1) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    }
                }
                else -> { 
                    isDesktopMode = false
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.textZoom = 100
                    newUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }
            }

            settings.userAgentString = newUserAgent
if (isDesktopMode) {
    webView.postDelayed({
        webView.evaluateJavascript(
            "document.body.style.zoom = '0.5';", null
        )
    }, 100)
}
            webView.reload()
            webView.requestLayout()

            Toast.makeText(this, "Switched to ${userAgents[which]}", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
    private fun isUrlWhitelisted(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        val sharedPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        val whitelist = sharedPrefs.getStringSet("WHITELIST_URLS", setOf())?.toMutableSet() ?: mutableSetOf()

        // System-level whitelist for common authentication providers (e.g. Google Sign-In)
        whitelist.add("google.com")
        whitelist.add("accounts.google.com")
        whitelist.add("accounts.youtube.com")

        return whitelist.any { whitelistedDomain -> host == whitelistedDomain || host.endsWith(".$whitelistedDomain") }
    }
    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            Toast.makeText(this, "All cookies cleared.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun loadEnabledUserScripts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val scripts = db.userScriptDao().getEnabledScripts()
            withContext(Dispatchers.Main) {
                enabledUserScripts = scripts
            }
        }
    }

    private fun injectUserscript(script: UserScript, expectedPageUrl: String = webView.url.orEmpty()) {
        val nameJson = org.json.JSONObject.quote(script.name)
        val polyfillBuilder = StringBuilder()
        polyfillBuilder.append("""
            window.GM_info = window.GM_info || { script: { name: $nameJson } };
            window.GM_addStyle = window.GM_addStyle || function(css) {
                if (window.GMApi && GMApi.addStyle) GMApi.addStyle(String(css));
                else {
                    var style = document.createElement('style');
                    style.textContent = String(css);
                    (document.head || document.documentElement).appendChild(style);
                }
            };
        """.trimIndent())
        if (script.grants.contains("GM_getValue")) {
            polyfillBuilder.append("""
                window.GM_getValue = function(key, defaultValue) {
                    try {
                        var value = AndroidUserscriptAPI.getValue($nameJson, String(key), defaultValue == null ? null : JSON.stringify(defaultValue));
                        if (value == null) return defaultValue;
                        try { return JSON.parse(value); } catch (e) { return value; }
                    } catch (e) { return defaultValue; }
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_setValue")) {
            polyfillBuilder.append("""
                window.GM_setValue = function(key, value) {
                    var serialized;
                    try { serialized = JSON.stringify(value); } catch (e) { serialized = String(value); }
                    AndroidUserscriptAPI.setValue($nameJson, String(key), serialized);
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_deleteValue")) {
            polyfillBuilder.append("""
                window.GM_deleteValue = function(key) {
                    AndroidUserscriptAPI.deleteValue($nameJson, String(key));
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_listValues")) {
            polyfillBuilder.append("""
                window.GM_listValues = function() {
                    try { return JSON.parse(AndroidUserscriptAPI.listValues($nameJson)); } catch (e) { return []; }
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_xmlhttpRequest")) {
            polyfillBuilder.append("""
                window.userscript_callbacks = window.userscript_callbacks || Object.create(null);
                window.GM_xmlhttpRequest = function(details) {
                    details = details || {};
                    var id = "gm_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2);
                    var active = true;
                    var finish = function(callbackName, payload) {
                        var callbacks = window.userscript_callbacks;
                        var callback = callbacks && callbacks[id];
                        if (!active || !callback) return;
                        active = false;
                        delete callbacks[id];
                        if (typeof details[callbackName] === "function") {
                            try { details[callbackName](payload); } catch (e) { console.error("GM_xmlhttpRequest callback failed", e); }
                        }
                    };
                    window.userscript_callbacks[id] = {
                        onload: function(response) {
                            response.response = response.responseText;
                            if (details.responseType === "json") {
                                try { response.response = JSON.parse(response.responseText); } catch (e) {}
                            }
                            finish("onload", response);
                        },
                        onerror: function(message) { finish("onerror", { error: String(message || "Request failed") }); },
                        ontimeout: function() { finish("ontimeout", { error: "Request timed out" }); }
                    };
                    try {
                        AndroidUserscriptAPI.xmlHttpRequest(JSON.stringify({
                            method: details.method || "GET",
                            url: details.url,
                            headers: details.headers || {},
                            data: details.data == null ? null : String(details.data),
                            timeout: Number(details.timeout || 15000),
                            responseType: details.responseType || "text"
                        }), id);
                    } catch (e) {
                        finish("onerror", { error: String(e && e.message || e) });
                    }
                    return { abort: function() {
                        active = false;
                        delete window.userscript_callbacks[id];
                    } };
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_log")) {
            polyfillBuilder.append("""
                window.GM_log = function() {
                    AndroidUserscriptAPI.log(Array.prototype.slice.call(arguments).join(' '));
                };
            """.trimIndent())
        }
        if (script.grants.contains("GM_notification")) {
            polyfillBuilder.append("""
                window.GM_notification = function(details) {
                    var message = typeof details === 'string' ? details : ((details && details.text) || (details && details.title) || '');
                    AndroidUserscriptAPI.showToast(String(message));
                };
            """.trimIndent())
        }

        val polyfills = polyfillBuilder.toString()
        webView.evaluateJavascript("(function(){ $polyfills })();", null)

        // @require resources are fetched off-thread, then injected in declared order.
        // Restrict schemes and bound both response size and total wait; no auth headers are forwarded.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val requiredScripts = script.requires.map { requireUrl ->
                    requireUrl.trim().also {
                        require(it.startsWith("https://", true) || it.startsWith("http://", true)) {
                            "Only HTTP(S) @require URLs are supported"
                        }
                    }.let { requireUrl ->
                        val connection = java.net.URL(requireUrl).openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 15000
                        connection.instanceFollowRedirects = true
                        try {
                            val status = connection.responseCode
                            require(status in 200..299) { "@require returned HTTP $status" }
                            val limit = 2 * 1024 * 1024
                            connection.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= limit) { "@require resource exceeds 2 MiB" }
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    // Navigation may have changed while a required library was downloading.
                    if (webView.url != expectedPageUrl) return@withContext
                    // Build one ordered script so libraries are installed before the userscript executes.
                    val builder = StringBuilder("(function(){ try {")
                    requiredScripts.forEach { builder.append("\n").append(it).append("\n;") }
                    val encodedName = org.json.JSONObject.quote(script.name)
                    builder.append("\n").append(script.script)
                    builder.append("\n} catch (e) { console.error('Userscript error in ', ")
                        .append(encodedName).append(", e); } })();")
                    webView.evaluateJavascript(builder.toString(), null)
                }
            } catch (e: Exception) {
                android.util.Log.w("Userscript", "Failed to load @require for script $nameJson (${e.javaClass.simpleName}); resource details omitted")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "Could not load a required userscript library.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun injectUserscriptOncePerPage(script: UserScript, url: String) {
        val accepted = synchronized(injectedUserscriptIdsForPage) {
            if (userscriptPageUrl != url || !injectedUserscriptIdsForPage.add(script.id)) {
                false
            } else {
                true
            }
        }
        if (accepted) injectUserscript(script, expectedPageUrl = url)
    }

    private fun injectEarlyUserscripts(url: String?) {
        if (url == null) return
        val matchingScripts = enabledUserScripts.filter {
            it.shouldRunOnUrl(url) && it.runAt == UserScript.RunAt.DOCUMENT_START
        }
        for (script in matchingScripts) injectUserscriptOncePerPage(script, url)
    }

    private fun injectPendingUserscripts() {
        val url = webView.url ?: return
        val matchingScripts = enabledUserScripts.filter {
            it.shouldRunOnUrl(url) &&
                (it.runAt == UserScript.RunAt.DOCUMENT_END || it.runAt == UserScript.RunAt.DOCUMENT_IDLE)
        }
        for (script in matchingScripts) injectUserscriptOncePerPage(script, url)
        pendingScriptsToInject.clear()
    }
    private fun showConfirmNavigationDialog(url: String, view: WebView?) {
        runOnUiThread {
            // Mask common auth tokens so they aren't exposed in UI
            var displayUrl = url
            val sensitiveParams = listOf("auth-token", "token", "sig", "signature", "key", "auth")
            try {
                val uri = android.net.Uri.parse(url)
                val queryParams = uri.queryParameterNames
                if (queryParams.isNotEmpty()) {
                    var builder = uri.buildUpon().clearQuery()
                    for (param in queryParams) {
                        if (sensitiveParams.any { param.contains(it, ignoreCase = true) }) {
                            builder = builder.appendQueryParameter(param, "[REDACTED]")
                        } else {
                            builder = builder.appendQueryParameter(param, uri.getQueryParameter(param))
                        }
                    }
                    displayUrl = builder.build().toString()
                }
            } catch (e: Exception) {}

            createThemedDialogBuilder(this@MainActivity)
                .setTitle("Navigation Request")
                .setMessage("This page wants to navigate to:\n\n$displayUrl")
                .setPositiveButton("Allow") { _, _ ->
                    approvedNavigationUrl = url
                    view?.loadUrl(url)
                }
                .setNegativeButton("Block") { _, _ ->
                    android.widget.Toast.makeText(this@MainActivity, "Navigation blocked", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    fun showBlockedNavigationDialog(url: String) {
        createThemedDialogBuilder(this)
            .setTitle("Suspicious Redirect Blocked")
            .setMessage("The site is trying to redirect you to:\n$url\n\nDo you want to allow this?")
            .setPositiveButton("Allow") { _, _ ->
                webView.loadUrl(url)
            }
            .setNeutralButton("Open in Background") { _, _ ->
                openInNewTab(url, inBackground = true)
            }
            .setNegativeButton("Block") { _, _ ->
                Toast.makeText(this, "Redirect blocked", Toast.LENGTH_SHORT).show()
                addToBlockedList(Uri.parse(url).host ?: url)
            }
            .show()
    }

    private fun showTranslateSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("translate_target_lang", "en") ?: "en"
        val isAuto = prefs.getBoolean("auto_translate_enabled", false)

        val languages = arrayOf("English", "Indonesian", "Spanish", "Korean", "Japanese", "Chinese (Simplified)")
        val languageCodes = arrayOf("en", "id", "es", "ko", "ja", "zh-CN")

        var selectedIndex = languageCodes.indexOf(currentLang)
        if (selectedIndex == -1) selectedIndex = 0

        val dialogView = layoutInflater.inflate(R.layout.dialog_translate_settings, null)
        val spinner = dialogView.findViewById<android.widget.Spinner>(R.id.language_spinner)
        val switchAuto = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_auto_translate)

        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex)

        switchAuto.isChecked = isAuto

        createThemedDialogBuilder(this)
            .setTitle("Translation Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newLangIndex = spinner.selectedItemPosition
                val newLangCode = languageCodes[newLangIndex]
                val newAuto = switchAuto.isChecked

                prefs.edit()
                    .putString("translate_target_lang", newLangCode)
                    .putBoolean("auto_translate_enabled", newAuto)
                    .apply()

                if (newAuto != isAuto) {
                    isAutoTranslateEnabled = newAuto
                    if (isAutoTranslateEnabled) {
                        binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                        injectTranslateScript(webView)
                        Toast.makeText(this, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.translateButton.clearColorFilter()
                        Toast.makeText(this, "Auto-Translate OFF (Reloading...)", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    }
                } else if (newAuto && newLangCode != currentLang) {
                    // Language changed, re-inject translation with new language
                    Toast.makeText(this, "Language updated", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

private fun createThemedDialogBuilder(context: Context, isOpaque: Boolean = false): AlertDialog.Builder {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val themeColorHex = prefs.getString("glossy_theme_color", "#A0000000") ?: "#A0000000"
        val baseColor = try { android.graphics.Color.parseColor(themeColorHex) } catch(e: Exception) { android.graphics.Color.parseColor("#A0000000") }
        val parsedColor = if (isOpaque) {
            // Strip alpha to make it 100% opaque
            android.graphics.Color.rgb(android.graphics.Color.red(baseColor), android.graphics.Color.green(baseColor), android.graphics.Color.blue(baseColor))
        } else {
            baseColor
        }

        val luminance = (0.299 * android.graphics.Color.red(parsedColor) + 0.587 * android.graphics.Color.green(parsedColor) + 0.114 * android.graphics.Color.blue(parsedColor)) / 255
        val textColor = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE

        return object : AlertDialog.Builder(context, R.style.CustomAlertDialogTheme) {
            override fun create(): AlertDialog {
                val dialog = super.create()

                dialog.setOnShowListener {
                    val window = dialog.window
                    if (window != null) {
                        val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                            setColor(parsedColor)
                            cornerRadius = 24f * context.resources.displayMetrics.density
                        }
                        window.setBackgroundDrawable(shapeDrawable)

                        window.decorView.post {
                            val titleView = window.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
                            titleView?.setTextColor(textColor)

                            val msgView = window.findViewById<android.widget.TextView>(android.R.id.message)
                            msgView?.setTextColor(textColor)

                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(textColor)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(textColor)
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(textColor)

                            val listView = dialog.listView
                            if (listView != null) {
                                for (i in 0 until listView.childCount) {
                                    val child = listView.getChildAt(i)
                                    if (child is android.widget.TextView) {
                                        child.setTextColor(textColor)
                                    } else if (child is android.view.ViewGroup) {
                                        for (j in 0 until child.childCount) {
                                            val innerChild = child.getChildAt(j)
                                            if (innerChild is android.widget.TextView) innerChild.setTextColor(textColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return dialog
            }

            // Still override show just in case to delegate to create().show()
            override fun show(): AlertDialog {
                val dialog = create()
                dialog.show()
                return dialog
            }
        }
    }

    private fun updateFabVisibility() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { updateFabVisibility() }
            return
        }
        refreshDetectedMediaSheet?.invoke()
        val hasFiles = synchronized(detectedMediaFiles) { detectedMediaFiles.isNotEmpty() }

        if (hasFiles) {
            binding.fabShowMedia.visibility = android.view.View.VISIBLE
            binding.mediaCountBadge.text = if (detectedMediaFiles.size > 99) "99+" else detectedMediaFiles.size.toString()
            binding.mediaCountBadge.visibility = android.view.View.VISIBLE
            binding.fabShowMedia.setImageResource(R.drawable.ic_download)

            binding.fabShowMedia.setOnClickListener {
                showMediaListDialog()
            }

            binding.fabShowMedia.setOnLongClickListener {
                if (detectedMediaFiles.isNotEmpty()) {
                    showMediaListDialog()
                } else {
                    android.widget.Toast.makeText(this, "No media intercepted yet. Play the video first.", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
        } else {
            binding.fabShowMedia.visibility = android.view.View.GONE
            binding.mediaCountBadge.visibility = android.view.View.GONE
        }

        runOnUiThread {
            val bestPlayable = mediaEngine.getBestCandidate()
            val floatingDetector = findViewById<android.widget.LinearLayout>(R.id.floatingDetectorUI)
            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            val isFloatingEnabled = prefs.getBoolean("FLOATING_DETECTOR_ENABLED", true)
            val minDuration = prefs.getInt("FLOATING_DETECTOR_MIN_DURATION", 0)

            if (!isFloatingEnabled || (bestPlayable != null && bestPlayable.durationSec > 0 && bestPlayable.durationSec < minDuration)) {
                detectorHideHandler.removeCallbacks(detectorHideRunnable)
                floatingDetector?.visibility = android.view.View.GONE
                return@runOnUiThread
            }

            // Apply saved translation
            if (savedDetectorX != null && savedDetectorY != null) {
                floatingDetector?.translationX = savedDetectorX!!
                floatingDetector?.translationY = savedDetectorY!!
            }

            floatingDetector?.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        floatingDetectorStartX = view.translationX
                        floatingDetectorStartY = view.translationY
                        floatingDetectorInitialTouchX = event.rawX
                        floatingDetectorInitialTouchY = event.rawY
                        isFloatingDetectorMoved = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - floatingDetectorInitialTouchX
                        val dy = event.rawY - floatingDetectorInitialTouchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            isFloatingDetectorMoved = true
                            view.translationX = floatingDetectorStartX + dx
                            view.translationY = floatingDetectorStartY + dy
                            savedDetectorX = view.translationX
                            savedDetectorY = view.translationY
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!isFloatingDetectorMoved) {
                            view.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            floatingDetector?.setOnClickListener {
                showMediaListDialog()
            }

            val txtState = findViewById<android.widget.TextView>(R.id.txtDetectorState)
            val txtTitle = findViewById<android.widget.TextView>(R.id.txtDetectorTitle)

            // Show overlay whenever mediaEngine has a non-null best playable candidate.
            if (bestPlayable != null) {
                floatingDetector?.visibility = android.view.View.VISIBLE

                txtTitle?.text = "Media Detected"

                val typeStr = if (bestPlayable.isManifest) "HLS" else "MP4"
                val qualityStr = "Auto"
                txtState?.text = "$typeStr • $qualityStr"

                val btnDownload = findViewById<android.widget.ImageView>(R.id.btnDetectorDownload)
                // Removed btnDownload click listener to prevent stealing touch from floatingDetector

                val btnClose = findViewById<android.widget.ImageButton>(R.id.btnDetectorClose)
                btnClose?.setOnClickListener {
                    floatingDetectorHiddenMediaKey = floatingDetectorMediaKey
                    floatingDetector?.visibility = android.view.View.GONE
                    detectorHideHandler.removeCallbacks(detectorHideRunnable)
                }

                // Show for up to 10 seconds per media candidate. Repeated detector updates
                // must not keep resetting the timer or resurrect a dismissed overlay.
                val mediaKey = bestPlayable.url
                val isNewMedia = mediaKey != floatingDetectorMediaKey
                if (isNewMedia) {
                    floatingDetectorMediaKey = mediaKey
                    floatingDetectorHiddenMediaKey = null
                    detectorHideHandler.removeCallbacks(detectorHideRunnable)
                }

                if (floatingDetectorHiddenMediaKey == mediaKey) {
                    floatingDetector?.visibility = android.view.View.GONE
                    detectorHideHandler.removeCallbacks(detectorHideRunnable)
                } else {
                    val wasHidden = floatingDetector?.visibility != android.view.View.VISIBLE
                    floatingDetector?.visibility = android.view.View.VISIBLE
                    if (isNewMedia || wasHidden) {
                        detectorHideHandler.removeCallbacks(detectorHideRunnable)
                        detectorHideHandler.postDelayed(detectorHideRunnable, 10_000L)
                    }
                }

            } else {
                detectorHideHandler.removeCallbacks(detectorHideRunnable)
                floatingDetectorMediaKey = null
                floatingDetectorHiddenMediaKey = null
                floatingDetector?.visibility = android.view.View.GONE
            }
        }
    }

    fun handleExtractedSubtitleBlob(content: String, originalUrl: String, language: String, label: String) {
        // Validate subtitle content format (VTT or SRT)
        val isVtt = content.startsWith("WEBVTT", ignoreCase = true) || content.contains("WEBVTT\n", ignoreCase = true)
        val isSrt = content.contains(Regex("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}"))

        if (!isVtt && !isSrt) {
            android.util.Log.e("SUBTITLE_BLOB", "Validation failed: Content is not valid WebVTT or SRT.\nURL: $originalUrl")
            return
        }

        val extension = if (isVtt) ".vtt" else ".srt"
        val mimeType = if (isVtt) "text/vtt" else "application/x-subrip"

        // Create a unique hash for the filename based on URL and language to avoid duplicates
        val hash = java.util.UUID.nameUUIDFromBytes(originalUrl.toByteArray()).toString()
        val safeTitle = "$label [$language]".replace(Regex("[^a-zA-Z0-9.\\[\\] -]"), "_").take(100)
        // Preserve the webpage-provided track label in the URI passed to CustomPlayerActivity.
        val fileName = "blob_sub_${safeTitle}_${hash}${extension}"
        val subtitleFile = java.io.File(filesDir, fileName)

        // Prevent duplicate writing if we already processed this exact Blob URL
        if (!subtitleFile.exists()) {
            try {
                subtitleFile.writeText(content)
                android.util.Log.d("SUBTITLE_BLOB", "Saved blob subtitle to: ${subtitleFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("SUBTITLE_BLOB", "Failed to write subtitle file: ${e.message}")
                return
            }
        }

        val localUriString = android.net.Uri.fromFile(subtitleFile).toString()

        val existsAlready = synchronized(detectedMediaFiles) {
            detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(localUriString) }
        }

        if (!existsAlready) {
            val mediaFile = MediaFile(
                url = localUriString,
                title = safeTitle,
                mimeType = mimeType,
                quality = "Auto",
                category = MediaCategory.SUBTITLE,
                fileSize = "${subtitleFile.length() / 1024} KB",
                language = language,
                isMainContent = false
            )

            synchronized(detectedMediaFiles) {
                detectedMediaFiles.add(mediaFile)
            }
            updateFabVisibility()
            currentMediaListAdapter?.notifyDataSetChanged()
        }
    }

    private val youTubeDetectionLock = Any()
    private var youtubeDetectionGeneration = 0
    private var activeYouTubeVideoKey: String? = null
    private var lastYoutubeUrl: String? = null
    private var pendingYouTubeAutoPlay = false

    private fun isYouTubeVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
        if (!isYouTubeHost) return false
        val firstSegment = uri.pathSegments.firstOrNull()?.lowercase()
        return host == "youtu.be" || host.endsWith(".youtu.be") ||
            firstSegment == "watch" ||
            (firstSegment != null && firstSegment in setOf("shorts", "embed", "live", "v"))
    }

    /**
     * YouTube is an SPA, so multiple callbacks can report the same video with different
     * query parameters. Key by video ID so its detection results are replaced only when
     * the actual video changes.
     */
    private fun youtubeVideoKey(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val segments = uri.pathSegments
        val firstSegment = segments.firstOrNull()?.lowercase()
        val id = when {
            host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
            firstSegment == "watch" -> uri.getQueryParameter("v")
            firstSegment != null && firstSegment in setOf("shorts", "embed", "live", "v") -> segments.getOrNull(1)
            else -> null
        }?.trim()

        return id?.takeIf { it.isNotEmpty() }?.let { "video:$it" }
            ?: "page:$host${uri.path.orEmpty()}"
    }

    /** Invalidate any pending YouTube extraction after navigating to another site. */
    private fun resetYouTubeDetectionContext() {
        synchronized(youTubeDetectionLock) {
            if (activeYouTubeVideoKey != null || lastYoutubeUrl != null || pendingYouTubeAutoPlay) {
                youtubeDetectionGeneration++
                activeYouTubeVideoKey = null
                lastYoutubeUrl = null
                pendingYouTubeAutoPlay = false
            }
        }
    }

    private fun checkForYouTube(url: String?, force: Boolean = false, autoPlay: Boolean = false) {
        if (url == null) return

        // Only extract page URLs that identify a YouTube video, not arbitrary YouTube pages.
        if (!isYouTubeVideoUrl(url)) return
        if (url.contains("googlevideo.com/videoplayback")) return

        val videoKey = youtubeVideoKey(url)
        var clearListForNewVideo = false
        var requestGeneration = 0
        synchronized(youTubeDetectionLock) {
            if (videoKey != activeYouTubeVideoKey) {
                activeYouTubeVideoKey = videoKey
                youtubeDetectionGeneration++
                lastYoutubeUrl = null
                // Do not carry an autoplay request from a previous video into this one.
                pendingYouTubeAutoPlay = false
                clearListForNewVideo = true
            }
            if (autoPlay) pendingYouTubeAutoPlay = true
            if (!force && url == lastYoutubeUrl) return
            lastYoutubeUrl = url
            requestGeneration = youtubeDetectionGeneration
        }

        if (clearListForNewVideo) {
            // Clear once per actual video ID. Run the UI/list reset before extraction so
            // the new video cannot be appended to the previous video's video/audio/subtitle rows.
            runOnUiThread {
                val isCurrentRequest = synchronized(youTubeDetectionLock) {
                    requestGeneration == youtubeDetectionGeneration &&
                        videoKey == activeYouTubeVideoKey
                }
                if (!isCurrentRequest) return@runOnUiThread

                synchronized(detectedMediaFiles) {
                    detectedMediaFiles.clear()
                }
                (webView.tag as? MediaStateInterface)?.clearState()
                mediaEngine.clear()
                currentMediaListAdapter?.notifyDataSetChanged()
                updateFabVisibility()
                launchYouTubeExtraction(url, videoKey, requestGeneration)
            }
        } else {
            runOnUiThread {
                val isCurrentRequest = synchronized(youTubeDetectionLock) {
                    requestGeneration == youtubeDetectionGeneration &&
                        videoKey == activeYouTubeVideoKey
                }
                if (isCurrentRequest) updateFabVisibility()
            }
            launchYouTubeExtraction(url, videoKey, requestGeneration)
        }
    }

    private fun launchYouTubeExtraction(url: String, videoKey: String, requestGeneration: Int) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mediaFile = YoutubeExtractorHelper.extractMedia(applicationContext, url)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val isCurrentRequest = synchronized(youTubeDetectionLock) {
                    requestGeneration == youtubeDetectionGeneration &&
                        videoKey == activeYouTubeVideoKey
                }
                if (!isCurrentRequest) {
                    android.util.Log.d("YouTube_Regression", "Ignoring stale extraction result for a previous video")
                    return@withContext
                }

                if (mediaFile != null) {
                    android.util.Log.d("YouTube_Regression", "Extracted media: ${mediaFile.title}, URL: ${mediaFile.url}")
                    android.util.Log.d("YouTube_Regression", "Target Audio URL: ${mediaFile.audioUrl}, Target Subtitles: ${mediaFile.subtitleUrls?.size}")
                    var wasAdded = false
                    synchronized(detectedMediaFiles) {
                        // 1. Add main video
                        if (!detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(mediaFile.url) }) {
                            detectedMediaFiles.add(0, mediaFile)
                            wasAdded = true
                        }
                        android.util.Log.d("YouTube_Regression", "Main video was added to list: $wasAdded. Current list size: ${detectedMediaFiles.size}")

                        // 2. Add audio
                        if (!mediaFile.audioUrl.isNullOrEmpty()) {
                            val audioFile = mediaFile.copy(
                                url = mediaFile.audioUrl!!,
                                title = "${mediaFile.title}_audio",
                                mimeType = "audio/mp4",
                                category = MediaCategory.AUDIO,
                                audioUrl = null,
                                subtitleUrls = null,
                                requiresSeparateAudio = false
                            )
                            if (!detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(audioFile.url) }) {
                                detectedMediaFiles.add(audioFile)
                                wasAdded = true
                            }
                        }

                        // 3. Add subtitles
                        if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                            mediaFile.subtitleUrls!!.forEach { subUrl ->
                                val subLang = SubtitleUtils.extractTrustedLanguageFromUrl(subUrl)
                                val subFile = mediaFile.copy(
                                    url = subUrl,
                                    title = "${mediaFile.title.substringBeforeLast('.', mediaFile.title)}_${SubtitleUtils.languageDisplayName(subLang) ?: "Subtitle"}.vtt",
                                    mimeType = "text/vtt",
                                    category = MediaCategory.SUBTITLE,
                                    audioUrl = null,
                                    subtitleUrls = null,
                                    language = subLang?.let { SubtitleUtils.languageDisplayName(it) },
                                    requiresSeparateAudio = false
                                )
                                if (!detectedMediaFiles.any { SubtitleUtils.canonicalMediaUrl(it.url) == SubtitleUtils.canonicalMediaUrl(subFile.url) }) {
                                    detectedMediaFiles.add(subFile)
                                    wasAdded = true
                                }
                            }
                        }
                    }

                    if (wasAdded) {
                        updateFabVisibility()
                        currentMediaListAdapter?.notifyDataSetChanged()
                    }
                    android.util.Log.d("YouTube_Regression", "UI Updated. Final list count: ${detectedMediaFiles.size}")

                    val shouldAutoPlay = synchronized(youTubeDetectionLock) {
                        if (requestGeneration == youtubeDetectionGeneration &&
                            videoKey == activeYouTubeVideoKey &&
                            pendingYouTubeAutoPlay
                        ) {
                            pendingYouTubeAutoPlay = false
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldAutoPlay) {
                        launchYouTubeInCustomPlayer(mediaFile)
                    }
                } else {
                    val shouldAutoPlay = synchronized(youTubeDetectionLock) {
                        if (requestGeneration == youtubeDetectionGeneration &&
                            videoKey == activeYouTubeVideoKey &&
                            pendingYouTubeAutoPlay
                        ) {
                            pendingYouTubeAutoPlay = false
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldAutoPlay) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "YouTube extraction failed — see logcat tag YoutubeExtractorHelper",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun configureYouTubeAudioSource(intent: android.content.Intent, mediaFile: MediaFile) {
        if (!mediaFile.requiresSeparateAudio) return
        val audioUrl = mediaFile.audioUrl?.takeIf { it.isNotBlank() } ?: return
        intent.putExtra(YouTubeDownloadService.EXTRA_AUDIO_URL, audioUrl)
        intent.putExtra("EXTRA_AUDIO_MIME_TYPE", "audio/mp4")
    }

    private fun launchYouTubeInCustomPlayer(mediaFile: MediaFile) {
        val intent = android.content.Intent(this, CustomPlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK);
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mediaFile.url)
            putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, mediaFile.title)
            putExtra(CustomPlayerActivity.EXTRA_MIME_TYPE, mediaFile.mimeType)
            putExtra(CustomPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
            val referer = mediaFile.referer ?: "https://www.youtube.com"
            putExtra(CustomPlayerActivity.EXTRA_REFERER, referer)
            android.webkit.CookieManager.getInstance().getCookie(referer)?.let {
                putExtra(CustomPlayerActivity.EXTRA_COOKIE, it)
            }
            this@MainActivity.configureYouTubeAudioSource(this, mediaFile)
            if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                val taggedUrls = mediaFile.subtitleUrls!!.map { url ->
                    if (mediaFile.language != null && !url.contains("lang=")) {
                        val joiner = if (url.contains("?")) "&lang=${mediaFile.language}" else "?lang=${mediaFile.language}"
                        url + joiner
                    } else url
                }
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, java.util.ArrayList(taggedUrls))
            }
        }
        startActivity(intent)
    }

}
