package app.xan.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramSocket
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Short-lived, tokenized HTTP endpoint that lets a Cast receiver read this machine's selected audio. */
internal class CastMediaRelay : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    @Synchronized
    fun publish(source: URI, receiverAddress: String): URI {
        close()
        val host = InetAddress.getByName(receiverAddress)
        val localAddress = DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(host, 8009))
            socket.localAddress.hostAddress
        }
        val nextToken = UUID.randomUUID().toString().replace("-", "")
        val http = HttpServer.create(InetSocketAddress(0), 0)
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "xan-cast-relay").apply { isDaemon = true } }
        http.executor = worker
        http.createContext("/$nextToken") { exchange -> serve(exchange, source, nextToken) }
        http.start()
        server = http
        executor = worker
        return URI("http", null, localAddress, http.address.port, "/$nextToken", null, null)
    }

    private fun serve(exchange: HttpExchange, source: URI, expectedToken: String) {
        exchange.use {
            if (exchange.requestURI.path != "/$expectedToken" || exchange.requestMethod !in setOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            runCatching {
                if (source.scheme.equals("file", true)) serveFile(exchange, Path.of(source)) else serveRemote(exchange, source)
            }.onFailure {
                runCatching { exchange.sendResponseHeaders(502, -1) }
            }
        }
    }

    private fun serveFile(exchange: HttpExchange, file: Path) {
        require(Files.isRegularFile(file))
        val size = Files.size(file)
        val range = parseByteRange(exchange.requestHeaders.getFirst("Range"), size)
        val start = range?.first ?: 0L
        val end = range?.last ?: (size - 1).coerceAtLeast(0)
        val count = (end - start + 1).coerceAtLeast(0)
        exchange.responseHeaders.add("Content-Type", URLConnection.guessContentTypeFromName(file.fileName.toString()) ?: "audio/mp4")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Length", count.toString())
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        exchange.sendResponseHeaders(if (range != null) 206 else 200, if (exchange.requestMethod == "HEAD") -1 else count)
        if (exchange.requestMethod == "HEAD" || count == 0L) return
        Files.newInputStream(file).use { input ->
            input.skipNBytes(start)
            exchange.responseBody.use { output -> input.copyExactlyTo(output, count) }
        }
    }

    private fun serveRemote(exchange: HttpExchange, source: URI) {
        val connection = source.toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 45_000
            exchange.requestHeaders.getFirst("Range")?.let { setRequestProperty("Range", it) }
            setRequestProperty("User-Agent", "Mozilla/5.0 XAN-Desktop-Cast/1.0")
        }
        val response = (connection as? java.net.HttpURLConnection)?.run {
            requestMethod = exchange.requestMethod
            responseCode
        } ?: error("Only HTTP audio streams can be relayed to Cast.")
        val http = connection as java.net.HttpURLConnection
        listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").forEach { key ->
            http.getHeaderField(key)?.let { exchange.responseHeaders.add(key, it) }
        }
        exchange.sendResponseHeaders(response, if (exchange.requestMethod == "HEAD") -1 else http.getHeaderFieldLong("Content-Length", 0L))
        if (exchange.requestMethod != "HEAD") http.inputStream.use { input -> exchange.responseBody.use(input::copyTo) }
        http.disconnect()
    }

    @Synchronized
    override fun close() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }
}

internal data class ByteRange(val first: Long, val last: Long)

internal fun parseByteRange(value: String?, size: Long): ByteRange? {
    if (value == null || size <= 0) return null
    val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(value.trim()) ?: return null
    val firstText = match.groupValues[1]
    val lastText = match.groupValues[2]
    val first: Long
    val last: Long
    if (firstText.isEmpty()) {
        val suffix = lastText.toLongOrNull()?.takeIf { it > 0 } ?: return null
        first = (size - suffix).coerceAtLeast(0)
        last = size - 1
    } else {
        first = firstText.toLongOrNull() ?: return null
        if (first >= size) return null
        last = lastText.toLongOrNull()?.coerceIn(first, size - 1) ?: size - 1
    }
    return ByteRange(first, last)
}

private fun java.io.InputStream.copyExactlyTo(output: java.io.OutputStream, count: Long) {
    var remaining = count
    val buffer = ByteArray(32 * 1024)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
}
