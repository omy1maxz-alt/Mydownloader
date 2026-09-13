import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import java.net.HttpURLConnection
import java.net.URL

class TestDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        request.headers()?.forEach { (key, values) ->
            values.forEach { connection.setRequestProperty(key, it) }
        }
        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage
        val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
        val responseHeaders = mutableMapOf<String, List<String>>()
        connection.headerFields.forEach { (key, value) -> if (key != null) responseHeaders[key] = value }
        return Response(responseCode, responseMessage, responseHeaders, responseBody, connection.url.toString())
    }
}

fun main() {
    NewPipe.init(TestDownloader(), Localization.DEFAULT)
    val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
    extractor.fetchPage()
    println("Title: ${extractor.name}")
    println("DASH: ${extractor.dashMpdUrl}")
    println("HLS: ${extractor.hlsUrl}")
    println("Video Streams: ${extractor.videoStreams.size}")
    extractor.videoStreams.forEach {
        println("  Resolution: ${it.resolution}, URL: ${it.content.take(50)}...")
    }
}
