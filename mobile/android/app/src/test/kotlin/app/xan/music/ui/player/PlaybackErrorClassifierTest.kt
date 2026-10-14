package app.xan.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorClassifierTest {
    @Test
    fun `generic forbidden response is not mislabeled as age restricted`() {
        assertFalse(isExplicitAgeRestriction("HTTP 403 - Response code: 403"))
    }

    @Test
    fun `explicit age verification response is recognized`() {
        assertTrue(isExplicitAgeRestriction("Sign in to confirm your age"))
    }
}
