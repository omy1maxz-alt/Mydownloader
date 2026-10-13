package com.omymaxz.download

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Adds a short, bounded retry window for transient HLS network errors while
 * preserving Media3 defaults for unrelated failures.
 * HTTP 401/403 responses are never retried because repeating them cannot fix
 * missing or rejected credentials.
 */
class CustomRetryPolicy : DefaultLoadErrorHandlingPolicy() {

    companion object {
        private const val TAG = "HLS_HTTP"
        private const val MAX_TRANSIENT_IO_RETRIES = 3
        private const val TRANSIENT_IO_BASE_DELAY_MS = 1_000L
        private const val MAX_TRANSIENT_IO_DELAY_MS = 4_000L
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception

        if (exception is InvalidResponseCodeException) {
            logHttpResponseError(loadErrorInfo, exception)
            when (exception.responseCode) {
                401, 403 -> return C.TIME_UNSET
                500, 502, 503, 504 -> {
                    if (loadErrorInfo.errorCount <= 3) {
                        return loadErrorInfo.errorCount * 2_000L
                    }
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        val transientIoFailure = findCause<SocketTimeoutException>(exception) != null ||
            findCause<UnknownHostException>(exception) != null ||
            findCause<SocketException>(exception) != null

        if (transientIoFailure) {
            val retryCount = loadErrorInfo.errorCount
            Log.w(
                TAG,
                "HLS_IO_RETRY error=${exception.javaClass.simpleName} " +
                    "retryCount=$retryCount/$MAX_TRANSIENT_IO_RETRIES " +
                    "mediaDataType=${loadErrorInfo.mediaLoadData.dataType} " +
                    "mediaStartTimeMs=${loadErrorInfo.mediaLoadData.mediaStartTimeMs} " +
                    "message=${exception.message?.take(180)}"
            )

            if (retryCount in 1..MAX_TRANSIENT_IO_RETRIES) {
                val shift = (retryCount - 1).coerceAtLeast(0)
                return (TRANSIENT_IO_BASE_DELAY_MS shl shift)
                    .coerceAtMost(MAX_TRANSIENT_IO_DELAY_MS)
            }

            Log.e(TAG, "HLS_IO_RETRY_EXHAUSTED retryCount=$retryCount")
            return C.TIME_UNSET
        }

        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private fun logHttpResponseError(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        exception: InvalidResponseCodeException
    ) {
        val uri = exception.dataSpec.uri
        val path = uri.path.orEmpty()
        val isManifest = path.endsWith(".m3u8", ignoreCase = true) ||
            path.endsWith(".mpd", ignoreCase = true)
        val suffix = if (isManifest) ".m3u8" else ".ts"
        val redactedPath = path.substringBeforeLast("/", "") + "/REDACTED" + suffix
        val headers = exception.dataSpec.httpRequestHeaders

        Log.e(
            TAG,
            "HLS_HTTP_RESPONSE host=${uri.host ?: "unknown"} path=$redactedPath " +
                "kind=${if (isManifest) "manifest" else "media_segment"} " +
                "userAgentPresent=${headers.hasHeader("User-Agent")} " +
                "refererPresent=${headers.hasHeader("Referer")} " +
                "originPresent=${headers.hasHeader("Origin")} " +
                "cookiePresent=${headers.hasHeader("Cookie")} " +
                "retryCount=${loadErrorInfo.errorCount} responseCode=${exception.responseCode}"
        )
    }

    private fun Map<String, String>.hasHeader(name: String): Boolean =
        keys.any { it.equals(name, ignoreCase = true) }

    private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
