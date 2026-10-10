package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCachePolicyTest {
    @Test
    fun evictsLeastRecentlyUsedEntriesUntilIncomingTrackFits() {
        val entries = listOf(
            cached("oldest", 20, 40),
            cached("newer", 20, 50),
            cached("newest", 20, 60),
        )

        assertEquals(
            listOf("oldest", "newer"),
            audioCacheEvictions(entries, limitBytes = 60, incomingBytes = 30).map { it.track.id },
        )
    }

    @Test
    fun keepsCacheWhenIncomingFileAlreadyFits() {
        assertEquals(emptyList<CachedCatalogTrack>(), audioCacheEvictions(listOf(cached("one", 20, 10)), 100, 50))
    }

    private fun cached(id: String, size: Long, lastAccessed: Long) = CachedCatalogTrack(
        track = CatalogTrack(id = id, title = id, artist = "Artist", album = "Album"),
        path = "$id.m4a",
        sizeBytes = size,
        cachedAt = lastAccessed,
        lastAccessedAt = lastAccessed,
    )
}
