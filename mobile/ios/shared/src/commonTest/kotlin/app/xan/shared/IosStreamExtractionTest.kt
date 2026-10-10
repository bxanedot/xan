package app.xan.shared

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*

class IosStreamExtractionTest {
    @Test fun directStreamDoesNotRunTheNativeExtractor() = runBlocking {
        val extractor = FakeExtractor()
        val direct = IosResolvedAudio("https://rr1.googlevideo.com/direct", "audio/mp4", 128_000)
        assertEquals(direct, resolveIosAudioWithFallback("video123", extractor) { direct })
        assertEquals(0, extractor.calls)
    }

    @Test fun decipheringFailureFallsBackToNativeExtraction() = runBlocking {
        val extractor = FakeExtractor { it.onResolved("https://rr1.googlevideo.com/deciphered?sig=valid"); it.onFailure("late duplicate") }
        val result = resolveIosAudioWithFallback("video123", extractor) { error("Cipher-only formats") }
        assertEquals("https://rr1.googlevideo.com/deciphered?sig=valid", result.url)
        assertEquals("audio/mp4", result.mimeType)
        assertEquals(1, extractor.calls)
    }

    @Test fun unsafeExtractorOutputIsRejected() = runBlocking {
        val extractor = FakeExtractor { it.onResolved("https://attacker.example/audio") }
        assertFailsWith<IllegalStateException> { resolveIosAudioWithFallback("video123", extractor) { error("No direct audio") } }
        assertNull(safeIosAudioStreamUrl("https://user:secret@rr1.googlevideo.com/audio"))
        assertNull(safeIosAudioStreamUrl("http://rr1.googlevideo.com/audio"))
        assertNull(safeIosAudioStreamUrl("https://rr1.googlevideo.com:8443/audio"))
    }

    @Test fun cancellingPlaybackCancelsTheNativeExtractionTask() = runBlocking {
        val extractor = FakeExtractor()
        assertNull(withTimeoutOrNull(20) { resolveIosAudioWithFallback("video123", extractor) { error("No direct audio") } })
        assertEquals(1, extractor.cancellations)
    }

    private class FakeExtractor(private val reply: (IosStreamExtractionCallback) -> Unit = {}) : IosNativeStreamExtractor {
        var calls = 0
        var cancellations = 0
        override fun resolveAudio(videoId: String, callback: IosStreamExtractionCallback) { calls++; reply(callback) }
        override fun cancel() { cancellations++ }
    }
}
