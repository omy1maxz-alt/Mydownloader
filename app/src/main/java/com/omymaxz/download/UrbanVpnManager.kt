package com.omymaxz.download

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prototype client for the Urban VPN Chrome extension's observed backend contract.
 *
 * This backend is undocumented and may change. Do not log proxy credentials or tokens.
 * This implementation only provides a local relay for WebView traffic; native download
 * and Media3 clients are not automatically routed through it.
 */
internal object UrbanVpnManager {
    private const val TAG = "UrbanVpn"
    private const val API_BASE = "https://api-pro.urban-vpn.com/rest/v1"
    private const val CLIENT_APP = "URBAN_VPN_BROWSER_EXTENSION"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_HEADER_BYTES = 64 * 1024

    data class Location(
        val countryName: String,
        val countryCode: String,
        val host: String,
        val port: Int,
        val signature: String
    ) {
        override fun toString(): String = "$countryName ($countryCode)"
    }

    data class ConnectionInfo(val countryName: String, val countryCode: String, val exitProxy: String)

    @Volatile private var relay: AuthenticatedHttpProxyRelay? = null
    @Volatile private var activeInfo: ConnectionInfo? = null

    fun status(): ConnectionInfo? = activeInfo

    fun fetchLocations(): List<Location> {
        val root = apiRequest("/entrypoints/countries", null)
        val countries = root.optJSONObject("countries")?.optJSONArray("elements")
            ?: throw IllegalStateException("Urban VPN response did not contain countries.elements")
        val result = LinkedHashMap<String, Location>()
        for (i in 0 until countries.length()) {
            val country = countries.optJSONObject(i) ?: continue
            val code = country.optJSONObject("code")?.optString("iso2").orEmpty()
            val name = country.optString("title", code).ifBlank { code }
            val servers = country.optJSONObject("servers")?.optJSONArray("elements") ?: continue
            for (j in 0 until servers.length()) {
                val server = servers.optJSONObject(j) ?: continue
                val primary = server.optJSONObject("address")?.optJSONObject("primary") ?: continue
                val host = primary.optString("host").ifBlank { primary.optString("ip") }
                val port = primary.optInt("port", -1)
                val signature = server.optString("signature")
                if (host.isBlank() || port !in 1..65535 || signature.isBlank()) continue
                val key = "$code|$host|$port"
                result.putIfAbsent(key, Location(name, code, host, port, signature))
            }
        }
        if (result.isEmpty()) throw IllegalStateException("Urban VPN returned no usable proxy locations")
        return result.values.sortedWith(compareBy<Location> { it.countryName.lowercase() }.thenBy { it.host })
    }

    @Synchronized
    fun connect(location: Location): ConnectionInfo {
        disconnect()
        val tokenResponse = apiRequest(
            "/security/tokens/accs-proxy",
            JSONObject().apply {
                put("type", "accs-proxy")
                put("clientApp", JSONObject().put("name", CLIENT_APP))
                put("signature", location.signature)
            }
        )
        val token = when (tokenResponse) {
            is JSONObject -> tokenResponse.optString("token")
                .ifBlank { tokenResponse.optString("accessToken") }
                .ifBlank { tokenResponse.optString("value") }
            else -> tokenResponse.toString().trim('"')
        }
        if (token.isBlank() || token == "null") {
            throw IllegalStateException("Urban VPN did not return a proxy token")
        }

        val newRelay = AuthenticatedHttpProxyRelay(location.host, location.port, token)
        newRelay.start()
        relay = newRelay
        activeInfo = ConnectionInfo(location.countryName, location.countryCode, "${location.host}:${location.port}")
        Log.i(TAG, "Urban VPN relay started for ${location.countryCode}; credentials omitted")
        return activeInfo!!
    }

    @Synchronized
    fun disconnect() {
        relay?.close()
        relay = null
        activeInfo = null
    }

    fun localProxyAddress(): String? = relay?.let { "127.0.0.1:${it.localPort}" }

    private fun apiRequest(path: String, body: JSONObject?): Any {
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("X-Client-App", CLIENT_APP)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..399) {
                Log.w(TAG, "Urban VPN API returned HTTP $status for $path")
                throw IllegalStateException("Urban VPN request failed (HTTP $status)")
            }
            return if (text.isBlank()) JSONObject() else JSONTokener(text).nextValue()
        } finally {
            connection.disconnect()
        }
    }
}

