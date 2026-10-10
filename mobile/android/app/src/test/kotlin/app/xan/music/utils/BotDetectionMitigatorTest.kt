package app.xan.music.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the YouTube error signatures used by playback recovery. A region block
 * and an unrelated generic IO error must not be mistaken for a bot challenge.
 */
class BotDetectionMitigatorTest {

    @Test
    fun `geo error signatures are detected`() {
        assertTrue(BotDetectionMitigator.isGeoError("This video is not available in your country"))
        assertTrue(BotDetectionMitigator.isGeoError("Content is geo-restricted"))
        assertTrue(BotDetectionMitigator.isGeoError("NOT_AVAILABLE_IN_THIS_COUNTRY"))
    }

    @Test
    fun `geo error detection is case-insensitive`() {
        assertTrue(BotDetectionMitigator.isGeoError("NOT AVAILABLE IN YOUR REGION"))
    }

    @Test
    fun `null or unrelated messages are not geo errors`() {
        assertFalse(BotDetectionMitigator.isGeoError(null))
        assertFalse(BotDetectionMitigator.isGeoError("Missing stream expire time"))
        assertFalse(BotDetectionMitigator.isGeoError("Bad stream player response"))
    }

    @Test
    fun `explicit YouTube bot verification messages are detected`() {
        assertTrue(BotDetectionMitigator.isBotDetectionError("h77: Sign in to confirm that you're not a bot"))
        assertTrue(BotDetectionMitigator.isBotDetectionError("Sign in to confirm you're not a bot"))
        assertTrue(BotDetectionMitigator.isBotDetectionError("YouTube returned automated queries"))
    }

    @Test
    fun `generic media and http errors are not mistaken for bot verification`() {
        assertFalse(BotDetectionMitigator.isBotDetectionError("Error 2000"))
        assertFalse(BotDetectionMitigator.isBotDetectionError("HTTP 403"))
        assertFalse(BotDetectionMitigator.isBotDetectionError("This content isn't available on this device"))
    }

    @Test
    fun `bot challenge is detected through wrapped causes`() {
        val error = IllegalStateException(
            "Unexpected player response",
            IllegalStateException("Sign in to confirm that you're not a bot"),
        )
        assertTrue(BotDetectionMitigator.isBotDetectionError(error))
    }

    @Test
    fun `visitor refresh is limited to bot challenges regardless of login state`() {
        assertTrue(
            BotDetectionMitigator.shouldRefreshVisitorSession(
                IllegalStateException("Sign in to confirm that you're not a bot"),
            ),
        )
        assertFalse(BotDetectionMitigator.shouldRefreshVisitorSession(IllegalStateException("Error 2000")))
        assertFalse(BotDetectionMitigator.shouldRefreshVisitorSession(IllegalStateException("geo-restricted")))
    }

    @Test
    fun `notifyPlaybackFailure returns false for logged-in users`() {
        // This generic failure counter only tracks guest playback failures.
        // Explicit bot challenges use the visitor-data refresh path separately.
        assertFalse(BotDetectionMitigator.notifyPlaybackFailure(isLoggedIn = true, errorMessage = "Bad stream player response"))
    }

    @Test
    fun `notifyPlaybackFailure returns false for a geo-restricted guest failure`() {
        assertFalse(BotDetectionMitigator.notifyPlaybackFailure(isLoggedIn = false, errorMessage = "geo-restricted"))
    }

    @Test
    fun `notifyPlaybackFailure returns true for a non-geo guest failure`() {
        assertTrue(BotDetectionMitigator.notifyPlaybackFailure(isLoggedIn = false, errorMessage = "Missing stream expire time"))
    }
}
