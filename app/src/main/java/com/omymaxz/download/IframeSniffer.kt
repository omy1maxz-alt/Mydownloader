package com.omymaxz.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

class IframeSniffer(private val context: Context, private val onMediaFound: (String) -> Unit) {

    private var hiddenWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    fun sniff(url: String) {
        mainHandler.post {
            hiddenWebView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null

                        if (isMediaUrl(reqUrl)) {
                            Log.d("IframeSniffer", "Detected media URL in hidden WebView: $reqUrl")
                            if (!isDestroyed) {
                                onMediaFound(reqUrl)
                                destroyWebView()
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                loadUrl(url)
            }

            // Timeout to destroy the WebView if nothing is found within 15 seconds
            mainHandler.postDelayed({
                destroyWebView()
            }, 15000)
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains(".mp4", ignoreCase = true) ||
               url.contains(".mkv", ignoreCase = true)
    }

    private fun destroyWebView() {
        if (isDestroyed) return
        isDestroyed = true
        mainHandler.post {
            hiddenWebView?.stopLoading()
            hiddenWebView?.loadUrl("about:blank")
            hiddenWebView?.clearHistory()
            hiddenWebView?.removeAllViews()
            hiddenWebView?.destroy()
            hiddenWebView = null
        }
    }
}
