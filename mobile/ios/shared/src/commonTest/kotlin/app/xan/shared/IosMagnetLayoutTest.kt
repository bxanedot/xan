package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class IosMagnetLayoutTest {
    @Test
    fun docksToTheNearestOfTheFourWindowEdges() {
        assertEquals(IosMagnetPosition(0f, .5f), dockIosMagnet(.1f, .5f, 300f, 500f))
        assertEquals(IosMagnetPosition(1f, .5f), dockIosMagnet(.9f, .5f, 300f, 500f))
        assertEquals(IosMagnetPosition(.5f, 0f), dockIosMagnet(.5f, .05f, 300f, 500f))
        assertEquals(IosMagnetPosition(.5f, 1f), dockIosMagnet(.5f, .95f, 300f, 500f))
    }

    @Test
    fun clampsInvalidPositionsAndHandlesCompactWindowBounds() {
        assertEquals(IosMagnetPosition(0f, 1f), dockIosMagnet(-1f, 2f, 0f, 0f))
    }
}
