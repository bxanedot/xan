package app.xan.desktop

import java.awt.image.BufferedImage
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Persistent, size-bounded artwork cache. URL query strings are hashed and never used as filenames. */
internal object ArtworkCache {
    private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
    private const val READ_BUFFER_BYTES = 32 * 1024

    @Synchronized
    fun load(url: String, directory: Path, limitBytes: Long): BufferedImage? {
        require(limitBytes == -1L || limitBytes > 0)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        runCatching { Files.createDirectories(directory) }.getOrElse { return null }
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val destination = directory.resolve("$key.img")
        if (Files.isRegularFile(destination)) {
            val image = runCatching { ImageIO.read(destination.toFile()) }.getOrNull()
            if (image != null) {
                runCatching { Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
                return image
            }
            runCatching { Files.deleteIfExists(destination) }
        }

        val temporary = runCatching { Files.createTempFile(directory, ".artwork-", ".part") }.getOrNull() ?: return null
        return try {
            val connection = uri.toURL().openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "XAN Desktop artwork cache")
            try {
                connection.connect()
                if (connection.responseCode !in 200..299) return null
                val declaredSize = connection.contentLengthLong
                if (declaredSize > MAX_IMAGE_BYTES) return null
                val copied = connection.inputStream.use { input -> copyLimited(input, temporary, MAX_IMAGE_BYTES) }
                if (copied < 0) return null
                val image = ImageIO.read(temporary.toFile()) ?: return null
                if (limitBytes < 0 || copied <= limitBytes) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                    evictToLimit(directory, limitBytes, destination)
                }
                image
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    @Synchronized
    fun loadLocalAudioArtwork(audioPath: Path, directory: Path, limitBytes: Long): BufferedImage? {
        if (!Files.isRegularFile(audioPath)) return null
        require(limitBytes == -1L || limitBytes > 0)
        val canonical = runCatching { audioPath.toRealPath() }.getOrNull() ?: return null
        val modified = runCatching { Files.getLastModifiedTime(canonical).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(canonical) }.getOrNull() ?: return null
        val cacheKey = "${canonical}\u0000${modified}\u0000${size}"
        val key = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        runCatching { Files.createDirectories(directory) }.getOrElse { return null }
        val destination = directory.resolve("$key.img")
        if (Files.isRegularFile(destination)) {
            ImageIO.read(destination.toFile())?.let {
                runCatching { Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
                return it
            }
            runCatching { Files.deleteIfExists(destination) }
        }
        return runCatching {
            val audio = org.jaudiotagger.audio.AudioFileIO.read(canonical.toFile())
            val bytes = audio.tag?.firstArtwork?.binaryData ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val image = ImageIO.read(bytes.inputStream()) ?: return null
            val temporary = Files.createTempFile(directory, ".embedded-art-", ".part")
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                evictToLimit(directory, limitBytes, destination)
            } finally {
                Files.deleteIfExists(temporary)
            }
            image
        }.getOrNull()
    }

    fun sizeBytes(directory: Path): Long {
        if (!Files.isDirectory(directory)) return 0
        return Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".img") }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }

    fun clear(directory: Path): Int {
        if (!Files.isDirectory(directory)) return 0
        return Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".img") }
                .toList().count { Files.deleteIfExists(it) }
        }
    }

    fun applyLimit(directory: Path, limitBytes: Long) {
        require(limitBytes == -1L || limitBytes > 0)
        evictToLimit(directory, limitBytes, keep = null)
    }

    private fun evictToLimit(directory: Path, limitBytes: Long, keep: Path?) {
        if (limitBytes < 0 || !Files.isDirectory(directory)) return
        val entries = Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".img") }
                .map { path -> path to runCatching { Files.size(path) }.getOrDefault(0L) }
                .sorted(compareBy { (path, _) -> runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) })
                .toList()
        }
        var used = entries.sumOf { it.second }
        for ((path, bytes) in entries) {
            if (used <= limitBytes) break
            if (path == keep) continue
            if (Files.deleteIfExists(path)) used -= bytes
        }
        if (used > limitBytes && keep != null) Files.deleteIfExists(keep)
    }

    private fun copyLimited(input: InputStream, destination: Path, maxBytes: Long): Long {
        var total = 0L
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return -1
                output.write(buffer, 0, count)
            }
        }
        return total
    }
}
