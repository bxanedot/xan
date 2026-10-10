package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class IosMusicPlaybackTest {
    @Test
    fun allowsOnlyHttpsArtworkFromKnownImageHosts() {
        assertEquals("https://lh3.googleusercontent.com/cover", safeIosArtworkUrl("https://lh3.googleusercontent.com/cover"))
        assertEquals("https://i.ytimg.com/vi/test/hqdefault.jpg", safeIosArtworkUrl("https://i.ytimg.com/vi/test/hqdefault.jpg"))
        assertEquals(null, safeIosArtworkUrl("http://i.ytimg.com/cover"))
        assertEquals(null, safeIosArtworkUrl("https://attacker.example/cover"))
        assertEquals(null, safeIosArtworkUrl("not a URL"))
    }

    @Test
    fun choosesHighestBitrateDirectAacStream() {
        val stream = parseIosPlayerResponse(
            """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{"adaptiveFormats":[
                {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"url":"https://rr1.googlevideo.com/webm"},
                {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://rr2.googlevideo.com/aac-low"},
                {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000,"url":"https://rr3.googlevideo.com/aac-high"}
              ]}
            }
            """.trimIndent(),
        )

        assertEquals("https://rr3.googlevideo.com/aac-high", stream.url)
        assertEquals("audio/mp4", stream.mimeType)
        assertEquals(256000, stream.bitrate)
    }

    @Test
    fun rejectsCipherOnlyAndNonGoogleVideoStreams() {
        val noDirectStream = """
            {"streamingData":{"adaptiveFormats":[
              {"mimeType":"audio/mp4","bitrate":128000,"signatureCipher":"s=opaque&url=https%3A%2F%2Frr1.googlevideo.com%2Faac"},
              {"mimeType":"audio/mp4","bitrate":256000,"url":"https://attacker.example/audio"}
            ]}}
        """.trimIndent()

        val error = assertFailsWith<IllegalStateException> { parseIosPlayerResponse(noDirectStream) }
        assertEquals(true, error.message?.contains("no direct AAC stream") == true)
    }

    @Test
    fun reportsProviderPlayabilityReason() {
        val error = assertFailsWith<IllegalStateException> {
            parseIosPlayerResponse("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Unavailable in this region."}}""")
        }
        assertEquals("Unavailable in this region.", error.message)
    }

    @Test
    fun createsSafeStableOfflineAudioFileNames() {
        val filename = iosDownloadFileName(
            IosMusicSearchTrack(
                videoId = "video123",
                title = "Song / with:*unsafe? chars",
                artist = "Artist Name",
                album = null,
                durationSeconds = 90,
                artworkUrl = null,
            ),
        )

        assertEquals("Artist Name - Song _ with__unsafe_ chars-video123.m4a", filename)
        assertFalse(filename.contains('/'))
        assertFalse(filename.contains('\\'))
    }

    @Test
    fun downloadQueueSkipsSavedAndDuplicateTracksAndEnforcesItsLimit() {
        val first = searchTrack("video123")
        val second = searchTrack("video456")
        val third = searchTrack("video789")

        val pending = selectIosPendingDownloads(
            candidates = listOf(first, second, first, third),
            downloadedIds = setOf(first.videoId),
            reservedIds = setOf("video456"),
            maxQueueSize = 4,
        )
        assertEquals(listOf("video789"), pending.map { it.videoId })
        assertFailsWith<IllegalArgumentException> {
            selectIosPendingDownloads(
                candidates = listOf(second, third),
                downloadedIds = emptySet(),
                reservedIds = setOf("video123"),
                maxQueueSize = 2,
            )
        }
    }

    private fun searchTrack(videoId: String) = IosMusicSearchTrack(
        videoId = videoId,
        title = "Track $videoId",
        artist = "Test Artist",
        album = null,
        durationSeconds = 120,
        artworkUrl = null,
    )
}
