import sys

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    content = f.read()

old_factory = """    fun getDataSourceFactory(context: Context): DataSource.Factory {
        // Instantiate a NEW factory per request rather than mutating the global singleton
        // to prevent race conditions or missing headers on background fetches.
        // Also use a ResolvingDataSource to explicitly log the final resolved DataSpec URI.
        val upstreamFactory = DataSource.Factory {
            val upstream = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)

            currentUserAgent?.let { upstream.setUserAgent(it) }"""

new_factory = """    fun getDataSourceFactory(context: Context): DataSource.Factory {
        // Instantiate a NEW factory per request rather than mutating the global singleton
        // to prevent race conditions or missing headers on background fetches.
        // Wrap the HttpDataSource in a DefaultDataSource so it can safely handle data: URIs and file: URIs
        val upstreamFactory = DataSource.Factory {
            val httpDataSource = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)

            currentUserAgent?.let { httpDataSource.setUserAgent(it) }
            val props = mutableMapOf<String, String>()
            currentCookie?.let { props["Cookie"] = it }
            currentReferer?.let {
                props["Referer"] = it
                try {
                    val refererUri = java.net.URL(it)
                    val origin = "${refererUri.protocol}://${refererUri.host}"
                    props["Origin"] = origin
                } catch (e: Exception) {}
            }
            props["Accept"] = "*/*"
            httpDataSource.setDefaultRequestProperties(props)

            val baseDataSource = DefaultDataSource.Factory(context, httpDataSource).createDataSource()
            baseDataSource
        }

        return ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val redactedUri = dataSpec.uri.toString().replace(Regex("([?&])(auth-token|sig|mac|token)=([^&]+)"), "$1$2=REDACTED")
            Log.d("HLS_HTTP", "Requesting: $redactedUri")
            dataSpec
        }
    }"""

# Actually, the old one continues with setting properties. Let's just find the whole method.
import re
match = re.search(r"    fun getDataSourceFactory.*?    \}", content, re.DOTALL)
if match:
    content = content.replace(match.group(0), new_factory)

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.write(content)

print("Done")
