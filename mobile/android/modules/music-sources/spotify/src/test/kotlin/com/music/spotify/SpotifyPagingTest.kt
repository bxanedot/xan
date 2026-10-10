package com.music.spotify

import com.music.spotify.models.SpotifyPaging
import com.music.spotify.models.nextOffsetOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPagingTest {
    @Test
    fun filteredPageStillAdvancesByRawItemCount() {
        val page = SpotifyPaging<String>(
            items = emptyList(),
            total = 100,
            limit = 50,
            offset = 0,
            rawItemCount = 50,
        )

        assertEquals(50, page.nextOffsetOrNull(currentOffset = 0, requestedLimit = 50))
    }

    @Test
    fun emptyRawPageStopsPagination() {
        val page = SpotifyPaging<String>(items = emptyList(), total = 100, rawItemCount = 0)

        assertNull(page.nextOffsetOrNull(currentOffset = 50, requestedLimit = 50))
    }

    @Test
    fun lastPageStopsAtReportedTotal() {
        val page = SpotifyPaging(items = listOf("last"), total = 51, rawItemCount = 1)

        assertNull(page.nextOffsetOrNull(currentOffset = 50, requestedLimit = 50))
    }
}
