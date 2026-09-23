package com.omymaxz.download

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource

class CustomRetryPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception

        if (exception is InvalidResponseCodeException) {
            val responseCode = exception.responseCode
            val url = exception.dataSpec.uri.toString()
            val host = exception.dataSpec.uri.host
            val path = exception.dataSpec.uri.path ?: ""
            val isManifest = path.endsWith(".m3u8") || path.endsWith(".mpd")
            val kind = if (isManifest) "manifest" else "media_segment"

            // Extract headers for diagnostic logging
            val headers = exception.dataSpec.httpRequestHeaders
            val userAgentPresent = headers.containsKey("User-Agent") || headers.containsKey("user-agent")
            val refererPresent = headers.containsKey("Referer") || headers.containsKey("referer")
            val originPresent = headers.containsKey("Origin") || headers.containsKey("origin")
            val cookiePresent = headers.containsKey("Cookie") || headers.containsKey("cookie")

            val redactedPath = path.substringBeforeLast("/") + "/REDACTED" + (if (isManifest) ".m3u8" else ".ts")

            android.util.Log.e("HLS_HTTP", """
                HLS_HTTP
                host=$host
                path=$redactedPath
                kind=$kind
                userAgentPresent=$userAgentPresent
                refererPresent=$refererPresent
                originPresent=$originPresent
                cookiePresent=$cookiePresent
                cacheEnabled=true
                retryCount=${loadErrorInfo.errorCount}
                responseCode=$responseCode
            """.trimIndent())

            if (responseCode == 500 || responseCode == 502 || responseCode == 503 || responseCode == 504) {
                if (loadErrorInfo.errorCount <= 3) {
                    val delay = (loadErrorInfo.errorCount * 2000).toLong()
                    return delay
                }
            } else if (responseCode == 401 || responseCode == 403) {
                return C.TIME_UNSET
            }
        }

        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
