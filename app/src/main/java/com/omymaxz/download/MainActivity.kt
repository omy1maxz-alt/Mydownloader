package com.omymaxz.download

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var userscriptInterface: UserscriptInterface
    private lateinit var gmApi: GMApi

    private val detectedMediaFiles = Collections.synchronizedList(mutableListOf<MediaFile>())
    private var currentMediaListAdapter: MediaListAdapter? = null
    private var lastUsedName: String = "Video"
    var currentVideoUrl: String? = null
    private var fullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    var isServiceRunning = false
    private val handler = Handler(Looper.getMainLooper())
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
        "propellerads.com", "revcontent.com", "mgid.com"
    )
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.dataString?.let {
                results = arrayOf(Uri.parse(it))
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

    companion object {
        const val ACTION_MEDIA_CONTROL = "com.omymaxz.download.MEDIA_CONTROL"
        const val EXTRA_COMMAND = "command"
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra(EXTRA_COMMAND)
            if (action != null) {
                android.util.Log.d("MainActivity", "Received media control broadcast: $action")
                when (action) {
                    MediaForegroundService.ACTION_PLAY -> {
                        binding.webView.evaluateJavascript("window.AndroidMediaController.play();", null)
                    }
                    MediaForegroundService.ACTION_PAUSE -> {
                        binding.webView.evaluateJavascript("window.AndroidMediaController.pause();", null)
                    }
                    MediaForegroundService.ACTION_NEXT -> {
                        binding.webView.evaluateJavascript("window.AndroidMediaController.next();", null)
                    }
                    MediaForegroundService.ACTION_PREVIOUS -> {
                        binding.webView.evaluateJavascript("window.AndroidMediaController.previous();", null)
                    }
                    MediaForegroundService.ACTION_STOP_SERVICE -> {
                        binding.webView.evaluateJavascript("window.AndroidMediaController.pause();", null)
                        stopPlaybackService()
                        isMediaPlaying = false
                    }
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
             val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
             val clip = android.content.ClipData.newPlainText("Copied Text", text)
             clipboard.setPrimaryClip(clip)
             runOnUiThread {
                 Toast.makeText(this@MainActivity, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
             }
        }
    }

private fun checkBatteryOptimization() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
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
        initializeTabs()
        setupUrlBarInToolbar()
        loadLastUsedName()
        setupHomeButton()
        setupTabButton()
        setupStartPage()
        setupToolbarNavButtons()
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
        val sharedPrefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        val host = sharedPrefs.getString("proxy_host", null)
        val port = sharedPrefs.getInt("proxy_port", -1)

        val proxyConfig: ProxyConfig
        if (host != null && port != -1) {
            proxyConfig = ProxyConfig.Builder()
                .addProxyRule("$host:$port")
                .build()
            Toast.makeText(this, "Proxy set to $host:$port", Toast.LENGTH_SHORT).show()
        } else {
            proxyConfig = ProxyConfig.Builder().addDirect().build()
        }

        ProxyController.getInstance().setProxyOverride(proxyConfig, ContextCompat.getMainExecutor(this), Runnable {
        })
    }

    private fun checkInitialStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val sharedPrefs = getSharedPreferences("AppData", Context.MODE_PRIVATE)
            val hasShownStorageNotice = sharedPrefs.getBoolean("HAS_SHOWN_STORAGE_NOTICE", false)

            if (!hasShownStorageNotice) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Access Notice")
                    .setMessage("This app may need storage access for backup/restore functionality. You'll be prompted when needed.")
                    .setPositiveButton("OK") { _, _ ->
                        sharedPrefs.edit().putBoolean("HAS_SHOWN_STORAGE_NOTICE", true).apply()
                    }
                    .show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isAppInBackground = true
        if (isMediaPlaying) {
            startOrUpdatePlaybackService()
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInBackground = false
        if (hasStartedForegroundService) {
            stopPlaybackService()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaControlReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_EXPORTED)
        } else {
            registerReceiver(mediaControlReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mediaControlReceiver)
        webView.destroy()
        if (hasStartedForegroundService) {
            stopPlaybackService()
            hasStartedForegroundService = false
        }
    }

    override fun onStop() {
        super.onStop()

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

    private fun setupToolbarNavButtons() {
        binding.backButton.setOnClickListener {
            if (currentTabIndex in tabs.indices) {
                val tab = tabs[currentTabIndex]
                if (tab.historyStack.size > 1) {
                    tab.historyStack.removeAt(tab.historyStack.size - 1)
                    val prevUrl = tab.historyStack.last()
                    webView.loadUrl(prevUrl)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
            updateToolbarNavButtonState()
        }
        binding.forwardButton.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
        binding.refreshButton.setOnClickListener {
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
        val tabsRecyclerView = dialogView.findViewById<RecyclerView>(R.id.tabsRecyclerView)
        val newTabButton = dialogView.findViewById<Button>(R.id.newTabButton)
        tabsRecyclerView.layoutManager = LinearLayoutManager(this)
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
            }
        )
        tabsRecyclerView.adapter = tabAdapter
        newTabButton.setOnClickListener {
            createNewTab()
            tabsDialog?.dismiss()
        }
        tabsDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        tabsDialog?.show()
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
        AlertDialog.Builder(this)
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
            (window.decorView as FrameLayout).removeView(fullscreenView)
            fullscreenView = null
            customViewCallback?.onCustomViewHidden()
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        // --- PERFORMANCE OPTIMIZATIONS ---
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null) // Enable hardware acceleration
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            // --- PERFORMANCE SETTINGS ---
            settings.allowFileAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT // Use cache
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Keep these as they are for proper layout handling
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(true)

            addJavascriptInterface(WebAPIPolyfill(this@MainActivity), "AndroidWebAPI")
            addJavascriptInterface(MediaStateInterface(this@MainActivity), "AndroidMediaState")
            addJavascriptInterface(userscriptInterface, "AndroidUserscriptAPI")
            addJavascriptInterface(gmApi, "GMApi")
            addJavascriptInterface(YouTubeInterface(this@MainActivity), "YouTubeInterface")

            setOnCreateContextMenuListener { _, _, _ ->
                val hitTestResult = this.hitTestResult
                if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    val linkUrl = hitTestResult.extra
                    if (linkUrl != null) {
                        val options = arrayOf("Open in new tab", "Open in background tab", "Open in Custom Tab", "Copy link URL")
                        AlertDialog.Builder(this@MainActivity)
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
                        AlertDialog.Builder(this@MainActivity)
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

                    super.onPageStarted(view, url, favicon)
                    isPageLoading = true
                    binding.progressBar.visibility = View.VISIBLE
                    binding.urlEditTextToolbar.setText(url)
                    synchronized(detectedMediaFiles) {
                        detectedMediaFiles.clear()
                    }
                    runOnUiThread { updateFabVisibility() }
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }
                    if (currentTabIndex in tabs.indices) {
                        val tab = tabs[currentTabIndex]
                        if (tab.historyStack.isEmpty() || tab.historyStack.last() != url) {
                            tab.historyStack.add(url!!)
                        }
                    }
                    updateToolbarNavButtonState()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoading = false
                    binding.progressBar.visibility = View.GONE
                    updateToolbarNavButtonState()
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }
                    injectMediaStateDetector()
                    injectAdvancedMediaDetector()
                    injectPendingUserscripts()
                    url?.let {
                        addToHistory(it)
                        if (currentTabIndex in tabs.indices) {
                            tabs[currentTabIndex].url = it
                            tabs[currentTabIndex].title = view?.title ?: "No Title"
                        }
                    }
                    checkForYouTube(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
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
                    if (isUrlWhitelisted(url)) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (isAdDomain(url)) {
                        return createEmptyResponse()
                    }
                    if (isMediaUrl(url)) {
                        try {
                            val category = MediaCategory.fromUrl(url)
                            val isMainContent = isMainVideoContent(url)
                            if (category == MediaCategory.VIDEO && isMainContent) {
                                currentVideoUrl = url
                            }
                            val detectedFormat = detectVideoFormat(url)
                            val quality = extractQualityFromUrl(url)
                            val enhancedTitle = generateSmartFileName(url, detectedFormat.extension, quality, category)
                            val fileSize = estimateFileSize(url, category)
                            val language = extractLanguageFromUrl(url)
                            val mediaFile = MediaFile(
                                url = url,
                                title = enhancedTitle,
                                mimeType = detectedFormat.mimeType,
                                quality = quality,
                                category = category,
                                fileSize = fileSize,
                                language = language,
                                isMainContent = isMainContent
                            )
                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url }
                            }
                            if (!existsAlready) {
                                synchronized(detectedMediaFiles) {
                                    detectedMediaFiles.add(mediaFile)
                                }
                                runOnUiThread { updateFabVisibility() }
                                if (category == MediaCategory.SUBTITLE) {
                                    fetchSubtitleSnippet(mediaFile)
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
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (currentTabIndex in tabs.indices && !title.isNullOrBlank()) {
                        tabs[currentTabIndex].title = title
                    }
                }
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_LONG).show()
                        return false
                    }
                    return true
                }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    customViewCallback = callback
                    (binding.root as FrameLayout).addView(fullscreenView)
                    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    binding.mainContent.visibility = View.GONE
                }
                override fun onHideCustomView() {
                    if (fullscreenView == null) return
                    (binding.root as FrameLayout).removeView(fullscreenView)
                    fullscreenView = null
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    binding.mainContent.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val currentUrl = view.url
                    if (currentUrl != null && isUrlWhitelisted(currentUrl)) {
                        val newWebView = WebView(this@MainActivity)
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                    val settingsPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
                    val blockPopups = settingsPrefs.getBoolean("BLOCK_ALL_POPUPS", true)
                    if (blockPopups) {
                        val showNotice = settingsPrefs.getBoolean("SHOW_POPUP_BLOCKED_NOTICE", true)
                        if (showNotice) {
                            Toast.makeText(applicationContext, "Pop-up blocked", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    val newWebView = WebView(this@MainActivity)
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = newWebView
                    resultMsg.sendToTarget()
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            openInNewTab(request.url.toString(), false)
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                            return true
                        }
                    }
                    return true
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                        setDescription("Downloading file...")
                        setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                    }
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(applicationContext, "Download Failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        fun showPreview(text: String, filename: String, mimeType: String) {
            runOnUiThread {
                val scrollView = ScrollView(this@MainActivity)
                val textView = TextView(this@MainActivity).apply {
                    this.text = text
                    setPadding(32, 32, 32, 32)
                    setTextIsSelectable(true)
                }
                scrollView.addView(textView)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Content Preview")
                    .setView(scrollView)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Download") { _, _ ->
                        saveToDownloads(text.toByteArray(), filename, mimeType)
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
                        showPreview(text.take(20000), filename, mimeType)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Native Preview failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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

    private fun injectJulesEnhancements(webView: WebView?) {
        val script = """
            javascript:(function() {
                if (document.getElementById('jules-copy-btn')) return;

                var btn = document.createElement('div');
                btn.id = 'jules-copy-btn';
                btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';
                btn.style.position = 'fixed';
                btn.style.bottom = '80px';
                btn.style.right = '20px';
                btn.style.width = '48px';
                btn.style.height = '48px';
                btn.style.backgroundColor = '#6200EE';
                btn.style.color = '#FFFFFF';
                btn.style.borderRadius = '50%';
                btn.style.display = 'flex';
                btn.style.alignItems = 'center';
                btn.style.justifyContent = 'center';
                btn.style.boxShadow = '0 4px 6px rgba(0,0,0,0.3)';
                btn.style.zIndex = '9999';
                btn.style.cursor = 'pointer';

                btn.onclick = function() {
                    var text = "";
                    var selection = window.getSelection().toString();

                    if (selection && selection.length > 0) {
                        text = selection;
                    } else {
                        // Resilient heuristic: find the last meaningful text block
                        // We ignore scripts, styles, buttons, inputs, and short text
                        var walker = document.createTreeWalker(
                            document.body,
                            NodeFilter.SHOW_ELEMENT,
                            {
                                acceptNode: function(node) {
                                    if (['SCRIPT', 'STYLE', 'BUTTON', 'INPUT', 'TEXTAREA', 'NAV', 'HEADER', 'FOOTER'].includes(node.tagName)) {
                                        return NodeFilter.FILTER_REJECT;
                                    }
                                    if (node.innerText && node.innerText.length > 50) {
                                        return NodeFilter.FILTER_ACCEPT;
                                    }
                                    return NodeFilter.FILTER_SKIP;
                                }
                            }
                        );

                        var lastNode = null;
                        while(walker.nextNode()) {
                            lastNode = walker.currentNode;
                        }

                        if (lastNode) {
                            text = lastNode.innerText;
                        } else {
                            // Fallback to body text if no specific block found
                            text = document.body.innerText;
                        }
                    }

                    if (text && text.length > 0) {
                        AndroidWebAPI.copyToClipboard(text);
                        // Visual feedback
                        var originalColor = btn.style.backgroundColor;
                        btn.style.backgroundColor = '#03DAC5'; // Teal/Success color
                        setTimeout(function() {
                            btn.style.backgroundColor = originalColor;
                        }, 500);
                    } else {
                         AndroidWebAPI.onPreviewError("No text found to copy.");
                    }
                };

                document.body.appendChild(btn);
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script, null)
    }
    private fun injectAdvancedMediaDetector() {
        val script = """
            javascript:(function() {
                'use strict';
                if (window.AndroidMediaDetector) return;

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
                        if (!url || url.startsWith('data:') || url.startsWith('blob:')) return;

                        // Basic extension check
                        if (url.match(/\.(mp4|mkv|webm|m3u8|mpd|mov|avi|flv|m4v)(\?|${'$'})/i)) {
                             this.notify(url, 'video');
                        } else if (url.match(/\.(mp3|aac|m4a|wav|ogg)(\?|${'$'})/i)) {
                             this.notify(url, 'audio');
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

                    scanDOM: function() {
                        document.querySelectorAll('video, audio, source').forEach(el => {
                            if (el.src) {
                                const type = (el.closest('audio') || el.nodeName === 'AUDIO') ? 'audio' : 'video';
                                this.checkUrl(el.src, type);
                            }
                        });
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
        fun onMediaDetected(url: String, type: String) {
            activity.runOnUiThread {
                try {
                    if (url.isNotEmpty() && url != "about:blank" && !url.startsWith("data:")) {
                         val existsAlready = synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.any { it.url == url }
                         }

                         if (!existsAlready) {
                             val category = if (type.contains("audio", true)) MediaCategory.AUDIO else MediaCategory.VIDEO

                             val detectedFormat = activity.detectVideoFormat(url)
                             val quality = activity.extractQualityFromUrl(url)
                             val enhancedTitle = activity.generateSmartFileName(url, detectedFormat.extension, quality, category)

                             val mediaFile = MediaFile(
                                url = url,
                                title = enhancedTitle,
                                mimeType = detectedFormat.mimeType,
                                quality = quality,
                                category = category,
                                fileSize = "Unknown",
                                language = null,
                                isMainContent = false
                            )

                            synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.add(mediaFile)
                            }
                            activity.updateFabVisibility()
                            android.util.Log.d("MediaStateInterface", "Advanced detection found: $url")
                         }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaStateInterface", "Error in onMediaDetected: ${e.message}")
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
            activity.runOnUiThread {
                if (videoUrl.isNotEmpty() && videoUrl != "about:blank") {
                    activity.currentVideoUrl = videoUrl
                    android.util.Log.d("MediaStateInterface", "Video found: $videoUrl")
                }
            }
        }
        @JavascriptInterface
        fun onMediaPlay() {
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaPlay called")
                activity.isMediaPlaying = true
                activity.startOrUpdatePlaybackService()
            }
        }

        @JavascriptInterface
        fun onMediaPause() {
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaPause called")
                activity.isMediaPlaying = false
                activity.startOrUpdatePlaybackService()
            }
        }

        @JavascriptInterface
        fun onMediaEnded() {
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

    val fileNameFromUrl = uri.lastPathSegment?.substringBeforeLast("?")

    val baseName = when {
        !fileNameFromUrl.isNullOrBlank() && fileNameFromUrl.contains('.') -> {
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
    private fun extractLanguageFromUrl(url: String): String? { return null }
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
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return suspiciousDomains.any { host.contains(it) }
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
    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        val cleanUrl = lower.substringBefore('?')
        if (isAdOrTrackingUrl(lower)) return false
        return cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".vtt") || cleanUrl.endsWith(".srt") || lower.contains("videoplayback")
    }
    private fun isAdOrTrackingUrl(url: String): Boolean {
        val adIndicators = listOf("googleads.", "doubleclick.net", "adsystem", "/ads/")
        return adIndicators.any { url.contains(it) }
    }
    private fun isMainVideoContent(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("videoplayback") || lower.contains("manifest")
    }
    private fun updateFabVisibility() {
        if (isYouTubeUrl(webView.url)) {
            binding.fabShowMedia.setImageResource(R.drawable.ic_download) // Ensure you have this or similar
            binding.fabShowMedia.visibility = View.VISIBLE
            // We override the click listener if it's YouTube
            binding.fabShowMedia.setOnClickListener {
                if (isYouTubeUrl(webView.url)) {
                    Toast.makeText(this, "Analyzing YouTube video...", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript(YouTubeHelper.EXTRACTION_SCRIPT, null)
                } else {
                    showMediaListDialog()
                }
            }
        } else {
             // Reset to default behavior
            binding.fabShowMedia.setImageResource(android.R.drawable.ic_menu_add) // Or your default icon
            binding.fabShowMedia.visibility = if (detectedMediaFiles.isNotEmpty()) View.VISIBLE else View.GONE
            binding.fabShowMedia.setOnClickListener {
                showMediaListDialog()
            }
        }
    }

    private fun isYouTubeUrl(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return (lower.contains("youtube.com/watch") || lower.contains("m.youtube.com/watch") || lower.contains("youtu.be/")) && !lower.contains("googleads")
    }

    private fun checkForYouTube(url: String?) {
        runOnUiThread {
            updateFabVisibility()
        }
    }
    private fun fetchSubtitleSnippet(mediaFile: MediaFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URL(mediaFile.url).openConnection() as java.net.HttpURLConnection
                val userAgent = withContext(Dispatchers.Main) { webView.settings.userAgentString }
                val cookie = CookieManager.getInstance().getCookie(mediaFile.url)
                connection.setRequestProperty("User-Agent", userAgent)
                if (cookie != null) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                // Try to get just the beginning of the file to save bandwidth
                // Most servers support range requests
                connection.setRequestProperty("Range", "bytes=0-4096")

                connection.connect()

                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val result = SubtitleUtils.extractSnippet(text)

                if (!result.snippet.isNullOrBlank()) {
                     val detectedFormat = detectVideoFormat(mediaFile.url)
                     val prefix = if (!result.language.isNullOrBlank()) "[${result.language}] " else ""
                     val newTitle = "$prefix${result.snippet}${detectedFormat.extension}"
                     updateMediaTitle(mediaFile.url, newTitle)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to fetch subtitle snippet: ${e.message}")
            }
        }
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

    private fun showYouTubeQualityDialog(video: YouTubeVideo) {
        val formats = video.formats.sortedByDescending { it.height }
        val options = formats.map { "${it.qualityLabel} - ${it.mimeType.substringBefore(';')} " }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Download: ${video.title}")
            .setItems(options) { _, which ->
                val selectedFormat = formats[which]
                downloadYouTubeVideo(video.title, selectedFormat)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadYouTubeVideo(title: String, format: YouTubeFormat) {
        try {
            val sanitizedTitle = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val fileName = "${sanitizedTitle}_${format.height}p.mp4"

            val request = DownloadManager.Request(Uri.parse(format.url))
                .setTitle(fileName)
                .setDescription("Downloading YouTube Video")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(webView.url)
            request.addRequestHeader("User-Agent", userAgent)
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMediaListDialog() {
        if (detectedMediaFiles.isEmpty()) {
            Toast.makeText(this, "No media files detected.", Toast.LENGTH_SHORT).show()
            return
        }
        val mediaFilesCopy = synchronized(detectedMediaFiles) {
            detectedMediaFiles.toList()
        }
        val dialogBinding = DialogMediaListBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        currentMediaListAdapter = MediaListAdapter(mediaFilesCopy, { mediaFile ->
            // Tap to download
            showRenameDialog(mediaFile)
            dialog.dismiss()
        }, { mediaFile ->
            // Long press to "Open With"
            openMediaWith(mediaFile)
            dialog.dismiss()
        })
        dialog.setOnDismissListener {
            currentMediaListAdapter = null
        }
        dialogBinding.mediaRecyclerView.layoutManager = LinearLayoutManager(this)
        dialogBinding.mediaRecyclerView.adapter = currentMediaListAdapter
        dialog.show()
    }

    private fun updateMediaTitle(url: String, newTitle: String) {
        synchronized(detectedMediaFiles) {
            val index = detectedMediaFiles.indexOfFirst { it.url == url }
            if (index != -1) {
                val oldFile = detectedMediaFiles[index]
                if (oldFile.title != newTitle) {
                    val newFile = oldFile.copy(title = newTitle)
                    detectedMediaFiles[index] = newFile
                    runOnUiThread {
                        currentMediaListAdapter?.notifyItemChanged(index)
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

    private fun showRenameDialog(mediaFile: MediaFile) {
        val input = EditText(this).apply {
            setText(mediaFile.title.substringBeforeLast('.'))
            selectAll()
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Download File")
            .setMessage("Quality: ${mediaFile.quality}")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val newName = input.text.toString().trim()
                val finalName = if (newName.isNotEmpty()) "$newName.${mediaFile.title.substringAfterLast('.')}" else mediaFile.title
                downloadMediaFile(mediaFile.copy(title = finalName))
            }
            .setNegativeButton("Cancel", null)

        if (mediaFile.url.startsWith("blob:") || mediaFile.mimeType.startsWith("text/") || mediaFile.title.endsWith(".vtt") || mediaFile.title.endsWith(".srt")) {
            builder.setNeutralButton("Preview") { _, _ ->
                val safeUrl = mediaFile.url.replace("\"", "\\\"")
                val safeTitle = mediaFile.title.replace("\"", "\\\"")
                val safeMime = mediaFile.mimeType.replace("\"", "\\\"")
                val js = getBlobContentScript(
                    mediaFile.url,
                    false,
                    "function(text) { AndroidWebAPI.showPreview(text.substring(0, 20000), \"$safeTitle\", \"$safeMime\"); }",
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

        // Check for HLS (.m3u8)
        val isHls = mediaFile.url.contains(".m3u8", ignoreCase = true) ||
                    mediaFile.mimeType == "application/vnd.apple.mpegurl" ||
                    mediaFile.mimeType == "application/x-mpegURL"

        if (isHls) {
            val userAgent = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(mediaFile.url)
            HlsDownloadHelper.downloadHls(this, mediaFile.url, mediaFile.title, userAgent, cookie)
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
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_history -> {
                showHistory()
                true
            }
            R.id.menu_add_bookmark -> {
                addCurrentPageToBookmarks()
                true
            }
            R.id.menu_proxy_settings -> {
                showProxySettingsDialog()
                true
            }
            R.id.menu_settings -> {
                showMasterSettingsDialog()
                true
            }
            R.id.menu_user_scripts -> {
                val intent = Intent(this, UserScriptManagerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.menu_open_external -> {
                openCurrentPageInExternalBrowser()
                true
            }
            R.id.menu_debug_site -> {
                showSiteDebuggingOptions()
                true
            }
            R.id.menu_enable_media_detection -> {
                manualMediaScan()
                true
            }
            R.id.menu_debug_page -> {
                showPageSource()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun manualMediaScan() {
        val script = """
            (function() {
                const media = [];

                function addMedia(url, title, type, language) {
                     if(!url) return;
                     url = url.trim();
                     if(url.startsWith('//')) url = 'https:' + url;
                     media.push({ url: url, title: title || document.title, type: type || 'video', language: language });
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
                                    const absUrl = new URL(track.src, win.document.baseURI).href;
                                    const lang = track.srclang || '';
                                    const label = track.label || 'Subtitle';
                                    addMedia(absUrl, label, 'subtitle', lang);
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

                        val category = if (type == "video") MediaCategory.VIDEO else MediaCategory.SUBTITLE
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
                            isMainContent = false
                        )
                    }

                    var addedCount = 0
                    synchronized(detectedMediaFiles) {
                        val existingUrls = detectedMediaFiles.map { it.url }.toSet()
                        newMediaFiles.forEach {
                            if (!existingUrls.contains(it.url)) {
                                detectedMediaFiles.add(it)
                                addedCount++
                                // Trigger http fetch if needed
                                if (it.category == MediaCategory.SUBTITLE) {
                                    if (!it.url.startsWith("blob:")) {
                                        fetchSubtitleSnippet(it)
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
                            showMediaListDialog()
                        } else {
                            Toast.makeText(this, "No new media found since last scan. Showing existing list.", Toast.LENGTH_SHORT).show()
                            showMediaListDialog()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No media detected. Try playing the video first.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error parsing media scan result", e)
                runOnUiThread {
                    Toast.makeText(this, "Error scanning for media.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        AlertDialog.Builder(this)
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

    private fun showProxySettingsDialog() {
        val sharedPrefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        val currentHost = sharedPrefs.getString("proxy_host", "")
        val currentPort = sharedPrefs.getInt("proxy_port", -1)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val hostInput = EditText(this).apply {
            hint = "Proxy Host"
            setText(currentHost)
        }
        dialogView.addView(hostInput)

        val portInput = EditText(this).apply {
            hint = "Proxy Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            if (currentPort != -1) {
                setText(currentPort.toString())
            }
        }
        dialogView.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle("Proxy Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val host = hostInput.text.toString().trim()
                val portStr = portInput.text.toString().trim()
                val port = if (portStr.isNotEmpty()) portStr.toInt() else -1

                if (host.isNotEmpty() && port != -1) {
                    sharedPrefs.edit()
                        .putString("proxy_host", host)
                        .putInt("proxy_port", port)
                        .apply()
                    applyProxy()
                } else {
                    Toast.makeText(this, "Invalid host or port", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Disable Proxy") { _, _ ->
                sharedPrefs.edit()
                    .remove("proxy_host")
                    .remove("proxy_port")
                    .apply()
                applyProxy()
                Toast.makeText(this, "Proxy disabled", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
    private fun showMasterSettingsDialog() {
        val items = arrayOf("Content Blocking", "Manage Blocked Sites", "Manage Whitelist", "Backup and Restore", "Background Loading")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showContentBlockingDialog()
                    1 -> showBlockedSitesDialog()
                    2 -> showWhitelistManagementDialog()
                    3 -> showBackupRestoreDialog()
                    4 -> showBackgroundLoadingDialog()
                }
            }
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

        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle("Whitelisted Sites")
            .setAdapter(adapter) { _, which ->
                val siteToRemove = whitelistedSites[which]
                AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
    private fun showSiteDebuggingOptions() {
        val options = arrayOf("Change User Agent", "Add to Whitelist", "Enable Remote Debugging", "Clear Cookies")
        AlertDialog.Builder(this)
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

    AlertDialog.Builder(this)
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
        val whitelist = sharedPrefs.getStringSet("WHITELIST_URLS", setOf()) ?: setOf()
        return whitelist.any { whitelistedDomain -> host == whitelistedDomain || host.endsWith(".$whitelistedDomain") }
    }
    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies {
            Toast.makeText(this, "All cookies cleared.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun loadEnabledUserScripts() {
        lifecycleScope.launch(Dispatchers.IO) {
            enabledUserScripts = db.userScriptDao().getEnabledScripts()
        }
    }
    private fun injectUserscript(script: UserScript) {
        val polyfillBuilder = StringBuilder()
        if (script.grants.contains("GM_addStyle")) {
            polyfillBuilder.append("""
                window.GM_addStyle = function(css) {
                    GMApi.addStyle(css);
                };
            """.trimIndent())
        }
        val polyfillScript = polyfillBuilder.toString()
        if (polyfillScript.isNotEmpty()) {
            webView.evaluateJavascript(polyfillScript, null)
        }
        val wrappedScript = "(function() { try { ${script.script} } catch (e) { console.error('Userscript error in ${script.name}:', e); } })();"
        webView.evaluateJavascript(wrappedScript, null)
    }

    private fun injectEarlyUserscripts(url: String?) {
        if (url == null) return
        val matchingScripts = enabledUserScripts.filter {
            it.shouldRunOnUrl(url) && it.runAt == UserScript.RunAt.DOCUMENT_START
        }
        for (script in matchingScripts) {
            injectUserscript(script)
        }
    }

    private fun injectPendingUserscripts() {
        val url = webView.url ?: return
        val matchingScripts = enabledUserScripts.filter {
            it.shouldRunOnUrl(url) && (it.runAt == UserScript.RunAt.DOCUMENT_END || it.runAt == UserScript.RunAt.DOCUMENT_IDLE)
        }
        for (script in matchingScripts) {
            injectUserscript(script)
        }
        pendingScriptsToInject.clear()
    }
    fun showBlockedNavigationDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Link Action")
            .setMessage("A page is trying to navigate or open a new window:$url")
            .setPositiveButton("Open") { _, _ ->
                webView.loadUrl(url)
            }
            .setNeutralButton("Open in Background") { _, _ ->
                openInNewTab(url, inBackground = true)
            }
            .setNegativeButton("Block") { _, _ ->
                Toast.makeText(this, "Action blocked", Toast.LENGTH_SHORT).show()
                addToBlockedList(Uri.parse(url).host ?: url)
            }
            .show()
    }
}