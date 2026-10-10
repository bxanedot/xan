package app.xan.music.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistDownloadProgressTest {
    @Test fun `counts completed files separately from queued failed and missing songs`() {
        assertEquals(ArtistDownloadProgress(1, 4, 1), artistDownloadProgress(
            listOf("done", "queued", "failed", "missing", "done"), mapOf(
                "done" to Download.STATE_COMPLETED, "queued" to Download.STATE_QUEUED,
                "failed" to Download.STATE_FAILED, "other-artist" to Download.STATE_COMPLETED)))
    }
    @Test fun `an unresolved artist does not inherit other artists downloads`() {
        assertEquals(ArtistDownloadProgress(0, 0, 0), artistDownloadProgress(emptyList(), mapOf("other" to Download.STATE_COMPLETED)))
    }
}
