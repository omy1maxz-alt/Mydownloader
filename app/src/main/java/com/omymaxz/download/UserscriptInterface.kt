package com.omymaxz.download

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UserscriptInterface(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope
) {
    private val gson = Gson()

    data class XmlHttpRequestDetails(
        val method: String,
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
        val responseHeaders: String
    )

    @JavascriptInterface
    fun log(message: String) {
        Log.d("Userscript", message)
    }

    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
        scope.launch(Dispatchers.IO) {
            try {
                val details = gson.fromJson(detailsJson, XmlHttpRequestDetails::class.java)
                val url = URL(details.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = details.method
                details.headers?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }
                if (details.data != null) {
                    connection.doOutput = true
                    connection.outputStream.write(details.data.toByteArray())
                }

                val status = connection.responseCode
                val statusText = connection.responseMessage
                val responseHeaders = connection.headerFields.map { (key, value) ->
                    "$key: ${value.joinToString(", ")}"
                }.joinToString("\n")

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val responseText = reader.readText()
                reader.close()

                val response = XmlHttpResponse(responseText, status, statusText, responseHeaders)
                val responseJson = gson.toJson(response)

                launch(Dispatchers.Main) {
                    webView.evaluateJavascript("window.userscript_callbacks['$callbackId']?.onload?.($responseJson);", null)
                }
            } catch (e: Exception) {
                Log.e("UserscriptInterface", "xmlHttpRequest error", e)
                launch(Dispatchers.Main) {
                    webView.evaluateJavascript("window.userscript_callbacks['$callbackId']?.onerror?.('${e.message}');", null)
                }
            }
        }
    }
}
