package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class IosPlaybackQueueTest {
    private val first = track("first")
    private val second = track("second")
    private val third = track("third")

    @Test
    fun insertsNextAfterTheCurrentTrackAndAdvancesInOrder() {
        val playing = createIosPlaybackQueue(listOf(first, second), startIndex = 0)
        val withNext = enqueueIosPlaybackTrack(playing, third)

        assertEquals(listOf("first", "third", "second"), withNext.orderedTracks().map { it.title })
        assertEquals("first", withNext.currentTrack?.title)
        val advanced = requireNotNull(advanceIosPlaybackQueue(withNext, 1))
        assertEquals("third", advanced.currentTrack?.title)
        assertEquals("second", requireNotNull(advanceIosPlaybackQueue(advanced, 1)).currentTrack?.title)
        assertNull(advanceIosPlaybackQueue(advanced.copy(currentIndex = 2), 1))
    }

    @Test
    fun deduplicatesAndRemovesTracksWithoutLosingTheCurrentSelection() {
        val initial = createIosPlaybackQueue(listOf(first, second, third), startIndex = 1)
        val duplicate = enqueueIosPlaybackTrack(initial, third)
        assertEquals(listOf("first", "second", "third"), duplicate.tracks.map { it.title })
        assertEquals("third is already in the queue.", duplicate.message)

        val removedBeforeCurrent = removeIosPlaybackQueueTrack(initial, first.videoId)
        assertEquals(0, removedBeforeCurrent.currentIndex)
        assertEquals("second", removedBeforeCurrent.currentTrack?.title)
        val removedCurrent = removeIosPlaybackQueueTrack(removedBeforeCurrent, second.videoId)
        assertEquals("third", removedCurrent.currentTrack?.title)
    }

    @Test
    fun clearingQueueKeepsOnlyTheCurrentTrack() {
        val initial = createIosPlaybackQueue(listOf(first, second, third), startIndex = 1)
        val cleared = clearIosPlaybackQueueAfterCurrent(initial)
        assertEquals(listOf("second"), cleared.tracks.map { it.title })
        assertEquals(0, cleared.currentIndex)
        assertNull(advanceIosPlaybackQueue(cleared, 1))
    }

    @Test
    fun rejectsInvalidVideoIdsBeforeTheyReachThePlayer() {
        val invalid = IosMusicSearchTrack("bad", "Bad", "Artist", null, 180, null)
        assertFailsWith<IllegalArgumentException> { createIosPlaybackQueue(listOf(invalid)) }
    }

    @Test
    fun shuffleKeepsTheCurrentTrackAndRestoresTheOriginalQueueOrderWhenDisabled() {
        val initial = createIosPlaybackQueue(listOf(first, second, third), startIndex = 1)
        val shuffled = setIosQueueShuffle(initial, enabled = true)

        assertEquals("second", shuffled.currentTrack?.title)
        assertEquals(setOf("first", "second", "third"), shuffled.orderedTracks().map { it.title }.toSet())
        assertEquals(listOf("first", "second", "third"), shuffled.tracks.map { it.title })

        val restored = setIosQueueShuffle(shuffled, enabled = false)
        assertEquals(listOf("first", "second", "third"), restored.orderedTracks().map { it.title })
        assertEquals("second", restored.currentTrack?.title)
        assertEquals(1, restored.currentIndex)
    }

    @Test
    fun repeatAllWrapsInBothDirectionsAndRepeatOneAppliesAtTrackEnd() {
        val repeatAll = createIosPlaybackQueue(listOf(first, second), startIndex = 1, repeatMode = IosRepeatMode.ALL)
        assertEquals("first", requireNotNull(advanceIosPlaybackQueue(repeatAll, 1)).currentTrack?.title)
        assertEquals("second", requireNotNull(advanceIosPlaybackQueue(repeatAll.copy(currentIndex = 0), -1)).currentTrack?.title)
        assertEquals(true, shouldRepeatIosQueueCurrentAtEnd(createIosPlaybackQueue(listOf(first), repeatMode = IosRepeatMode.ALL)))

        val repeatOne = createIosPlaybackQueue(listOf(first, second), repeatMode = IosRepeatMode.ONE)
        assertEquals(true, shouldRepeatIosQueueCurrentAtEnd(repeatOne))
        val oneTrack = repeatOne.copy(tracks = listOf(first), playOrder = listOf(0))
        assertEquals(true, shouldRepeatIosQueueCurrentAtEnd(oneTrack))
    }

    @Test
    fun queueEditsRetainShuffleAndRepeatModes() {
        val initial = createIosPlaybackQueue(listOf(first, second, third), startIndex = 0, shuffleEnabled = true, repeatMode = IosRepeatMode.ALL)
        val cleared = clearIosPlaybackQueueAfterCurrent(initial)
        assertEquals(true, cleared.shuffleEnabled)
        assertEquals(IosRepeatMode.ALL, cleared.repeatMode)
        assertEquals("first", cleared.currentTrack?.title)
    }

    @Test
    fun playbackModesRestoreOnlyWhenTheAndroidPreferenceIsEnabled() {
        val restored = restoredIosPlaybackModes("true", "true", "ALL")
        assertEquals(true, restored.shuffleEnabled)
        assertEquals(IosRepeatMode.ALL, restored.repeatMode)

        val disabled = restoredIosPlaybackModes("false", "true", "ONE")
        assertEquals(false, disabled.shuffleEnabled)
        assertEquals(IosRepeatMode.OFF, disabled.repeatMode)

        val migrated = restoredIosPlaybackModes(null, null, "INVALID")
        assertEquals(false, migrated.shuffleEnabled)
        assertEquals(IosRepeatMode.OFF, migrated.repeatMode)
    }

    private fun track(id: String) = IosMusicSearchTrack("${id}123456", id, "Artist", null, 180, null)
}
