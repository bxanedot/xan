package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopQueueOperationsTest {
    private fun track(id: String) = MusicTrack(
        title = id,
        artist = "Artist",
        album = "Album",
        colors = listOf(XanColors.Card, XanColors.CardLight),
        motif = "♪",
        videoId = id,
    )

    @Test
    fun hostQueueAddInsertNextAndAppendPreserveCurrentTrack() {
        val initial = DesktopQueueState(listOf(track("current"), track("later")), currentIndex = 0)
        val afterNext = DesktopQueueOperations.add(initial, track("next"), insertNext = true)
        assertEquals(listOf("current", "next", "later"), afterNext.tracks.map { it.videoId })
        assertEquals(0, afterNext.currentIndex)

        val afterAppend = DesktopQueueOperations.add(afterNext, track("tail"), insertNext = false)
        assertEquals(listOf("current", "next", "later", "tail"), afterAppend.tracks.map { it.videoId })
        assertEquals(0, afterAppend.currentIndex)
    }

    @Test
    fun hostQueueRemoveAndClearOnlyMutateItemsAfterCurrent() {
        val initial = DesktopQueueState(listOf(track("current"), track("remove"), track("keep")), currentIndex = 0)
        val afterRemove = DesktopQueueOperations.removeFuture(initial, "remove")
        assertEquals(listOf("current", "keep"), afterRemove.tracks.map { it.videoId })
        assertEquals(0, afterRemove.currentIndex)

        val afterClear = DesktopQueueOperations.clearFuture(afterRemove)
        assertEquals(listOf("current"), afterClear.tracks.map { it.videoId })
        assertEquals(0, afterClear.currentIndex)
    }

    @Test
    fun emptyQueueClearUsesNoCurrentIndex() {
        val cleared = DesktopQueueOperations.clearFuture(DesktopQueueState(listOf(track("stale")), currentIndex = -1))
        assertEquals(emptyList<MusicTrack>(), cleared.tracks)
        assertEquals(-1, cleared.currentIndex)
    }
}
