package com.omymaxz.download

import android.webkit.WebView
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.util.Base64

class GMApi(private val webView: WebView) {

    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun addStyle(css: String) {
        // Use post to ensure execution on the main thread
        handler.post {
            // Escape the CSS to be safely injected into a JavaScript template literal.
            // First, escape backslashes, then backticks, then dollar signs.
            val escapedCss = css.replace("\\", "\\\\")
                                .replace("`", "\\`")
                                .replace("\${", "\\\${")

            val script = """
                (function() {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.innerHTML = `${escapedCss}`;
                    document.head.appendChild(style);
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        }
    }
}
