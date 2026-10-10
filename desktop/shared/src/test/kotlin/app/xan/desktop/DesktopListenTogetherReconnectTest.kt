package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopListenTogetherReconnectTest {
    @Test
    fun reconnectDelayUsesExponentialBackoffWithThirtyTwoSecondCap() {
        assertEquals(1_000L, desktopRoomReconnectDelayMillis(1))
        assertEquals(2_000L, desktopRoomReconnectDelayMillis(2))
        assertEquals(4_000L, desktopRoomReconnectDelayMillis(3))
        assertEquals(16_000L, desktopRoomReconnectDelayMillis(5))
        assertEquals(32_000L, desktopRoomReconnectDelayMillis(6))
        assertEquals(32_000L, desktopRoomReconnectDelayMillis(20))
    }

    @Test
    fun invalidLowAttemptUsesFirstBackoffInterval() {
        assertEquals(1_000L, desktopRoomReconnectDelayMillis(0))
        assertEquals(1_000L, desktopRoomReconnectDelayMillis(-4))
    }
}
