package app.xan.desktop

import com.music.shazamkit.models.RecognitionResult
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class MusicRecognitionFlowTest {
    @Test
    fun recordsProcessesAndReturnsMatchedTrack() = runBlocking {
        lateinit var flow: MusicRecognitionFlow
        var durationMillis = 0L
        flow = MusicRecognitionFlow(
            recordPcm = {
                assertTrue(flow.status.value is RecognitionStatus.Listening)
                pcmTone(seconds = 1)
            },
            lookup = { signature, duration ->
                assertTrue(flow.status.value is RecognitionStatus.Processing)
                assertTrue(signature.startsWith("data:audio/vnd.shazam.sig;base64,"))
                durationMillis = duration
                Result.success(recognitionResult())
            },
        )

        val outcome = flow.recognize()

        assertTrue(outcome is RecognitionStatus.Success)
        assertTrue(durationMillis in 990L..1_010L)
        assertEquals(outcome, flow.status.value)
    }

    @Test
    fun noMatchIsRetryableAndMicrophoneErrorsAreSurfaced() = runBlocking {
        val noMatch = MusicRecognitionFlow({ pcmTone(1) }, { _, _ -> Result.failure(IllegalStateException("No matches found")) })
        assertTrue(noMatch.recognize() is RecognitionStatus.NoMatch)
        assertEquals(RecognitionStatus.Ready, noMatch.status.value.let {
            noMatch.reset()
            noMatch.status.value
        })

        val noMicrophone = MusicRecognitionFlow({ error("Microphone permission is unavailable") }, { _, _ -> error("must not run") })
        val error = noMicrophone.recognize() as RecognitionStatus.Error
        assertTrue(error.message.contains("Microphone permission"))
    }

    private fun pcmTone(seconds: Int): ByteArray {
        val sampleRate = 44_100
        val samples = ShortArray(sampleRate * seconds) { index ->
            (sin(2.0 * PI * 440.0 * index / sampleRate) * Short.MAX_VALUE * .2).toInt().toShort()
        }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        return bytes.array()
    }

    private fun recognitionResult() = RecognitionResult(
        trackId = "recognized-track",
        title = "Recognized Test Song",
        artist = "XAN Test",
        album = "Test Album",
        coverArtUrl = null,
        coverArtHqUrl = null,
        genre = null,
        releaseDate = null,
        label = null,
        lyrics = null,
        shazamUrl = "https://www.shazam.com/track/recognized-track",
        appleMusicUrl = null,
        spotifyUrl = null,
        isrc = null,
    )
}
