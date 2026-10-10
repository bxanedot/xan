package app.xan.music.ui.component

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetSemicircleLayoutTest {
    @Test
    fun `all five destination targets fit and do not overlap at supported widths`() {
        for (width in listOf(240f, 280f, 320f)) {
            for (onLeft in listOf(false, true)) {
                val layout = magnetSemicircleLayout(width, onLeft, itemCount = 5)
                assertEquals(5, layout.items.size)
                layout.items.forEach { item ->
                    assertTrue(item.x >= 26f && item.x <= layout.width - 26f)
                    assertTrue(item.y >= 26f && item.y <= layout.height - 26f)
                    assertTrue(hypot(item.x - layout.magnet.x, item.y - layout.magnet.y) >= 54f)
                }
                layout.items.forEachIndexed { index, item ->
                    layout.items.drop(index + 1).forEach { other ->
                        assertTrue(hypot(item.x - other.x, item.y - other.y) >= 52f)
                    }
                }
            }
        }
    }

    @Test
    fun `left placement mirrors right placement around the Magnet`() {
        val right = magnetSemicircleLayout(320f, false)
        val left = magnetSemicircleLayout(320f, true)
        assertEquals(320f - right.magnet.x, left.magnet.x, .01f)
        right.items.zip(left.items).forEach { (r, l) ->
            assertEquals(320f - r.x, l.x, .01f)
            assertEquals(r.y, l.y, .01f)
        }
    }
}
