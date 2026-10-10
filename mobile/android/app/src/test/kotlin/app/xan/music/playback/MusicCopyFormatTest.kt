package app.xan.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MusicCopyFormatTest {
    @Test
    fun `same title with different song IDs creates different files`() {
        assertNotEquals(musicCopyFileName("Song", "id-a", MusicCopyFormat.MP3),
            musicCopyFileName("Song", "id-b", MusicCopyFormat.MP3))
        assertEquals(musicCopyFileName("Song", "id-a", MusicCopyFormat.MP3),
            musicCopyFileName("Song", "id-a", MusicCopyFormat.MP3))
    }

    @Test
    fun `unsafe title and ID cannot escape destination folder`() {
        val name = musicCopyFileName("../../a\\b\n:*?\"<>|", "../../id", MusicCopyFormat.FLAC)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('\n'))
        assertFalse(name.startsWith('.'))
        assertTrue(name.endsWith(".flac"))
    }

    @Test
    fun `empty and very long titles produce usable names`() {
        assertTrue(musicCopyFileName("...", "id", MusicCopyFormat.WAV).startsWith("Track-"))
        assertTrue(musicCopyFileName("a".repeat(500), "id", MusicCopyFormat.WAV).length < 110)
        assertTrue(musicCopyFileName("音楽", "id", MusicCopyFormat.WAV).startsWith("音楽-"))
        assertTrue(musicCopyFileName("音".repeat(300), "id", MusicCopyFormat.WAV).toByteArray().size < 255)
    }

    @Test
    fun `metadata containing command syntax stays one argument`() {
        val title = "title\" -i another-file -y"
        val arguments = MusicCopyFormat.MP3.conversionArguments("input with spaces", "out.mp3", title, "artist")
        assertEquals(1, arguments.count { it == "-i" })
        assertEquals("input with spaces", arguments[arguments.indexOf("-i") + 1])
        assertTrue(arguments.contains("title=$title"))
        assertEquals("out.mp3", arguments.last())
        assertFalse(arguments.contains("copy"))
    }

    @Test
    fun `all targets encode audio with matching extensions and MIME types`() {
        val encoders = mapOf(MusicCopyFormat.MP3 to "libmp3lame", MusicCopyFormat.AAC to "aac",
            MusicCopyFormat.M4A to "aac", MusicCopyFormat.FLAC to "flac", MusicCopyFormat.WAV to "pcm_s16le",
            MusicCopyFormat.OPUS to "libopus", MusicCopyFormat.OGG to "libvorbis")
        for ((format, encoder) in encoders) {
            val arguments = format.conversionArguments("input", "out.${format.extension}", "title", "artist")
            assertEquals(encoder, arguments[arguments.indexOf("-c:a") + 1])
            assertTrue(arguments.contains("-vn"))
            assertTrue(format.mimeType.startsWith("audio/"))
        }
    }

    @Test
    fun `unknown stored formats cannot become arbitrary codec arguments`() {
        assertEquals(listOf(MusicCopyFormat.MP3, MusicCopyFormat.FLAC),
            MusicCopyFormat.selected(setOf("MP3", "FLAC", "-i malicious")))
        assertTrue(MusicCopyFormat.selected(emptySet()).isEmpty())
    }

    @Test
    fun `recovery checksum identifies complete bytes instead of just a matching length`() {
        val first = "completed copied audio"
        val checksum = musicCopyChecksum(ByteArrayInputStream(first.toByteArray()))
        assertEquals(musicCopyIdentity(first), checksum)
        assertNotEquals(checksum, musicCopyChecksum(ByteArrayInputStream("different copied audio".toByteArray())))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun `checking copied bytes respects cancellation`() {
        musicCopyChecksum(ByteArrayInputStream(ByteArray(1024))) { throw java.util.concurrent.CancellationException() }
    }
}
