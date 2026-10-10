package app.xan.desktop

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors

class VerifiedToolDownloadTest {
    @Test
    fun streamsAndVerifiesTheDownloadedAssetBeforePublishingIt() {
        val bytes = "verified-tool-binary".toByteArray()
        val checksum = bytes.sha256()
        withServer(checksum, bytes) { base ->
            val directory = Files.createTempDirectory("xan-tool-download-")
            val destination = directory.resolve("tool.bin")
            try {
                VerifiedToolDownload.download("$base/asset", "$base/sums", "tool.bin", destination)
                assertArrayEquals(bytes, Files.readAllBytes(destination))
                assertFalse(Files.exists(directory.resolve("tool.bin.part")))
            } finally {
                Files.deleteIfExists(destination)
                Files.deleteIfExists(directory)
            }
        }
    }

    @Test
    fun checksumMismatchLeavesNoPublishedOrPartialFile() {
        val bytes = "different asset".toByteArray()
        withServer("0".repeat(64), bytes) { base ->
            val directory = Files.createTempDirectory("xan-tool-bad-checksum-")
            val destination = directory.resolve("tool.bin")
            try {
                assertThrows(IllegalStateException::class.java) {
                    VerifiedToolDownload.download("$base/asset", "$base/sums", "tool.bin", destination)
                }
                assertFalse(Files.exists(destination))
                assertFalse(Files.exists(directory.resolve("tool.bin.part")))
            } finally {
                Files.deleteIfExists(destination)
                Files.deleteIfExists(directory)
            }
        }
    }

    @Test
    fun readTimeoutBoundsAStalledAssetResponse() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val checksum = "a".repeat(64)
        server.createContext("/sums") { exchange ->
            val response = "$checksum  tool.bin".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/asset") { exchange ->
            Thread.sleep(500)
            runCatching {
                exchange.sendResponseHeaders(200, 4)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }
        }
        server.start()
        val directory = Files.createTempDirectory("xan-tool-timeout-")
        val destination = directory.resolve("tool.bin")
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertThrows(IOException::class.java) {
                VerifiedToolDownload.download(
                    "$base/asset", "$base/sums", "tool.bin", destination,
                    connectTimeoutMillis = 1_000,
                    readTimeoutMillis = 100,
                )
            }
            assertFalse(Files.exists(destination))
            assertFalse(Files.exists(directory.resolve("tool.bin.part")))
        } finally {
            server.stop(0)
            executor.shutdownNow()
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }

    private fun withServer(checksum: String, bytes: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sums") { exchange ->
            val response = "$checksum  tool.bin".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }
}
