/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */
package app.xan.music.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class MostPlayedSongsStatsQueryTest {
    private fun querySql(): String =
        MOST_PLAYED_SONGS_STATS_QUERY
            .replace(":fromTimeStamp", "0")
            .replace(":toTimeStamp", "1000")
            .replace(":limit", "10")
            .replace(":offset", "0")

    private fun withDatabase(block: (java.sql.Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE song (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        thumbnailUrl TEXT,
                        isVideo INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE artist (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE song_artist_map (
                        songId TEXT NOT NULL,
                        artistId TEXT NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE event (
                        songId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        playTime INTEGER
                    )
                    """.trimIndent(),
                )
            }
            block(db)
        }
    }

    @Test
    fun legacyNullMetadataAndAggregateAreCoalesced() = withDatabase { db ->
        db.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO song(id, title, thumbnailUrl, isVideo) VALUES ('legacy', 'Legacy Song', NULL, 0)",
            )
            // Legacy/corrupt rows can contain a null aggregate input even if the current
            // entity schema no longer permits it. SUM(NULL) would otherwise map as SQL NULL.
            statement.executeUpdate(
                "INSERT INTO event(songId, timestamp, playTime) VALUES ('legacy', 100, NULL)",
            )

            statement.executeQuery(querySql()).use { rows ->
                assertTrue(rows.next())
                assertEquals("legacy", rows.getString("id"))
                assertEquals("", rows.getString("thumbnailUrl"))
                assertEquals(0L, rows.getLong("timeListened"))
                assertFalse(rows.wasNull())
                assertFalse(rows.next())
            }
        }
    }

    @Test
    fun emptyListeningHistoryReturnsNoRows() = withDatabase { db ->
        db.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO song(id, title, thumbnailUrl, isVideo) VALUES ('idle', 'Never Played', NULL, 0)",
            )
            statement.executeQuery(querySql()).use { rows ->
                assertFalse(rows.next())
            }
        }
    }
}
