package app.xan.music.video

import app.xan.music.models.MediaMetadata
import com.music.innertube.ExternalVideoFormat
import com.music.innertube.ExternalVideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalVideoFormatTest {
    @Test
    fun `auto chooses the best complete stream before adaptive or split streams`() {
        val complete = format("720p-mp4", "https://cdn.test/720", "mp4", 720, videoOnly = false)
        val videoOnly = format(
            "1080p-webm", "https://cdn.test/1080", "webm", 1080, videoOnly = true,
        ).copy(audioUrl = "https://cdn.test/audio", audioMimeType = "audio/webm", audioExtension = "webm")
        val adaptive = format("adaptive", "https://cdn.test/live.m3u8", "m3u8", 0, videoOnly = false)
            .copy(adaptiveManifest = true)

        val selected = ExternalVideoInfo("title", "artist", 20, null, listOf(videoOnly, adaptive, complete))
            .autoPlaybackFormat()

        assertEquals(complete.id, selected?.id)
    }

    @Test
    fun `download selection follows the stream playing rather than the highest listed quality`() {
        val low = format("360p", "https://cdn.test/360.mp4", "mp4", 360, videoOnly = false)
        val high = format("1080p", "https://cdn.test/1080.webm", "webm", 1080, videoOnly = false)
            .copy(codec = "vp9", audioCodec = "opus")
        val metadata = MediaMetadata(
            id = "external-video:test",
            title = "title",
            artists = emptyList(),
            duration = 20,
            isExternalVideo = true,
            externalVideoResolution = low.resolution,
            externalVideoExtension = low.extension,
            externalVideoMimeType = low.mimeType,
            externalVideoStreamUrl = low.url,
            externalVideoFormats = listOf(low, high).map { format ->
                MediaMetadata.ExternalVideoPlaybackFormat(
                    id = format.id,
                    url = format.url,
                    mimeType = format.mimeType,
                    extension = format.extension,
                    resolution = format.resolution,
                    height = format.height,
                    bitrate = format.bitrate,
                    videoOnly = format.videoOnly,
                    adaptiveManifest = format.adaptiveManifest,
                    audioUrl = format.audioUrl,
                    audioMimeType = format.audioMimeType,
                    audioExtension = format.audioExtension,
                    codec = format.codec,
                    audioCodec = format.audioCodec,
                )
            },
        )

        val selected = metadata.selectedExternalVideoFormat("https://cdn.test/360.mp4")

        assertNotNull(selected)
        assertEquals(low.id, selected?.id)
        assertEquals("mp4", selected?.extension)
        assertEquals("vp9", metadata.externalVideoFormats.last().codec)
        assertEquals("opus", metadata.externalVideoFormats.last().audioCodec)
    }

    private fun format(
        id: String,
        url: String,
        extension: String,
        height: Int,
        videoOnly: Boolean,
    ) = ExternalVideoFormat(
        id = id,
        url = url,
        mimeType = "video/$extension",
        extension = extension,
        resolution = if (height == 0) "Adaptive" else "${height}p",
        height = height,
        bitrate = height * 1_000,
        videoOnly = videoOnly,
    )
}
