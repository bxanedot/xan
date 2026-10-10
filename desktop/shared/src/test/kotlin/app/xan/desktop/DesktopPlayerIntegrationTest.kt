package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class DesktopPlayerIntegrationTest {
    @Test
    fun localAudioCanStartPauseSeekAndStopInTheJavaFxEngine() {
        assumeFalse(
            "JavaFX Media integration is disabled on the hosted Windows runner after its native media worker crashed; run this test on a Windows desktop with audio output.",
            java.lang.Boolean.getBoolean("xan.skipNativeMediaIntegration"),
        )

        val folder = Files.createTempDirectory("xan-player-integration-")
        val wav = folder.resolve("silent.wav")
        writeSilentWav(wav, seconds = 4)
        val originalVolume = DesktopBackend.playerVolume()
        val originalEqualizer = DesktopBackend.equalizerSettings()
        val originalFade = DesktopBackend.djTransitionSeconds()
        try {
            DesktopPlayer.setVolume(0f)
            val track = MusicTrack(
                title = "Silent test track", artist = "XAN test", album = "",
                colors = listOf(XanColors.CardLight, XanColors.Card), motif = "♫",
                source = "local", durationSeconds = 4, localPath = wav.toString(), metadataVerified = false,
            )
            val secondTrack = track.copy(title = "Second test track")
            val thirdTrack = track.copy(title = "Third test track")
            runBlocking { DesktopPlayer.playQueue(listOf(track, secondTrack, thirdTrack), 0) }
            val started = awaitState { it.status == PlaybackStatus.PLAYING }
            assertTrue("JavaFX did not start playback: ${DesktopPlayer.state.value.error}", started)
            assertTrue("Playback clock did not advance", awaitState { it.positionMillis >= 500 })
            val firstPlaybackSession = DesktopPlayer.state.value.playbackSessionId

            val liveSettings = EqualizerSettings(enabled = true, advanced = true, gainsDb = listOf(6.0, 3.0, -2.0, 4.0, 10.0))
            DesktopPlayer.setEqualizerSettings(liveSettings)
            assertTrue("EQ settings were not persisted", DesktopBackend.equalizerSettings() == liveSettings)
            assertTrue("JavaFX equalizer did not receive the five bands", awaitEq { it.enabled && it.gainsDb == liveSettings.gainsDb && it.effectivePlayerVolume < 0.8 })
            DesktopPlayer.setEqualizerSettings(liveSettings.copy(enabled = false))
            assertTrue("EQ bypass did not reach the active player", awaitEq { !it.enabled })

            DesktopPlayer.togglePlayPause()
            assertTrue("Player did not pause", awaitState { it.status == PlaybackStatus.PAUSED })
            val pausedPosition = DesktopPlayer.state.value.positionMillis
            Thread.sleep(400)
            assertTrue("Position advanced while paused", DesktopPlayer.state.value.positionMillis <= pausedPosition + 100)

            DesktopBackend.setDjTransitionSeconds(1)
            DesktopPlayer.setVolume(0.4f)
            DesktopPlayer.seekTo(3_500)
            assertTrue("Seek into the fade-out window did not lower the output", awaitEq { it.effectivePlayerVolume in 0.01..0.3 })
            DesktopPlayer.seekTo(1_500)
            assertTrue("Seeking back left the player muted", awaitEq { kotlin.math.abs(it.effectivePlayerVolume - 0.4) < 0.01 })
            DesktopPlayer.setVolume(0f)
            DesktopBackend.setDjTransitionSeconds(0)

            DesktopPlayer.seekTo(0)
            assertTrue("Seek did not move to the beginning", awaitState { it.positionMillis < 250 })
            DesktopPlayer.enqueueNext(track)
            assertEquals(4, DesktopPlayer.state.value.queue.size)
            runBlocking { DesktopPlayer.next() }
            assertTrue("Next track did not advance the queue", awaitState { it.queueIndex == 1 && it.status == PlaybackStatus.PLAYING })
            assertNotEquals("Repeated identical tracks need distinct scrobble event ids", firstPlaybackSession, DesktopPlayer.state.value.playbackSessionId)
            runBlocking { DesktopPlayer.previous() }
            assertTrue("Previous track did not return in the queue", awaitState { it.queueIndex == 0 && it.status == PlaybackStatus.PLAYING })
            assertTrue("Queue reorder should succeed", DesktopPlayer.moveQueueItem(3, 0))
            assertEquals("Third test track", DesktopPlayer.state.value.queue[0].title)
            assertEquals("Reordering before the current item should preserve the current track", 1, DesktopPlayer.state.value.queueIndex)
            assertTrue("Removing an earlier item should shift the current index", runBlocking { DesktopPlayer.removeQueueItem(0) })
            assertEquals(0, DesktopPlayer.state.value.queueIndex)
            assertTrue("Removing the current item should start the next available track", runBlocking { DesktopPlayer.removeQueueItem(0) })
            assertTrue("The player should continue on the replacement current item", awaitState { it.status == PlaybackStatus.PLAYING && it.queueIndex == 0 })
            assertEquals(RepeatMode.ALL, DesktopPlayer.cycleRepeatMode())
            assertEquals(RepeatMode.ONE, DesktopPlayer.cycleRepeatMode())
            val beforeRepeatOne = DesktopPlayer.state.value.playbackSessionId
            runBlocking { DesktopPlayer.next() }
            assertTrue("Repeat One should replay the current track", awaitState { it.playbackSessionId != beforeRepeatOne && it.queueIndex == 0 && it.status == PlaybackStatus.PLAYING })
            assertEquals(RepeatMode.OFF, DesktopPlayer.cycleRepeatMode())
            DesktopPlayer.setRepeatModeForTest(RepeatMode.ALL)
            runBlocking { DesktopPlayer.next() }
            assertTrue("Repeat All should advance through the queue", awaitState { it.queueIndex == 1 && it.status == PlaybackStatus.PLAYING })
            runBlocking { DesktopPlayer.next() }
            assertTrue("Repeat All should wrap to the start of the queue", awaitState { it.queueIndex == 0 && it.status == PlaybackStatus.PLAYING })
            DesktopPlayer.setRepeatModeForTest(RepeatMode.OFF)
            DesktopPlayer.seekTo((DesktopPlayer.state.value.durationMillis - 300).coerceAtLeast(0))
            assertTrue(
                "Playback did not automatically continue to the next queue item at end of track",
                awaitState(timeoutMillis = 8_000) { it.queueIndex == 1 && it.status == PlaybackStatus.PLAYING },
            )
            runBlocking { DesktopPlayer.stop() }
            assertEquals(PlaybackStatus.IDLE, DesktopPlayer.state.value.status)
        } finally {
            DesktopPlayer.shutdown()
            DesktopBackend.setPlayerVolume(originalVolume)
            DesktopBackend.setDjTransitionSeconds(originalFade)
            DesktopPlayer.setEqualizerSettings(originalEqualizer)
            runCatching { Files.deleteIfExists(wav) }.onFailure { wav.toFile().deleteOnExit() }
            runCatching { Files.deleteIfExists(folder) }.onFailure { folder.toFile().deleteOnExit() }
        }
    }

    private fun awaitState(timeoutMillis: Long = 5_000, condition: (PlaybackSnapshot) -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition(DesktopPlayer.state.value)) return true
            Thread.sleep(25)
        }
        return condition(DesktopPlayer.state.value)
    }

    private fun awaitEq(timeoutMillis: Long = 5_000, condition: (EqualizerRuntimeSnapshot) -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val snapshot = runCatching { runBlocking { DesktopPlayer.equalizerRuntimeSnapshot() } }.getOrNull()
            if (snapshot != null && condition(snapshot)) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun writeSilentWav(path: java.nio.file.Path, seconds: Int) {
        val sampleRate = 44_100
        val channels = 1
        val bitsPerSample = 16
        val dataLength = sampleRate * channels * (bitsPerSample / 8) * seconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLength)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * (bitsPerSample / 8))
        header.putShort((channels * (bitsPerSample / 8)).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength)
        Files.newOutputStream(path).use { output ->
            output.write(header.array())
            output.write(ByteArray(dataLength))
        }
    }
}
