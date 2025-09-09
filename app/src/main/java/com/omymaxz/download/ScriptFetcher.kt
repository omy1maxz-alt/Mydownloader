package com.omymaxz.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ScriptFetcher(
    private val scriptCacheDao: ScriptCacheDao,
    private val coroutineScope: CoroutineScope
) {
    suspend fun fetchScripts(urls: List<String>): List<String> {
        return withContext(Dispatchers.IO) {
            val scriptsContent = mutableListOf<String>()
            for (url in urls) {
                val cachedScript = scriptCacheDao.getScriptByUrl(url)
                if (cachedScript != null) {
                    scriptsContent.add(cachedScript.content)
                } else {
                    try {
                        val urlConnection = URL(url).openConnection() as HttpURLConnection
                        urlConnection.requestMethod = "GET"
                        urlConnection.connect()
                        val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                        val content = reader.readText()
                        reader.close()
                        if (content.isNotBlank()) {
                            scriptsContent.add(content)
                            coroutineScope.launch {
                                scriptCacheDao.insertScript(ScriptCache(url, content))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ScriptFetcher", "Failed to fetch script from $url: ${e.message}")
                    }
                }
            }
            scriptsContent
        }
    }
}
