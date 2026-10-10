package app.xan.shared

import app.xan.music.listentogether.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosRoomPlaybackPlannerTest {
    @Test
    fun startsANewRoomTrackAtTheSynchronizedPositionAndPlaybackState() {
        val track = track("video123")
        val plan = IosRoomPlaybackPlanner().update(track, shouldPlay = false, targetPositionMillis = 12_500, isResolving = false)

        assertEquals(track, plan.trackToResolve)
        assertEquals(12_500L, plan.positionMillis)
        assertEquals(false, plan.playing)
    }

    @Test
    fun mapsRoomPauseAndSeekUpdatesToNativePlayerCommands() {
        val planner = IosRoomPlaybackPlanner()
        val track = track("video123")
        planner.update(track, shouldPlay = true, targetPositionMillis = 2_000L, isResolving = false)

        val pauseAndSeek = planner.update(track, shouldPlay = false, targetPositionMillis = 9_000L, isResolving = false)
        assertNull(pauseAndSeek.trackToResolve)
        assertEquals(9_000L, pauseAndSeek.positionMillis)
        assertEquals(false, pauseAndSeek.playing)

        val unchanged = planner.update(track, shouldPlay = false, targetPositionMillis = 9_000L, isResolving = false)
        assertNull(unchanged.trackToResolve)
        assertNull(unchanged.positionMillis)
        assertNull(unchanged.playing)
    }

    @Test
    fun reconnectingTrackChangeRequestsAReplacementStream() {
        val planner = IosRoomPlaybackPlanner()
        planner.update(track("video123"), shouldPlay = true, targetPositionMillis = 1_000L, isResolving = false)

        val nextTrack = track("video456")
        val plan = planner.update(nextTrack, shouldPlay = true, targetPositionMillis = 4_000L, isResolving = false)

        assertEquals(nextTrack, plan.trackToResolve)
        assertEquals(4_000L, plan.positionMillis)
        assertEquals(true, plan.playing)
    }

    private fun track(id: String) = TrackInfo(
        id = id,
        title = "Room track",
        artist = "Room artist",
        duration = 180_000L,
        thumbnail = "https://i.ytimg.com/cover.jpg",
    )
}
