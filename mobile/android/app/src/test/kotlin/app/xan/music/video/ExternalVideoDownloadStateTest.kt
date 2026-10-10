package app.xan.music.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalVideoDownloadStateTest {
    @Test fun `unknown size remains indeterminate`() {
        assertNull(downloadPercent(150, -1, false))
        assertNull(downloadPercent(150, 0, false))
    }

    @Test fun `received bytes cannot claim completion before validation and publish`() {
        assertEquals(99, downloadPercent(100, 100, false))
        assertEquals(99, downloadPercent(110, 100, false))
        assertEquals(100, downloadPercent(100, 100, true))
    }

    @Test fun `known progress reports transferred fraction`() {
        assertEquals(25, downloadPercent(25, 100, false))
        assertEquals(0, downloadPercent(-1, 100, false))
    }
}