private class AuthenticatedHttpProxyRelay(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    token: String
) : Closeable {
    private val running = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val server = ServerSocket()
    private val proxyAuthorization = "Basic " + Base64.encodeToString(
        "$token:1".toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP
    )
    val localPort: Int get() = server.localPort

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        running.set(true)
        workers.execute {
            while (running.get()) {
                try {
                    val client = server.accept()
                    workers.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w("UrbanVpn", "Local proxy accept failed", e)
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 0
            val clientInput = BufferedInputStream(client.getInputStream())
            val clientOutput = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(clientInput) ?: return
            if (requestLine.isBlank()) return
            val headers = readHeaders(clientInput)
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 3) {
                writeError(clientOutput, 400, "Bad Request")
                return
            }
            upstream = Socket().apply {
                connect(InetSocketAddress(upstreamHost, upstreamPort), 12_000)
                soTimeout = 0
            }
            val upstreamInput = BufferedInputStream(upstream.getInputStream())
            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
            val isConnect = parts[0].equals("CONNECT", ignoreCase = true)
            val outboundHeaders = headers.filterNot {
                it.substringBefore(':').trim().equals("Proxy-Authorization", true) ||
                    it.substringBefore(':').trim().equals("Proxy-Connection", true)
            }
            upstreamOutput.write((requestLine + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            outboundHeaders.forEach { upstreamOutput.write((it + "\r\n").toByteArray(StandardCharsets.ISO_8859_1)) }
            upstreamOutput.write(("Proxy-Authorization: $proxyAuthorization\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            if (isConnect) {
                upstreamOutput.write("Proxy-Connection: Keep-Alive\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                upstreamOutput.flush()
                val responseHead = readHeaderBlock(upstreamInput)
                clientOutput.write(responseHead)
                clientOutput.flush()
                if (!responseHead.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200") &&
                    !responseHead.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.0 200")) return
                tunnel(client, upstream)
            } else {
                upstreamOutput.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                upstreamOutput.flush()
                // The browser may send a request body. Pump it concurrently with the response.
                val done = CountDownLatch(2)
                val target = upstream
                workers.execute {
                    try { copyUntilEof(clientInput, target.getOutputStream()) } catch (_: Exception) {}
                    finally { try { target.shutdownOutput() } catch (_: Exception) {}; done.countDown() }
                }
                workers.execute {
                    try { copyUntilEof(upstreamInput, clientOutput) } catch (_: Exception) {}
                    finally { try { client.shutdownOutput() } catch (_: Exception) {}; done.countDown() }
                }
                done.await(READ_TIMEOUT_MS.toLong() * 4, TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.d("UrbanVpn", "Proxy request failed: ${e.javaClass.simpleName}")
            try { writeError(BufferedOutputStream(client.getOutputStream()), 502, "Bad Gateway") } catch (_: Exception) {}
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
        }
    }

    private fun tunnel(client: Socket, upstream: Socket) {
        val latch = CountDownLatch(2)
        workers.execute {
            try { copyUntilEof(client.getInputStream(), upstream.getOutputStream()) } catch (_: Exception) {}
            finally { try { upstream.shutdownOutput() } catch (_: Exception) {}; latch.countDown() }
        }
        workers.execute {
            try { copyUntilEof(upstream.getInputStream(), client.getOutputStream()) } catch (_: Exception) {}
            finally { try { client.shutdownOutput() } catch (_: Exception) {}; latch.countDown() }
        }
        latch.await()
    }

    private fun readHeaders(input: InputStream): List<String> {
        val result = ArrayList<String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw IllegalStateException("Unexpected end of proxy headers")
            total += line.length + 2
            if (total > MAX_HEADER_BYTES) throw IllegalStateException("Proxy headers too large")
            if (line.isEmpty()) break
            result.add(line)
        }
        return result
    }

    private fun readHeaderBlock(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var state = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) throw IllegalStateException("Upstream proxy closed during CONNECT")
            output.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> return output.toByteArray()
                b == 13 -> 1
                else -> 0
            }
        }
        throw IllegalStateException("Upstream proxy response headers too large")
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == 10) {
                val value = bytes.toByteArray()
                val length = if (value.isNotEmpty() && value.last() == 13.toByte()) value.size - 1 else value.size
                return String(value, 0, length, StandardCharsets.ISO_8859_1)
            }
            bytes.write(b)
        }
        throw IllegalStateException("Proxy header line too large")
    }

    private fun copyUntilEof(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun writeError(output: OutputStream, status: Int, message: String) {
        val body = "Urban VPN relay: $status $message\n".toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $status $message\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    override fun close() {
        running.set(false)
        try { server.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }
}
