package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class CastMediaRelayTest {
    @Test
    fun byteRangesHandleFullOpenAndSuffixRanges() {
        assertEquals(ByteRange(2, 5), parseByteRange("bytes=2-5", 10))
        assertEquals(ByteRange(7, 9), parseByteRange("bytes=7-", 10))
        assertEquals(ByteRange(7, 9), parseByteRange("bytes=-3", 10))
        assertNull(parseByteRange("items=2-5", 10))
        assertNull(parseByteRange("bytes=10-", 10))
    }

    @Test
    fun relayServesLocalAudioAndChromecastRangeRequests() {
        val file = Files.createTempFile("xan-cast", ".m4a")
        val relay = CastMediaRelay()
        try {
            Files.write(file, "abcdefghij".toByteArray())
            val url = relay.publish(file.toUri(), "127.0.0.1")
            val client = HttpClient.newHttpClient()
            val full = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, full.statusCode())
            assertEquals("abcdefghij", full.body())

            val partial = client.send(
                HttpRequest.newBuilder(url).header("Range", "bytes=3-6").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(206, partial.statusCode())
            assertEquals("defg", partial.body())
            assertEquals("bytes 3-6/10", partial.headers().firstValue("Content-Range").orElse(null))

            val head = client.send(HttpRequest.newBuilder(URI(url.toString())).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, head.statusCode())
            assertEquals("10", head.headers().firstValue("Content-Length").orElse(null))
        } finally {
            relay.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun relayForwardsRangeRequestsToRemoteAudioSource() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "abcdefghij".toByteArray()
        upstream.createContext("/audio") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val bytes = if (range == "bytes=2-5") body.copyOfRange(2, 6) else body
            exchange.responseHeaders.add("Content-Type", "audio/mp4")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range == "bytes=2-5") exchange.responseHeaders.add("Content-Range", "bytes 2-5/10")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val relay = CastMediaRelay()
        try {
            upstream.start()
            val source = URI("http://127.0.0.1:${upstream.address.port}/audio")
            val url = relay.publish(source, "127.0.0.1")
            val partial = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(url).header("Range", "bytes=2-5").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(206, partial.statusCode())
            assertEquals("cdef", partial.body())
            assertEquals("bytes 2-5/10", partial.headers().firstValue("Content-Range").orElse(null))
        } finally {
            relay.close()
            upstream.stop(0)
        }
    }
}
