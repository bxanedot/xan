package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleChartsClientTest {
    @Test
    fun parsesChartRanksAndResizesArtwork() {
        val result = AppleChartsClient.parseChart(
            """{"feed":{"results":[{"name":"Song A","artistName":"Artist A","artworkUrl100":"https://img.example/100x100bb.jpg","url":"https://music.apple.com/song/a"},{"name":"Song B","artistName":"Artist B"}]}}""",
        )

        assertEquals(listOf(1, 2), result.map { it.rank })
        assertEquals("Song A", result.first().title)
        assertEquals("https://img.example/800x800bb.jpg", result.first().artworkUrl)
        assertEquals(null, result.last().artworkUrl)
    }

    @Test
    fun invalidChartPayloadIsReported() {
        assertThrows(IllegalStateException::class.java) {
            AppleChartsClient.parseChart("""{"feed":{}}""")
        }
    }
}
