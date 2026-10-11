package com.omymaxz.download

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class UserscriptInterface(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope
) {
    private val gson = Gson()

    data class XmlHttpRequestDetails(
        val method: String?,
        val url: String,
        val headers: Map<String, String>?,
        val data: String?,
        val timeout: Long?,
        val responseType: String?
    )

    data class XmlHttpResponse(
        val responseText: String,
        val status: Int,
        val statusText: String,
        val responseHeaders: String,
        val finalUrl: String
    )

    @JavascriptInterface
    fun log(message: String) {
        Log.d("Userscript", message.take(2000))
    }

    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, message.take(500), Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun setValue(scriptName: String, key: String, value: String) {
        val prefs = context.getSharedPreferences("userscript_data_$scriptName", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun getValue(scriptName: String, key: String, defaultValue: String?): String? {
        val prefs = context.getSharedPreferences("userscript_data_$scriptName", Context.MODE_PRIVATE)
        return prefs.getString(key, defaultValue)
    }

    @JavascriptInterface
    fun deleteValue(scriptName: String, key: String) {
        val prefs = context.getSharedPreferences("userscript_data_$scriptName", Context.MODE_PRIVATE)
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun listValues(scriptName: String): String {
        val prefs = context.getSharedPreferences("userscript_data_$scriptName", Context.MODE_PRIVATE)
        return gson.toJson(prefs.all.keys)
    }

    @JavascriptInterface
    fun xmlHttpRequest(detailsJson: String, callbackId: String) {
        if (!callbackId.matches(Regex("gm_[A-Za-z0-9_-]{1,100}"))) {
            Log.w("UserscriptInterface", "Rejected malformed XHR callback ID")
            return
        }
        scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val details = gson.fromJson(detailsJson, XmlHttpRequestDetails::class.java)
                    ?: throw IllegalArgumentException("Missing request details")
                val url = URL(details.url)
                require(url.protocol.equals("http", true) || url.protocol.equals("https", true)) {
                    "Only HTTP(S) userscript requests are supported"
                }
                val method = (details.method ?: "GET").uppercase()
                require(method.matches(Regex("[A-Z]{3,10}"))) { "Invalid HTTP method" }
                val conn = url.openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = method
                conn.connectTimeout = boundedTimeout(details.timeout)
                conn.readTimeout = boundedTimeout(details.timeout)
                conn.instanceFollowRedirects = true
                details.headers?.forEach { (key, value) ->
                    require(key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_|~-]{1,128}"))) {
                        "Invalid HTTP header name"
                    }
                    require(!key.equals("Host", true) && !key.equals("Content-Length", true)) {
                        "Managed HTTP header is not allowed"
                    }
                    conn.setRequestProperty(key, value.take(8192))
                }
                if (details.data != null && method !in setOf("GET", "HEAD")) {
                    conn.doOutput = true
                    conn.setRequestProperty(
                        "Content-Type",
                        conn.getRequestProperty("Content-Type") ?: "text/plain; charset=utf-8"
                    )
                    conn.outputStream.use {
                        it.write(details.data.toByteArray(StandardCharsets.UTF_8))
                    }
                }

                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val responseText = stream?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val limit = 5 * 1024 * 1024
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= limit) { "Userscript response exceeds 5 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(StandardCharsets.UTF_8.name())
                }.orEmpty()
                val responseHeaders = conn.headerFields
                    .filterKeys { it != null }
                    .map { entry -> entry.key + ": " + entry.value.joinToString(", ") }
                    .joinToString("\n")
                    .take(32 * 1024)
                val response = XmlHttpResponse(
                    responseText = responseText,
                    status = status,
                    statusText = conn.responseMessage.orEmpty(),
                    responseHeaders = responseHeaders,
                    finalUrl = conn.url.toString()
                )
                val responseJson = gson.toJson(response)
                withContext(Dispatchers.Main) {
                    val idJson = org.json.JSONObject.quote(callbackId)
                    webView.evaluateJavascript(
                        "window.userscript_callbacks && window.userscript_callbacks[$idJson] && window.userscript_callbacks[$idJson].onload && window.userscript_callbacks[$idJson].onload($responseJson);",
                        null
                    )
                }
            } catch (e: Exception) {
                val error = e.message?.take(500) ?: "Request failed"
                Log.w("UserscriptInterface", "GM_xmlhttpRequest failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    val idJson = org.json.JSONObject.quote(callbackId)
                    val errorJson = org.json.JSONObject.quote(error)
                    webView.evaluateJavascript(
                        "window.userscript_callbacks && window.userscript_callbacks[$idJson] && window.userscript_callbacks[$idJson].onerror && window.userscript_callbacks[$idJson].onerror($errorJson);",
                        null
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun boundedTimeout(timeout: Long?): Int =
        (timeout ?: 15_000L).coerceIn(1_000L, 60_000L).toInt()
}
