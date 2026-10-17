package com.omymaxz.download

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class NewPipeDownloader : Downloader() {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = httpMethod
            connection.readTimeout = 30000
            connection.connectTimeout = 30000
            connection.instanceFollowRedirects = true

            // Headers
            if (headers != null) {
                for ((key, values) in headers) {
                    for (value in values) {
                        connection.setRequestProperty(key, value)
                    }
                }
            }
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", USER_AGENT)
            }

            // Body
            if (dataToSend != null && (httpMethod == "POST" || httpMethod == "PUT")) {
                connection.doOutput = true
                connection.outputStream.use { os ->
                    os.write(dataToSend)
                }
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            // Handle NewPipe reCAPTCHA check if they block us
            if (responseCode == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

            // Extract headers
            val responseHeaders = mutableMapOf<String, List<String>>()
            connection.headerFields.forEach { (key, value) ->
                if (key != null) {
                    responseHeaders[key] = value
                }
            }

            return Response(
                responseCode,
                responseMessage,
                responseHeaders,
                responseBody,
                connection.url.toString() // latest url after redirects
            )
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
    }
}
