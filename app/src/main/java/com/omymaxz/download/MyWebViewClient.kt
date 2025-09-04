package com.omymaxz.download

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class MyWebViewClient(private val activity: MainActivity) : WebViewClient() {
    private var lastNavigationTime = 0L
    var navigationCount = 0 // public for testing

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        navigationCount = 0
        activity.isPageLoading = true
        activity.pendingScriptsToInject.clear()
        activity.binding.progressBar.visibility = View.VISIBLE
        activity.binding.urlEditTextToolbar.setText(url)
        synchronized(activity.detectedMediaFiles) {
            activity.detectedMediaFiles.clear()
        }
        activity.runOnUiThread { activity.updateFabVisibility() }
        if (url?.contains("perchance.org") == true) {
            activity.injectPerchanceFixes(view)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        activity.isPageLoading = false
        activity.binding.progressBar.visibility = View.GONE
        activity.updateToolbarNavButtonState()
        if (url?.contains("perchance.org") == true) {
            activity.injectPerchanceFixes(view)
        }
        activity.injectPendingUserscripts()
        url?.let {
            activity.addToHistory(it)
            if (activity.currentTabIndex in activity.tabs.indices) {
                activity.tabs[activity.currentTabIndex].url = it
                activity.tabs[activity.currentTabIndex].title = view?.title ?: "No Title"
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (activity.isUrlWhitelisted(url)) {
            return false
        }
        if (url.contains("perchance.org")) {
            activity.openInCustomTab(url)
            return true
        }
        val currentTime = System.currentTimeMillis()
        if (activity.isAdDomain(url) || activity.isInBlockedList(url) || isSuspiciousRedirectPattern(url, currentTime, view?.url)) {
            activity.showBlockedNavigationDialog(url)
            return true
        }
        lastNavigationTime = currentTime
        navigationCount++
        return false
    }

    private fun isSuspiciousRedirectPattern(url: String, currentTime: Long, previousUrl: String?): Boolean {
        val settingsPrefs = activity.getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        if (!settingsPrefs.getBoolean("BLOCK_REDIRECTS", true)) return false
        val timeSinceLastNav = currentTime - lastNavigationTime
        val isDifferentHost = Uri.parse(url).host != Uri.parse(previousUrl ?: "").host
        return isDifferentHost && (timeSinceLastNav < 1000 && navigationCount > 0)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        if (activity.isUrlWhitelisted(url)) {
            return super.shouldInterceptRequest(view, request)
        }
        if (activity.isAdDomain(url)) {
            return createEmptyResponse()
        }
        if (activity.isMediaUrl(url)) {
            try {
                val category = MediaCategory.fromUrl(url)
                val isMainContent = activity.isMainVideoContent(url)
                if (category == MediaCategory.VIDEO && isMainContent) {
                    activity.currentVideoUrl = url
                    if(activity.serviceBound){
                        activity.mediaService?.updateMediaInfo(activity.binding.webView.title ?: "Web Video")
                    }
                }
                val detectedFormat = activity.detectVideoFormat(url)
                val quality = activity.extractQualityFromUrl(url)
                val enhancedTitle = activity.generateSmartFileName(url, detectedFormat.extension, quality, category)
                val fileSize = activity.estimateFileSize(url, category)
                val language = activity.extractLanguageFromUrl(url)
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
                val existsAlready = synchronized(activity.detectedMediaFiles) {
                    activity.detectedMediaFiles.any { it.url == url }
                }
                if (!existsAlready) {
                    synchronized(activity.detectedMediaFiles) {
                        activity.detectedMediaFiles.add(mediaFile)
                    }
                    activity.runOnUiThread { activity.updateFabVisibility() }
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
