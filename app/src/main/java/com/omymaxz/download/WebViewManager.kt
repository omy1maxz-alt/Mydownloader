package com.omymaxz.download

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

object WebViewManager {
    private val webViewCache = ConcurrentHashMap<String, WebView>()

    @Synchronized
    fun getOrCreateWebView(context: MainActivity, tabId: String): WebView {
        return webViewCache.getOrPut(tabId) {
            WebView(context).apply {
                // Ensure WebView is created on main thread
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    throw IllegalStateException("WebView must be created on main thread")
                }
                context.setupWebView(this, tabId)
            }
        }
    }

    fun getWebView(tabId: String): WebView? {
        return webViewCache[tabId]
    }

    fun removeWebView(tabId: String) {
        webViewCache.remove(tabId)?.destroy()
    }

    fun clear() {
        webViewCache.values.forEach { it.destroy() }
        webViewCache.clear()
    }
}
