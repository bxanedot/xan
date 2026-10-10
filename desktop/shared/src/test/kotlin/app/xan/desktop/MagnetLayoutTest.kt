package app.xan.desktop

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetLayoutTest {
    @Test
    fun defaultBottomRightMagnetOpensAnUnclippedUpperSemicircle() {
        val points = magnetSemicircleCenters(width = 1_280f, height = 820f, hubCenter = Offset(1_234f, 774f))

        assertEquals(6, points.size)
        assertTrue(points.all { it.x in 26f..1_254f && it.y in 26f..794f })
        assertEquals(1_234f, points.first().x, .01f)
        assertEquals(1_234f - 2 * 138f, points.last().x, .01f)
        assertEquals(706f, points.first().y, .01f)
        assertTrue(points[2].y < points.first().y)
        assertTrue(points.zipWithNext().all { (a, b) -> kotlin.math.hypot(a.x - b.x, a.y - b.y) > MAGNET_ATTACHMENT_SIZE })
    }

    @Test
    fun magnetAndActionSetMatchAndroidQuickNavigation() {
        assertEquals(listOf("home", "search", "library", "recognize", "shuffle", "settings"), MagnetNavItems.map { it.id })
    }

    @Test
    fun attachmentsStayInsideWindowWhenMagnetIsDockedAtEachCorner() {
        val width = 1_280f
        val height = 820f
        val cornerCenters = listOf(
            Offset(46f, 46f), Offset(width - 46f, 46f),
            Offset(46f, height - 46f), Offset(width - 46f, height - 46f),
        )

        cornerCenters.forEach { hub ->
            val points = magnetSemicircleCenters(width, height, hub)
            assertEquals(6, points.size)
            assertTrue(points.all { it.x in 26f..(width - 26f) && it.y in 26f..(height - 26f) })
            assertTrue("$hub -> $points", points.zipWithNext().all { (a, b) -> kotlin.math.hypot(a.x - b.x, a.y - b.y) > MAGNET_ATTACHMENT_SIZE })
        }
    }

    @Test
    fun stackedAttachmentsStayInsideWindowAtEveryEdgeAndInNarrowWindows() {
        val cases = listOf(
            Triple(1280f, 820f, Offset(46f, 46f)),
            Triple(1280f, 820f, Offset(1234f, 46f)),
            Triple(1280f, 820f, Offset(46f, 774f)),
            Triple(1280f, 820f, Offset(1234f, 774f)),
            Triple(240f, 280f, Offset(194f, 234f)),
        )
        cases.forEach { (width, height, hub) ->
            val points = magnetStackCenters(width, height, hub)
            assertEquals(6, points.size)
            assertTrue("$width x $height -> $points", points.all { it.x in 26f..(width - 26f).coerceAtLeast(26f) && it.y in 26f..(height - 26f).coerceAtLeast(26f) })
            val vertical = points.all { kotlin.math.abs(it.x - points.first().x) < .01f }
            if (vertical) assertTrue(points.zipWithNext().all { (a, b) -> b.y > a.y })
            else assertTrue(points.zipWithNext().all { (a, b) -> b.x > a.x })
        }
    }
}
