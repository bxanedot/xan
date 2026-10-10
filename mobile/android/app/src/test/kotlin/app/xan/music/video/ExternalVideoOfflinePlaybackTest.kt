package app.xan.music.video

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import app.xan.music.models.MediaMetadata
import app.xan.music.extensions.toMediaItem
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ExternalVideoOfflinePlaybackTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `saved video metadata reopens its local file through the playback data source`() {
        val expected = ByteArray(4096) { (it % 239).toByte() }
        val file = temporaryFolder.newFile("saved-video.mp4").apply { writeBytes(expected) }
        val metadata = MediaMetadata(
            id = "external-video:test",
            title = "Saved video",
            artists = emptyList(),
            duration = 1,
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            isExternalVideo = true,
            externalVideoStreamUrl = file.toURI().toString(),
            externalVideoMimeType = "video/mp4",
            externalVideoExtension = "mp4",
        )

        val item = metadata.toMediaItem()
        assertEquals(file.toURI().toString(), item.localConfiguration?.uri.toString())
        assertTrue(item.mediaMetadata.mediaType == androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO)

        val source = DefaultDataSource.Factory(RuntimeEnvironment.getApplication()).createDataSource()
        val output = ByteArrayOutputStream()
        try {
            source.open(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
            val buffer = ByteArray(257)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
        } finally {
            source.close()
        }
        assertArrayEquals(expected, output.toByteArray())
    }
}
