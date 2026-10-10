package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecognitionHistoryStoreTest {
    @Test
    fun historyPersistsAcrossCatalogueReopenAndCanBeCleared() {
        val directory = Files.createTempDirectory("xan-recognition-history-")
        val file = directory.resolve("catalogue.sqlite3")
        try {
            val firstCatalogue = CatalogStore(file)
            val first = RecognitionHistoryStore(
                readValue = { firstCatalogue.getSetting("recognition_history") },
                writeValue = { firstCatalogue.setSetting("recognition_history", it) },
            )
            val older = RecognitionHistoryEntry("track-1", "A title | with delimiter", "Artist", "Album", "https://img.example/a", "https://www.shazam.com/track/1", 10L)
            val newer = RecognitionHistoryEntry("track-1", "A title | with delimiter", "Artist", "Album", "https://img.example/a", "https://www.shazam.com/track/1", 20L)
            first.record(older)
            first.record(newer)

            val reopenedCatalogue = CatalogStore(file)
            val reopened = RecognitionHistoryStore(
                readValue = { reopenedCatalogue.getSetting("recognition_history") },
                writeValue = { reopenedCatalogue.setSetting("recognition_history", it) },
            )
            assertEquals(listOf(newer, older), reopened.entries())
            reopened.clear()
            assertTrue(reopened.entries().isEmpty())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
