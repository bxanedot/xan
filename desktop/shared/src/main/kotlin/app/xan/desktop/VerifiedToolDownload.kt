package app.xan.desktop

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Downloads executable support tools without unbounded network waits or buffering whole binaries in memory. */
internal object VerifiedToolDownload {
    private const val MAX_CHECKSUM_BYTES = 1024L * 1024L
    private const val DEFAULT_MAX_ASSET_BYTES = 150L * 1024L * 1024L

    fun download(
        url: String,
        checksumUrl: String,
        assetName: String,
        destination: Path,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 45_000,
        maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    ) {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && maxAssetBytes > 0)
        val temporary = destination.resolveSibling(destination.fileName.toString() + ".part")
        Files.createDirectories(destination.parent)
        try {
            val checksumText = withConnection(checksumUrl, connectTimeoutMillis, readTimeoutMillis) { connection ->
                connection.inputStream.use { readBounded(it, MAX_CHECKSUM_BYTES).toString(Charsets.UTF_8) }
            }
            val expected = ChecksumParser.sha256(checksumText, assetName)
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            withConnection(url, connectTimeoutMillis, readTimeoutMillis) { connection ->
                val contentLength = connection.contentLengthLong
                require(contentLength < 0 || contentLength <= maxAssetBytes) { "$assetName exceeds the download size limit." }
                connection.inputStream.use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= maxAssetBytes) { "$assetName exceeds the download size limit." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(totalBytes > 0) { "$assetName download was empty." }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, ignoreCase = true)) { "Checksum verification failed for $assetName." }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun open(url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): URLConnection {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("User-Agent", "XAN-Desktop")
        }
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.disconnect()
                throw IOException("Download request failed with HTTP $status.")
            }
        }
        return connection
    }

    private inline fun <T> withConnection(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        action: (URLConnection) -> T,
    ): T {
        val connection = open(url, connectTimeoutMillis, readTimeoutMillis)
        return try {
            action(connection)
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Checksum response exceeds the size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
