package app.xan.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -Dxan.liveSmoke=true. Tests direct YouTube playback and automatic yt-dlp recovery in XAN's embedded player. */
class LivePlaybackSmokeTest {
    @Test
    fun youtubeAudioAndYtDlpRecoveryPlayInsideTheDesktopPlayer() = runBlocking {
        assumeTrue("Set -Dxan.liveSmoke=true to enable the network and audio smoke test.", java.lang.Boolean.getBoolean("xan.liveSmoke"))
        val originalVolume = DesktopPlayer.state.value.volume
        val track = MusicTrack(
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            colors = listOf(XanColors.CardLight, XanColors.Card),
            motif = "♫",
            videoId = "dQw4w9WgXcQ",
            source = "YouTube Music",
        )
        try {
            DesktopPlayer.setVolume(0f)
            DesktopPlayer.play(track)
            val deadline = System.currentTimeMillis() + 90_000
            while (DesktopPlayer.state.value.status !in setOf(PlaybackStatus.PLAYING, PlaybackStatus.ERROR) && System.currentTimeMillis() < deadline) {
                delay(250)
            }
            assertEquals("XAN's embedded player did not begin playback: ${DesktopPlayer.state.value.error}", PlaybackStatus.PLAYING, DesktopPlayer.state.value.status)
            delay(2_000)
            assertTrue("The in-app audio position did not advance.", DesktopPlayer.state.value.positionMillis > 0)

            // Force the remote stream to fail so the test exercises yt-dlp download and local in-app recovery.
            DesktopPlayer.stop()
            DesktopPlayer.playQueue(listOf(track), 0) { java.net.URI("https://127.0.0.1:9/unplayable-audio") }
            val recoveryDeadline = System.currentTimeMillis() + 120_000
            while (DesktopPlayer.state.value.status !in setOf(PlaybackStatus.PLAYING, PlaybackStatus.ERROR) && System.currentTimeMillis() < recoveryDeadline) {
                delay(250)
            }
            assertEquals("XAN did not recover through its embedded player: ${DesktopPlayer.state.value.error}", PlaybackStatus.PLAYING, DesktopPlayer.state.value.status)
            delay(2_000)
            assertTrue("The yt-dlp recovery audio did not advance inside XAN.", DesktopPlayer.state.value.positionMillis > 0)
        } finally {
            runCatching { DesktopPlayer.stop() }
            DesktopPlayer.setVolume(originalVolume)
            DesktopPlayer.shutdown()
            DesktopBackend.clearPlaybackFallback(track.videoId!!)
        }
    }
}
