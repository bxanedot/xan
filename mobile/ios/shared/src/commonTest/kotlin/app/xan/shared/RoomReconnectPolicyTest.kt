package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomReconnectPolicyTest {
    @Test
    fun `reconnect delay backs off and caps at 32 seconds`() {
        assertEquals(1_000L, roomReconnectDelayMillis(1))
        assertEquals(2_000L, roomReconnectDelayMillis(2))
        assertEquals(4_000L, roomReconnectDelayMillis(3))
        assertEquals(16_000L, roomReconnectDelayMillis(5))
        assertEquals(32_000L, roomReconnectDelayMillis(6))
        assertEquals(32_000L, roomReconnectDelayMillis(20))
    }

    @Test
    fun `invalid low retry count uses the first backoff interval`() {
        assertEquals(1_000L, roomReconnectDelayMillis(0))
        assertEquals(1_000L, roomReconnectDelayMillis(-4))
    }
}
