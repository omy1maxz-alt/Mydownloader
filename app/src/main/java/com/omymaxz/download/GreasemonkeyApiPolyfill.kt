package com.omymaxz.download

import android.content.Context
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GreasemonkeyApiPolyfill(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    @JavascriptInterface
    fun xmlHttpRequest(options: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(options)
                val method = json.optString("method", "GET")
                val url = json.getString("url")
                val headers = json.optJSONObject("headers")
                val data = json.optString("data", null)
                val urlConnection = URL(url).openConnection() as HttpURLConnection
                urlConnection.requestMethod = method
                headers?.keys()?.forEach { key ->
                    urlConnection.setRequestProperty(key, headers.getString(key))
                }
                if (method == "POST" && data != null) {
                    urlConnection.doOutput = true
                    OutputStreamWriter(urlConnection.outputStream).use { it.write(data) }
                }
                val responseCode = urlConnection.responseCode
                android.util.Log.d("GM_Polyfill", "GM_xmlHttpRequest to $url completed with status $responseCode")
            } catch (e: Exception) {
                android.util.Log.e("GM_Polyfill", "GM_xmlHttpRequest failed: ${e.message}", e)
            }
        }
    }
}
