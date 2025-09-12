package com.omymaxz.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executor
import androidx.recyclerview.widget.GridLayoutManager
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
    private val detectedMediaFiles = Collections.synchronizedList(mutableListOf<MediaFile>())
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
    var isMediaPlaying = false
    private val historyResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val urlToLoad = result.data?.getStringExtra("URL_TO_LOAD")
            if (urlToLoad != null) {
                binding.webView.loadUrl(urlToLoad)
                showWebView()
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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            runOnUiThread {
                if (binding.webView.visibility == View.VISIBLE && binding.webView.url != null) {
                    Toast.makeText(this@MainActivity, "Connection restored, reloading...", Toast.LENGTH_SHORT).show()
                    binding.webView.reload()
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
    private val userscriptInterface by lazy { UserscriptInterface(this, binding.webView, lifecycleScope) }
    private val gmApi by lazy { GMApi(binding.webView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkInitialStoragePermissions()

        db = AppDatabase.getDatabase(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        initializeTabs()
        setupUrlBarInToolbar()
        loadLastUsedName()
        setupWebView()
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
            // This runnable is called after the proxy settings have been applied.
            // You can add any post-proxy-set logic here if needed.
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
        if (currentTabIndex in tabs.indices) {
            val currentTab = tabs[currentTabIndex]
            if (currentTab.url != null && binding.webView.url != currentTab.url) {
                restoreTabState(currentTabIndex)
            }
        }
    }

    override fun onStop() {
        super.onStop()

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            mediaService = null
        }

        if (isMediaPlaying && !isChangingConfigurations) {
            startOrUpdatePlaybackService(shouldTakeOver = true)
            // REMOVED: binding.webView.evaluateJavascript("document.querySelector('video')?.pause();", null)
        } else {
            if (hasStartedForegroundService && !isMediaPlaying) {
                 stopPlaybackService()
            }
        }

        val currentUrl = if (binding.webView.visibility == View.VISIBLE) binding.webView.url else null
        val currentTitle = if (binding.webView.visibility == View.VISIBLE) binding.webView.title else null
        var currentState: Bundle? = null

        if (currentTabIndex in tabs.indices && binding.webView.visibility == View.VISIBLE) {
            currentState = Bundle()
            try {
                binding.webView.saveState(currentState)
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

    override fun onDestroy() {
        super.onDestroy()
        if (hasStartedForegroundService) {
            stopPlaybackService()
            hasStartedForegroundService = false
        }
    }

    private fun setupToolbarNavButtons() {
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }
        binding.refreshButton.setOnClickListener {
            binding.webView.reload()
        }
    }

    private fun updateToolbarNavButtonState() {
        val canGoBack = binding.webView.canGoBack()
        binding.backButton.isEnabled = canGoBack
        binding.backButton.alpha = if (canGoBack) 1.0f else 0.5f
        val canGoForward = binding.webView.canGoForward()
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
            if (binding.webView.visibility == View.VISIBLE) {
                currentTab.url = binding.webView.url
                currentTab.title = binding.webView.title ?: "New Tab"
                val state = Bundle()
                try {
                    binding.webView.saveState(state)
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
            binding.webView.loadUrl(tab.url!!)
            showWebView()
            if (tab.state != null) {
                try {
                    binding.webView.restoreState(tab.state!!)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to restore WebView state: ${e.message}")
                }
            }
        } else if (tab.state != null) {
             try {
                binding.webView.restoreState(tab.state!!)
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
            }
            showStartPage()
        }
    }

    private fun showStartPage() {
        binding.webView.visibility = View.GONE
        binding.bookmarkRecyclerView.visibility = View.VISIBLE
        binding.urlEditTextToolbar.setText("")
        updateToolbarNavButtonState()
    }

    private fun showWebView() {
        binding.webView.visibility = View.VISIBLE
        binding.bookmarkRecyclerView.visibility = View.GONE
        updateToolbarNavButtonState()
    }

    private fun setupStartPage() {
        bookmarkAdapter = BookmarkAdapter(
            bookmarks = mutableListOf(),
            onItemClick = { bookmark ->
                binding.webView.loadUrl(bookmark.url)
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
        binding.webView.loadUrl(url)
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
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else if (binding.webView.visibility == View.VISIBLE) {
            showStartPage()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Exit App?")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("Yes") { _, _ -> finish() }
                .setNegativeButton("No", null)
                .show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(true)
            addJavascriptInterface(WebAPIPolyfill(this@MainActivity), "AndroidWebAPI")
            addJavascriptInterface(MediaStateInterface(this@MainActivity), "AndroidMediaState")
            addJavascriptInterface(userscriptInterface, "AndroidUserscriptAPI")
            addJavascriptInterface(gmApi, "GMApi")
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                            meta.setAttribute('content', 'width=1920, user-scalable=0.5');
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
                    binding.progressBar.visibility = View.VISIBLE
                    binding.urlEditTextToolbar.setText(url)
                    synchronized(detectedMediaFiles) {
                        detectedMediaFiles.clear()
                    }
                    runOnUiThread { updateFabVisibility() }
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    updateToolbarNavButtonState()
                    if (url?.contains("perchance.org") == true) {
                        injectPerchanceFixes(view)
                    }
                    injectMediaStateDetector()
                    injectPendingUserscripts()
                    url?.let {
                        addToHistory(it)
                        if (currentTabIndex in tabs.indices) {
                            tabs[currentTabIndex].url = it
                            tabs[currentTabIndex].title = view?.title ?: "No Title"
                        }
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (isUrlWhitelisted(url)) {
                        return false
                    }
                    if (url.contains("perchance.org")) {
                        openInCustomTab(url)
                        return true
                    }
                    val currentTime = System.currentTimeMillis()
                    if (isAdDomain(url) || isInBlockedList(url) || (!isUrlWhitelisted(url) && isSuspiciousRedirectPattern(url, currentTime, view?.url, lastNavigationTime, navigationCount))) {
                        showBlockedNavigationDialog(url)
                        return true
                    }
                    lastNavigationTime = currentTime
                    navigationCount++
                    return false
                }
                private fun isSuspiciousRedirectPattern(url: String, currentTime: Long, previousUrl: String?, lastNavTime: Long, navCount: Int): Boolean {
                    val settingsPrefs = getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
                    if (!settingsPrefs.getBoolean("BLOCK_REDIRECTS", true)) return false
                    val timeSinceLastNav = currentTime - lastNavTime
                    val isDifferentHost = Uri.parse(url).host != Uri.parse(previousUrl ?: "").host
                    return isDifferentHost && (timeSinceLastNav < 1000 && navCount > 0)
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
                        // Allow popups for whitelisted sites
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
private fun injectMediaStateDetector() {
    val script = """
        javascript:(function() {
            function setupVideoMonitoring() {
                const videos = document.querySelectorAll('video');
                videos.forEach(function(video, index) {
                    if (!video.hasAttribute('data-monitored')) {
                        video.setAttribute('data-monitored', 'true');

                        video.addEventListener('loadstart', function() {
                            AndroidMediaState.onVideoFound(video.src || video.currentSrc || window.location.href);
                        });

                        video.addEventListener('play', function() {
                            AndroidMediaState.onMediaPlay();
                            video.setAttribute('data-was-playing', 'true');
                        });

                        video.addEventListener('pause', function() {
                            AndroidMediaState.onMediaPause();
                            video.removeAttribute('data-was-playing');
                        });

                        video.addEventListener('ended', function() {
                            AndroidMediaState.onMediaEnded();
                            video.removeAttribute('data-was-playing');
                        });

                        video.addEventListener('error', function(e) {
                            AndroidMediaState.onError('Video error: ' + e.message);
                        });
                    }
                });
            }

            setupVideoMonitoring();

            const observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    if (mutation.type === 'childList') {
                        setupVideoMonitoring();
                    }
                });
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        })();
    """.trimIndent()

    binding.webView.evaluateJavascript(script, null)
}

private fun startOrUpdatePlaybackService(shouldTakeOver: Boolean = false, isProactiveStart: Boolean = false) {
    if (currentVideoUrl == null && !isProactiveStart) return

    val intent = Intent(this, MediaForegroundService::class.java)

    if (shouldTakeOver && currentVideoUrl != null) {
        binding.webView.evaluateJavascript("document.querySelector('video')?.currentTime || 0;") { positionStr ->
            val position = positionStr.toFloatOrNull() ?: 0f
            val headers = mutableMapOf<String, String>()
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(currentVideoUrl) ?: ""
            if (cookies.isNotEmpty()) {
                headers["Cookie"] = cookies
            }
            headers["User-Agent"] = binding.webView.settings.userAgentString
            binding.webView.url?.let { headers["Referer"] = it }

            intent.apply {
                action = MediaForegroundService.ACTION_PLAY
                putExtra("url", currentVideoUrl)
                putExtra("title", binding.webView.title ?: "Web Media")
                putExtra("position", position)
                putExtra("headers", HashMap(headers))
            }
            startForegroundServiceWithCatch(intent)
        }
    } else if (isProactiveStart) {
        intent.action = MediaForegroundService.ACTION_START_PROACTIVE
        startForegroundServiceWithCatch(intent)
    }
}

private fun startForegroundServiceWithCatch(intent: Intent) {
    try {
        ContextCompat.startForegroundService(this, intent)
        hasStartedForegroundService = true
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error starting MediaForegroundService", e)
    }
}

    fun stopPlaybackService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        val serviceIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = MediaForegroundService.ACTION_STOP
        }
        stopService(serviceIntent)
        hasStartedForegroundService = false
    }
    private fun resumeMediaPlayback() {
        val resumeScript = "javascript:(function() { var video = document.querySelector('video'); if (video && video.paused && video.hasAttribute('data-was-playing')) { video.play().catch(function(e) {}); } })();"
        binding.webView.loadUrl(resumeScript)
    }
    inner class MediaStateInterface(private val activity: MainActivity) {
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
                if (!activity.hasStartedForegroundService) {
                    activity.startOrUpdatePlaybackService(isProactiveStart = true)
                }
            }
        }

        @JavascriptInterface
        fun onMediaPause() {
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaPause called")
                activity.isMediaPlaying = false
                if (!activity.isAppInBackground && activity.hasStartedForegroundService) {
                    activity.stopPlaybackService()
                }
            }
        }

        @JavascriptInterface
        fun onMediaEnded() {
            activity.runOnUiThread {
                android.util.Log.d("MediaStateInterface", "onMediaEnded called")
                activity.isMediaPlaying = false
                activity.currentVideoUrl = null
                if (activity.hasStartedForegroundService) {
                    activity.stopPlaybackService()
                }
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            activity.runOnUiThread {
                android.util.Log.e("MediaStateInterface", "Media error: $error")
                activity.isMediaPlaying = false
                activity.currentVideoUrl = null
                if (activity.hasStartedForegroundService) {
                    activity.stopPlaybackService()
                }
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
        return when {
            lowerUrl.endsWith(".mp4") -> VideoFormat(".mp4", "video/mp4")
            lowerUrl.endsWith(".mkv") -> VideoFormat(".mkv", "video/x-matroska")
            lowerUrl.endsWith(".webm") -> VideoFormat(".webm", "video/webm")
            lowerUrl.endsWith(".avi") -> VideoFormat(".avi", "video/x-msvideo")
            lowerUrl.endsWith(".mov") -> VideoFormat(".mov", "video/quicktime")
            lowerUrl.endsWith(".flv") -> VideoFormat(".flv", "video/x-flv")
            lowerUrl.contains(".m3u8") -> VideoFormat(".m3u8", "application/vnd.apple.mpegurl")
            lowerUrl.endsWith(".m4v") -> VideoFormat(".m4v", "video/mp4")
            lowerUrl.endsWith(".vtt") -> VideoFormat(".vtt", "text/vtt")
            lowerUrl.endsWith(".srt") -> VideoFormat(".srt", "application/x-subrip")
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
        val patterns = listOf("(?<=watch\\?v=)[^&]+", "(?<=youtu.be/)[^?]+", "(?<=embed/)[^?]+", "(?<=v/)[^?]+")
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
        if (isAdOrTrackingUrl(lower)) return false
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".vtt") || lower.endsWith(".srt") || lower.contains("videoplayback")
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
        binding.fabShowMedia.visibility = if (detectedMediaFiles.isNotEmpty()) View.VISIBLE else View.GONE
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
        val adapter = MediaListAdapter(mediaFilesCopy, { mediaFile ->
            showRenameDialog(mediaFile)
            dialog.dismiss()
        }, { mediaFile ->
            openInExternalPlayer(mediaFile.url)
        })
        dialogBinding.mediaRecyclerView.layoutManager = LinearLayoutManager(this)
        dialogBinding.mediaRecyclerView.adapter = adapter
        dialog.show()
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
    private fun showRenameDialog(mediaFile: MediaFile) {
        val input = EditText(this).apply {
            setText(mediaFile.title.substringBeforeLast('.'))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Download File")
            .setMessage("Quality: ${mediaFile.quality}")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val newName = input.text.toString().trim()
                val finalName = if (newName.isNotEmpty()) "$newName.${mediaFile.title.substringAfterLast('.')}" else mediaFile.title
                downloadMediaFile(mediaFile.copy(title = finalName))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun downloadMediaFile(mediaFile: MediaFile) {
        try {
            val request = DownloadManager.Request(Uri.parse(mediaFile.url))
                .setTitle(mediaFile.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, mediaFile.title)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Download started: ${mediaFile.title}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun addToHistory(url: String) {
        val title = binding.webView.title ?: "No Title"
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
            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun addCurrentPageToBookmarks() {
        if (binding.webView.visibility == View.VISIBLE && !binding.webView.url.isNullOrEmpty()) {
            val url = binding.webView.url!!
            val title = binding.webView.title ?: "No Title"
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
        val items = arrayOf("Content Blocking", "Manage Blocked Sites", "Manage Whitelist", "Backup and Restore")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showContentBlockingDialog()
                    1 -> showBlockedSitesDialog()
                    2 -> showWhitelistManagementDialog()
                    3 -> showBackupRestoreDialog()
                }
            }
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
                if (binding.webView.visibility == View.VISIBLE) {
                    binding.webView.reload()
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
        val currentUrl = binding.webView.url
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
                        binding.webView.url?.let { url ->
                            Uri.parse(url).host?.let { host ->
                                addToWhitelist(host)
                                binding.webView.reload()
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
    val settings = binding.webView.settings

    AlertDialog.Builder(this)
        .setTitle("Change Browser Identity")
        .setItems(userAgents) { _, which ->
            val newUserAgent: String
            when (which) {
               1, 2 -> { // Desktop Modes
    isDesktopMode = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true  // ENABLE OVERVIEW MODE FOR DESKTOP
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
                else -> { // Default Mobile
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
// After settings.userAgentString = newUserAgent, add:
if (isDesktopMode) {
    binding.webView.postDelayed({
        binding.webView.evaluateJavascript(
            "document.body.style.zoom = '0.5';", null
        )
    }, 100)
}
            binding.webView.reload()
            binding.webView.requestLayout() // Force a re-layout

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
            binding.webView.evaluateJavascript(polyfillScript, null)
        }
        val wrappedScript = "(function() { try { ${script.script} } catch (e) { console.error('Userscript error in ${script.name}:', e); } })();"
        binding.webView.evaluateJavascript(wrappedScript, null)
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
        val url = binding.webView.url ?: return
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
                binding.webView.loadUrl(url)
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
