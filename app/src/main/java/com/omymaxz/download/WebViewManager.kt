package com.omymaxz.download

import android.content.Context
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

object WebViewManager {
    private val webViewCache = ConcurrentHashMap<String, WebView>()

    fun getOrCreateWebView(context: MainActivity, tabId: String): WebView {
        return webViewCache.getOrPut(tabId) {
            WebView(context).apply {
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
