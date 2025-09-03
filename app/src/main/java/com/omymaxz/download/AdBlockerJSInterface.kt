package com.omymaxz.download

import android.webkit.JavascriptInterface
import android.widget.Toast

class AdBlockerJSInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun showBlockedPopup() {
        activity.runOnUiThread {
            Toast.makeText(activity, "🚫 Pop-up blocked", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun showBlockedRedirect(url: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, "🚫 Redirect to ad blocked", Toast.LENGTH_SHORT).show()
        }
    }
}
