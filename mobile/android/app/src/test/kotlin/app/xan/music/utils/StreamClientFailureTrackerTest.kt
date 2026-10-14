package app.xan.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamClientFailureTrackerTest {
    @Test
    fun `rejected clients are excluded per video until their failure expires`() {
        var now = 1_000L
        val tracker = StreamClientFailureTracker(ttlMillis = 3_000L) { now }

        tracker.markRejected("video-a", "WEB_CREATOR")
        now += 1_000L
        tracker.markRejected("video-a", "WEB_REMIX")

        assertEquals(setOf("WEB_CREATOR", "WEB_REMIX"), tracker.excludedFor("video-a"))
        assertEquals(emptySet<String>(), tracker.excludedFor("video-b"))

        now += 2_000L
        assertEquals(setOf("WEB_REMIX"), tracker.excludedFor("video-a"))

        now += 1_000L
        assertEquals(emptySet<String>(), tracker.excludedFor("video-a"))
    }
}
