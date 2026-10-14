package app.xan.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureClassifierTest {
    @Test
    fun recognizesUnavailableMessageThroughLoaderCauseChain() {
        val error = IllegalStateException(
            "UnexpectedLoaderException",
            RuntimeException("Video unavailable"),
        )

        assertTrue(PlaybackFailureClassifier.isContentUnavailable(error))
    }

    @Test
    fun leavesNetworkFailuresRetryable() {
        assertFalse(PlaybackFailureClassifier.isContentUnavailable(java.net.SocketTimeoutException("Timed out")))
    }

    @Test
    fun doesNotClassifyOrdinaryPlaybackFailuresAsUnavailable() {
        assertFalse(PlaybackFailureClassifier.isContentUnavailable(IllegalStateException("No audio format found")))
    }
}
