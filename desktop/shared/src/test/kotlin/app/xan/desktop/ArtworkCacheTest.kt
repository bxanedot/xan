package app.xan.desktop

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkCacheTest {
    @Test
    fun extractsAndCachesEmbeddedArtworkFromLocalAudio() {
        val directory = Files.createTempDirectory("xan-local-artwork-")
        val audioPath = directory.resolve("tagged.wav")
        val imagePath = directory.resolve("cover.png")
        val cache = directory.resolve("cache")
        val image = BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB).apply { setRGB(2, 3, 0x00ff00) }
        try {
            ImageIO.write(image, "png", imagePath.toFile())
            writeSilentWav(audioPath)
            val audio = org.jaudiotagger.audio.AudioFileIO.read(audioPath.toFile())
            val tag: org.jaudiotagger.tag.Tag = audio.tagOrCreateAndSetDefault
            val artwork: org.jaudiotagger.tag.images.Artwork = org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(imagePath.toFile())
            tag.setField(artwork)
            audio.commit()

            val extracted = ArtworkCache.loadLocalAudioArtwork(audioPath, cache, 1024 * 1024)
            assertNotNull(extracted)
            assertEquals(7, extracted!!.width)
            assertEquals(5, extracted.height)
            assertEquals(0x00ff00, extracted.getRGB(2, 3) and 0xFFFFFF)
            assertNotNull(ArtworkCache.loadLocalAudioArtwork(audioPath, cache, 1024 * 1024))
            assertTrue(ArtworkCache.sizeBytes(cache) > 0)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun downloadsArtworkOnceThenServesThePersistentCachedImage() {
        val directory = Files.createTempDirectory("xan-artwork-cache-")
        val imageBytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/cover") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, imageBytes.size.toLong())
                exchange.responseBody.use { it.write(imageBytes) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/cover"
            assertNotNull(ArtworkCache.load(url, directory, 1024 * 1024))
            assertNotNull(ArtworkCache.load(url, directory, 1024 * 1024))
            assertEquals(1, requests.get())
            assertTrue(ArtworkCache.sizeBytes(directory) > 0)
            assertEquals(1, ArtworkCache.clear(directory))
            assertEquals(0L, ArtworkCache.sizeBytes(directory))
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun artworkLimitEvictsLeastRecentlyUsedFiles() {
        val directory = Files.createTempDirectory("xan-artwork-limit-")
        try {
            val old = directory.resolve("old.img")
            val recent = directory.resolve("recent.img")
            Files.write(old, ByteArray(10))
            Files.write(recent, ByteArray(10))
            Files.setLastModifiedTime(old, FileTime.fromMillis(10))
            Files.setLastModifiedTime(recent, FileTime.fromMillis(20))

            ArtworkCache.applyLimit(directory, 10)

            assertTrue(Files.notExists(old))
            assertTrue(Files.exists(recent))
            assertEquals(10L, ArtworkCache.sizeBytes(directory))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun writeSilentWav(path: java.nio.file.Path) {
        val sampleRate = 8_000
        val dataLength = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)); header.putInt(36 + dataLength)
        header.put("WAVE".toByteArray(Charsets.US_ASCII)); header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16); header.putShort(1); header.putShort(1); header.putInt(sampleRate)
        header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)); header.putInt(dataLength)
        Files.newOutputStream(path).use { output -> output.write(header.array()); output.write(ByteArray(dataLength)) }
    }
}
