package app.xan.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** Source-neutral catalogue metadata. Secrets and playable URLs never belong here. */
internal data class CatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int? = null,
    val artworkUrl: String? = null,
    val source: String = "local",
    val sourceId: String? = null,
    val videoId: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val browseId: String? = null,
    val collectionId: String? = null,
    val kind: String = "song",
    val localPath: String? = null,
    val explicit: Boolean = false,
    val year: Int? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val liked: Boolean = false,
    val metadataVerified: Boolean = true,
)

internal data class CatalogPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val source: String,
    val updatedAt: Long,
    val trackCount: Int = 0,
    val createdAt: Long = updatedAt,
)

internal data class CatalogPlayEvent(
    val track: CatalogTrack,
    val playedAt: Long,
    val listenedMillis: Long,
)

internal data class CatalogHistoryStats(
    val playCount: Long,
    val listenedMillis: Long,
    val uniqueTracks: Long,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
)

internal data class CatalogTrackPlayStats(
    val track: CatalogTrack,
    val playCount: Long,
    val listenedMillis: Long,
)

internal data class YouTubeLibraryEntry(
    val ownerId: String,
    val itemId: String,
    val kind: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val browseId: String? = null,
    val collectionId: String? = null,
    val durationSeconds: Int? = null,
    val position: Int = 0,
    val syncedAt: Long = System.currentTimeMillis(),
)

internal data class YouTubeLibrarySync(
    val ownerId: String,
    val syncedAt: Long,
    val songCount: Int,
    val albumCount: Int,
    val artistCount: Int,
    val playlistCount: Int,
)

internal data class CachedDesktopLyrics(val lyrics: String, val provider: String, val updatedAt: Long)
internal data class CachedLyricVariant(val lines: List<String>, val updatedAt: Long)
internal data class CachedCatalogTrack(
    val track: CatalogTrack,
    val path: String,
    val sizeBytes: Long,
    val cachedAt: Long,
    val lastAccessedAt: Long,
)

/** SQLite-backed XAN catalogue. Schema changes are applied transactionally via PRAGMA user_version. */
internal class CatalogStore(private val databaseFile: Path) {
    private val logger = Logger.getLogger(CatalogStore::class.java.name)

    init {
        Files.createDirectories(databaseFile.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        migrate()
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
    }

    private fun migrate() {
        connection().use { db ->
            var version = db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
            }
            require(version <= SCHEMA_VERSION) { "XAN catalogue schema $version is newer than this version supports." }
            while (version < SCHEMA_VERSION) {
                val next = version + 1
                db.autoCommit = false
                try {
                    migrations.getValue(next).forEach { sql -> db.createStatement().use { it.execute(sql) } }
                    db.createStatement().use { it.execute("PRAGMA user_version = $next") }
                    db.commit()
                    version = next
                } catch (failure: Exception) {
                    runCatching { db.rollback() }
                    logger.log(Level.SEVERE, "Catalogue migration to schema $next failed", failure)
                    throw IllegalStateException("XAN catalogue migration to schema $next failed.", failure)
                } finally {
                    db.autoCommit = true
                }
            }
        }
    }

    fun upsertTracks(tracks: Collection<CatalogTrack>) {
        if (tracks.isEmpty()) return
        connection().use { db ->
            db.autoCommit = false
            try {
                upsertTracks(db, tracks)
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                logger.log(Level.WARNING, "Could not update catalogue tracks", failure)
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    private fun upsertTracks(db: Connection, tracks: Collection<CatalogTrack>) {
        db.prepareStatement(
            """INSERT INTO tracks(
                id,kind,title,artist,album,duration_seconds,artwork_url,source,source_id,video_id,
                artist_id,album_id,browse_id,collection_id,local_path,explicit,year,added_at,liked,metadata_verified
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                kind=excluded.kind,title=excluded.title,artist=excluded.artist,album=excluded.album,
                duration_seconds=excluded.duration_seconds,artwork_url=excluded.artwork_url,
                source=excluded.source,source_id=excluded.source_id,video_id=excluded.video_id,
                artist_id=excluded.artist_id,album_id=excluded.album_id,browse_id=excluded.browse_id,
                collection_id=excluded.collection_id,local_path=excluded.local_path,
                explicit=excluded.explicit,year=excluded.year,metadata_verified=excluded.metadata_verified""",
        ).use { statement ->
            tracks.forEach { track ->
                statement.setString(1, track.id)
                statement.setString(2, track.kind)
                statement.setString(3, track.title)
                statement.setString(4, track.artist)
                statement.setString(5, track.album)
                statement.setObject(6, track.durationSeconds)
                statement.setString(7, track.artworkUrl)
                statement.setString(8, track.source)
                statement.setString(9, track.sourceId)
                statement.setString(10, track.videoId)
                statement.setString(11, track.artistId)
                statement.setString(12, track.albumId)
                statement.setString(13, track.browseId)
                statement.setString(14, track.collectionId)
                statement.setString(15, track.localPath)
                statement.setInt(16, if (track.explicit) 1 else 0)
                statement.setObject(17, track.year)
                statement.setLong(18, track.addedAt)
                statement.setInt(19, if (track.liked) 1 else 0)
                statement.setInt(20, if (track.metadataVerified) 1 else 0)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun search(query: String, limit: Int = 100): List<CatalogTrack> {
        if (query.isBlank()) return emptyList()
        val escaped = query.trim().lowercase().replace("~", "~~").replace("%", "~%").replace("_", "~_")
        return queryTracks(
            """SELECT * FROM tracks WHERE lower(title) LIKE ? ESCAPE '~'
                OR lower(artist) LIKE ? ESCAPE '~' OR lower(album) LIKE ? ESCAPE '~'
                ORDER BY liked DESC, added_at DESC LIMIT ?""",
            listOf("%$escaped%", "%$escaped%", "%$escaped%", limit.coerceIn(1, 1000)),
        )
    }

    fun allLocalTracks(limit: Int = 1000): List<CatalogTrack> = queryTracks(
        "SELECT * FROM tracks WHERE local_path IS NOT NULL ORDER BY added_at DESC, lower(title) LIMIT ?",
        listOf(limit.coerceIn(1, 5000)),
    )

    fun removeTracks(ids: Collection<String>) {
        if (ids.isEmpty()) return
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM playlist_tracks WHERE track_id=?").use { links ->
                    db.prepareStatement("DELETE FROM tracks WHERE id=? AND local_path IS NOT NULL").use { tracks ->
                        ids.forEach { id ->
                            links.setString(1, id); links.addBatch()
                            tracks.setString(1, id); tracks.addBatch()
                        }
                        links.executeBatch()
                        tracks.executeBatch()
                    }
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally { db.autoCommit = true }
        }
    }

    fun likedTracks(limit: Int = 1000): List<CatalogTrack> = queryTracks(
        "SELECT * FROM tracks WHERE liked=1 ORDER BY added_at DESC LIMIT ?",
        listOf(limit.coerceIn(1, 5000)),
    )

    fun setLiked(trackId: String, liked: Boolean) {
        connection().use { db ->
            db.prepareStatement("UPDATE tracks SET liked=? WHERE id=?").use {
                it.setInt(1, if (liked) 1 else 0)
                it.setString(2, trackId)
                check(it.executeUpdate() == 1) { "Track is not in the XAN catalogue." }
            }
        }
    }

    /** Atomically replace one selected YouTube Music channel's cached library. */
    fun replaceYouTubeLibrary(ownerId: String, entries: Collection<YouTubeLibraryEntry>, syncedAt: Long = System.currentTimeMillis()) {
        require(ownerId.isNotBlank()) { "A YouTube Music channel ID is required to sync its library." }
        require(entries.size <= 20_000) { "The YouTube Music library is too large to store." }
        require(entries.all { it.ownerId == ownerId }) { "Library entries must belong to the selected channel." }
        require(entries.all { it.kind in setOf("song", "album", "artist", "playlist") && it.itemId.isNotBlank() }) {
            "The YouTube Music library contains an invalid item."
        }
        val uniqueEntries = entries.distinctBy { it.kind to it.itemId }
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM youtube_library_items WHERE owner_id=?").use {
                    it.setString(1, ownerId)
                    it.executeUpdate()
                }
                db.prepareStatement(
                    """INSERT INTO youtube_library_items(
                        owner_id,item_id,kind,title,artist,album,artwork_url,video_id,artist_id,album_id,
                        browse_id,collection_id,duration_seconds,position,synced_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { statement ->
                    uniqueEntries.forEach { entry ->
                        statement.setString(1, ownerId)
                        statement.setString(2, entry.itemId)
                        statement.setString(3, entry.kind)
                        statement.setString(4, entry.title)
                        statement.setString(5, entry.artist)
                        statement.setString(6, entry.album)
                        statement.setString(7, entry.artworkUrl)
                        statement.setString(8, entry.videoId)
                        statement.setString(9, entry.artistId)
                        statement.setString(10, entry.albumId)
                        statement.setString(11, entry.browseId)
                        statement.setString(12, entry.collectionId)
                        statement.setObject(13, entry.durationSeconds)
                        statement.setInt(14, entry.position)
                        statement.setLong(15, syncedAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                db.prepareStatement(
                    """INSERT INTO youtube_library_syncs(owner_id,synced_at,song_count,album_count,artist_count,playlist_count)
                       VALUES(?,?,?,?,?,?) ON CONFLICT(owner_id) DO UPDATE SET synced_at=excluded.synced_at,
                       song_count=excluded.song_count,album_count=excluded.album_count,
                       artist_count=excluded.artist_count,playlist_count=excluded.playlist_count""",
                ).use { statement ->
                    statement.setString(1, ownerId)
                    statement.setLong(2, syncedAt)
                    statement.setInt(3, uniqueEntries.count { it.kind == "song" })
                    statement.setInt(4, uniqueEntries.count { it.kind == "album" })
                    statement.setInt(5, uniqueEntries.count { it.kind == "artist" })
                    statement.setInt(6, uniqueEntries.count { it.kind == "playlist" })
                    statement.executeUpdate()
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun youtubeLibrary(ownerId: String): List<YouTubeLibraryEntry> = connection().use { db ->
        db.prepareStatement(
            """SELECT owner_id,item_id,kind,title,artist,album,artwork_url,video_id,artist_id,album_id,
                      browse_id,collection_id,duration_seconds,position,synced_at
               FROM youtube_library_items WHERE owner_id=? ORDER BY kind,position,lower(title)""",
        ).use { statement ->
            statement.setString(1, ownerId)
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add(YouTubeLibraryEntry(
                    ownerId = rs.getString("owner_id"), itemId = rs.getString("item_id"), kind = rs.getString("kind"),
                    title = rs.getString("title"), artist = rs.getString("artist"), album = rs.getString("album"),
                    artworkUrl = rs.getString("artwork_url"), videoId = rs.getString("video_id"),
                    artistId = rs.getString("artist_id"), albumId = rs.getString("album_id"), browseId = rs.getString("browse_id"),
                    collectionId = rs.getString("collection_id"), durationSeconds = rs.getInt("duration_seconds").takeUnless { rs.wasNull() },
                    position = rs.getInt("position"), syncedAt = rs.getLong("synced_at"),
                ))
            } }
        }
    }

    /** Remove all account-synced YouTube Music snapshots from this device. */
    fun clearYouTubeLibrarySnapshots() {
        connection().use { db ->
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM youtube_library_items")
                    statement.executeUpdate("DELETE FROM youtube_library_syncs")
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun youtubeLibrarySync(ownerId: String): YouTubeLibrarySync? = connection().use { db ->
        db.prepareStatement("SELECT * FROM youtube_library_syncs WHERE owner_id=?").use { statement ->
            statement.setString(1, ownerId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else YouTubeLibrarySync(
                    ownerId = rs.getString("owner_id"), syncedAt = rs.getLong("synced_at"),
                    songCount = rs.getInt("song_count"), albumCount = rs.getInt("album_count"),
                    artistCount = rs.getInt("artist_count"), playlistCount = rs.getInt("playlist_count"),
                )
            }
        }
    }

    fun createPlaylist(name: String, description: String? = null, source: String = "local"): CatalogPlaylist {
        require(name.isNotBlank()) { "Playlist name cannot be empty." }
        val id = "xan-${UUID.randomUUID()}"
        val updatedAt = System.currentTimeMillis()
        connection().use { db ->
            db.prepareStatement("INSERT INTO playlists(id,name,description,source,created_at,updated_at) VALUES(?,?,?,?,?,?)").use {
                it.setString(1, id)
                it.setString(2, name.trim())
                it.setString(3, description)
                it.setString(4, source)
                it.setLong(5, updatedAt)
                it.setLong(6, updatedAt)
                it.executeUpdate()
            }
        }
        return CatalogPlaylist(id, name.trim(), description, source, updatedAt)
    }

    fun playlists(): List<CatalogPlaylist> = connection().use { db ->
        db.prepareStatement("""SELECT p.id,p.name,p.description,p.source,p.created_at,p.updated_at,
            (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlist_id=p.id) AS track_count
            FROM playlists p ORDER BY p.updated_at DESC""").use { statement ->
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPlaylist()) } }
        }
    }

    fun youtubePlaylistId(playlistId: String, ownerId: String): String? = connection().use { db ->
        db.prepareStatement("SELECT remote_id FROM youtube_playlist_links WHERE local_id=? AND owner_id=?").use {
            it.setString(1, playlistId)
            it.setString(2, ownerId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString("remote_id") else null }
        }
    }

    fun linkYoutubePlaylist(playlistId: String, ownerId: String, remoteId: String, syncedAt: Long = System.currentTimeMillis()) {
        require(playlistId.isNotBlank() && ownerId.isNotBlank() && remoteId.isNotBlank())
        connection().use { db -> db.prepareStatement("""INSERT INTO youtube_playlist_links(local_id,owner_id,remote_id,synced_at)
            VALUES(?,?,?,?) ON CONFLICT(local_id,owner_id) DO UPDATE SET remote_id=excluded.remote_id,synced_at=excluded.synced_at""").use {
            it.setString(1, playlistId)
            it.setString(2, ownerId)
            it.setString(3, remoteId)
            it.setLong(4, syncedAt)
            it.executeUpdate()
        } }
    }

    fun updatePlaylistMetadata(playlistId: String, name: String, description: String?) {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "Playlist name cannot be empty." }
        val safeDescription = description?.trim()?.takeIf(String::isNotEmpty)
        connection().use { db ->
            db.prepareStatement("UPDATE playlists SET name=?,description=?,updated_at=? WHERE id=?").use {
                it.setString(1, safeName)
                it.setString(2, safeDescription)
                it.setLong(3, System.currentTimeMillis())
                it.setString(4, playlistId)
                check(it.executeUpdate() == 1) { "Playlist does not exist." }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        connection().use { db -> db.prepareStatement("DELETE FROM playlists WHERE id=?").use {
            it.setString(1, playlistId)
            check(it.executeUpdate() == 1) { "Playlist does not exist." }
        } }
    }

    fun replacePlaylistTracks(playlistId: String, tracks: List<CatalogTrack>) {
        connection().use { db ->
            db.autoCommit = false
            try {
                upsertTracks(db, tracks)
                db.prepareStatement("DELETE FROM playlist_tracks WHERE playlist_id=?").use {
                    it.setString(1, playlistId)
                    it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO playlist_tracks(playlist_id,track_id,position,added_at) VALUES(?,?,?,?)").use { statement ->
                    val addedAt = System.currentTimeMillis()
                    tracks.forEachIndexed { index, track ->
                        statement.setString(1, playlistId)
                        statement.setString(2, track.id)
                        statement.setInt(3, index)
                        statement.setLong(4, addedAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                db.prepareStatement("UPDATE playlists SET updated_at=? WHERE id=?").use {
                    it.setLong(1, System.currentTimeMillis())
                    it.setString(2, playlistId)
                    check(it.executeUpdate() == 1) { "Playlist does not exist." }
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                logger.log(Level.WARNING, "Could not update playlist tracks", failure)
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun playlistTracks(playlistId: String): List<CatalogTrack> = queryTracks(
        """SELECT t.* FROM playlist_tracks p JOIN tracks t ON t.id=p.track_id
           WHERE p.playlist_id=? ORDER BY p.position""",
        listOf(playlistId),
    )

    /** Add one track without replacing the rest of the playlist. Duplicate additions are ignored. */
    fun addPlaylistTrack(playlistId: String, track: CatalogTrack): Boolean = connection().use { db ->
        db.autoCommit = false
        try {
            upsertTracks(db, listOf(track))
            val exists = db.prepareStatement("SELECT 1 FROM playlists WHERE id=?").use {
                it.setString(1, playlistId)
                it.executeQuery().use(ResultSet::next)
            }
            check(exists) { "Playlist does not exist." }
            val alreadyAdded = db.prepareStatement("SELECT 1 FROM playlist_tracks WHERE playlist_id=? AND track_id=?").use {
                it.setString(1, playlistId)
                it.setString(2, track.id)
                it.executeQuery().use(ResultSet::next)
            }
            if (alreadyAdded) {
                db.commit()
                return@use false
            }
            val nextPosition = db.prepareStatement("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?").use {
                it.setString(1, playlistId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
            db.prepareStatement("INSERT INTO playlist_tracks(playlist_id,track_id,position,added_at) VALUES(?,?,?,?)").use {
                it.setString(1, playlistId)
                it.setString(2, track.id)
                it.setInt(3, nextPosition)
                it.setLong(4, System.currentTimeMillis())
                it.executeUpdate()
            }
            db.prepareStatement("UPDATE playlists SET updated_at=? WHERE id=?").use {
                it.setLong(1, System.currentTimeMillis())
                it.setString(2, playlistId)
                check(it.executeUpdate() == 1) { "Playlist does not exist." }
            }
            db.commit()
            true
        } catch (failure: Exception) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    fun removePlaylistTrack(playlistId: String, trackId: String): Boolean = connection().use { db ->
        db.autoCommit = false
        try {
            val position = db.prepareStatement("SELECT position FROM playlist_tracks WHERE playlist_id=? AND track_id=?").use {
                it.setString(1, playlistId)
                it.setString(2, trackId)
                it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
            } ?: run { db.commit(); return@use false }
            db.prepareStatement("DELETE FROM playlist_tracks WHERE playlist_id=? AND track_id=?").use {
                it.setString(1, playlistId)
                it.setString(2, trackId)
                check(it.executeUpdate() == 1)
            }
            db.prepareStatement("UPDATE playlist_tracks SET position=position-1 WHERE playlist_id=? AND position>?").use {
                it.setString(1, playlistId)
                it.setInt(2, position)
                it.executeUpdate()
            }
            db.prepareStatement("UPDATE playlists SET updated_at=? WHERE id=?").use {
                it.setLong(1, System.currentTimeMillis())
                it.setString(2, playlistId)
                it.executeUpdate()
            }
            db.commit()
            true
        } catch (failure: Exception) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    fun movePlaylistTrack(playlistId: String, from: Int, to: Int): Boolean = connection().use { db ->
        db.autoCommit = false
        try {
            val ids = db.prepareStatement("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position").use {
                it.setString(1, playlistId)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            if (from !in ids.indices || to !in ids.indices || from == to) {
                db.commit()
                return@use false
            }
            val ordered = ids.toMutableList().apply { add(to, removeAt(from)) }
            db.prepareStatement("UPDATE playlist_tracks SET position=? WHERE playlist_id=? AND track_id=?").use { update ->
                ordered.forEachIndexed { index, id ->
                    update.setInt(1, index)
                    update.setString(2, playlistId)
                    update.setString(3, id)
                    update.addBatch()
                }
                update.executeBatch()
            }
            db.prepareStatement("UPDATE playlists SET updated_at=? WHERE id=?").use {
                it.setLong(1, System.currentTimeMillis())
                it.setString(2, playlistId)
                it.executeUpdate()
            }
            db.commit()
            true
        } catch (failure: Exception) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    fun recordPlay(trackId: String, playedAt: Long, listenedMillis: Long) {
        require(listenedMillis >= 0)
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO history(track_id,played_at,listened_ms) VALUES(?,?,?)").use {
                    it.setString(1, trackId)
                    it.setLong(2, playedAt)
                    it.setLong(3, listenedMillis)
                    it.executeUpdate()
                }
                db.prepareStatement("UPDATE tracks SET last_played_at=? WHERE id=?").use {
                    it.setLong(1, playedAt)
                    it.setString(2, trackId)
                    it.executeUpdate()
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                logger.log(Level.WARNING, "Could not record playback history", failure)
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun recentHistory(limit: Int = 100): List<CatalogPlayEvent> = connection().use { db ->
        db.prepareStatement(
            """SELECT t.*,h.played_at,h.listened_ms FROM history h JOIN tracks t ON t.id=h.track_id
               ORDER BY h.played_at DESC LIMIT ?""",
        ).use { statement ->
            statement.setInt(1, limit.coerceIn(1, 1000))
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add(CatalogPlayEvent(rs.toTrack(), rs.getLong("played_at"), rs.getLong("listened_ms")))
            } }
        }
    }

    fun topPlayedTracks(limit: Int = 50): List<CatalogTrack> = queryTracks(
        """SELECT t.* FROM tracks t JOIN history h ON h.track_id=t.id
           GROUP BY t.id ORDER BY SUM(h.listened_ms) DESC, COUNT(h.track_id) DESC, MAX(h.played_at) DESC LIMIT ?""",
        listOf(limit.coerceIn(1, 500)),
    )

    fun historyStats(sinceMillis: Long? = null): CatalogHistoryStats = connection().use { db ->
        db.prepareStatement("SELECT COUNT(*) AS play_count,COALESCE(SUM(listened_ms),0) AS listened_ms,COUNT(DISTINCT track_id) AS unique_tracks,MIN(played_at) AS first_played_at,MAX(played_at) AS last_played_at FROM history${if (sinceMillis == null) "" else " WHERE played_at>=?"}").use { statement ->
            if (sinceMillis != null) statement.setLong(1, sinceMillis)
            statement.executeQuery().use { rs ->
                rs.next()
                CatalogHistoryStats(
                    playCount = rs.getLong("play_count"),
                    listenedMillis = rs.getLong("listened_ms"),
                    uniqueTracks = rs.getLong("unique_tracks"),
                    firstPlayedAt = rs.getLong("first_played_at").takeUnless { rs.wasNull() },
                    lastPlayedAt = rs.getLong("last_played_at").takeUnless { rs.wasNull() },
                )
            }
        }
    }

    fun topPlayedTrackStats(limit: Int = 10, sinceMillis: Long? = null): List<CatalogTrackPlayStats> = connection().use { db ->
        db.prepareStatement(
            """SELECT t.*,COUNT(h.track_id) AS history_play_count,COALESCE(SUM(h.listened_ms),0) AS history_listened_ms
               FROM tracks t JOIN history h ON h.track_id=t.id
               ${if (sinceMillis == null) "" else "WHERE h.played_at>=?"}
               GROUP BY t.id ORDER BY SUM(h.listened_ms) DESC,COUNT(h.track_id) DESC,MAX(h.played_at) DESC LIMIT ?""",
        ).use { statement ->
            if (sinceMillis != null) statement.setLong(1, sinceMillis)
            statement.setInt(if (sinceMillis != null) 2 else 1, limit.coerceIn(1, 100))
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add(CatalogTrackPlayStats(rs.toTrack(), rs.getLong("history_play_count"), rs.getLong("history_listened_ms")))
            } }
        }
    }

    fun historyCount(): Long = connection().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM history").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun clearHistory() {
        connection().use { db ->
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM history")
                    statement.executeUpdate("UPDATE tracks SET last_played_at=NULL")
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun recordSearch(query: String, searchedAt: Long = System.currentTimeMillis()) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO search_history(query,searched_at) VALUES(?,?)").use {
                    it.setString(1, normalized)
                    it.setLong(2, searchedAt)
                    it.executeUpdate()
                }
                db.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY searched_at DESC,id DESC LIMIT 200)")
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                logger.log(Level.WARNING, "Could not record search history", failure)
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun recentSearches(limit: Int = 12): List<String> = connection().use { db ->
        db.prepareStatement("SELECT query FROM search_history GROUP BY query COLLATE NOCASE ORDER BY MAX(searched_at) DESC LIMIT ?").use {
            it.setInt(1, limit.coerceIn(1, 100))
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("query")) } }
        }
    }

    fun searchHistoryCount(): Long = connection().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM search_history").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun clearSearchHistory() {
        connection().use { it.createStatement().use { statement -> statement.executeUpdate("DELETE FROM search_history") } }
    }

    fun cachedLyrics(trackKey: String): CachedDesktopLyrics? = connection().use { db ->
        db.prepareStatement("SELECT lyrics,provider,updated_at FROM lyrics_cache WHERE track_key=?").use {
            it.setString(1, trackKey)
            it.executeQuery().use { rs -> if (rs.next()) CachedDesktopLyrics(rs.getString("lyrics"), rs.getString("provider"), rs.getLong("updated_at")) else null }
        }
    }

    fun cacheLyrics(trackKey: String, lyrics: String, provider: String, updatedAt: Long = System.currentTimeMillis()) {
        require(trackKey.isNotBlank() && lyrics.isNotBlank() && provider.isNotBlank())
        connection().use { db -> db.prepareStatement(
            "INSERT INTO lyrics_cache(track_key,lyrics,provider,updated_at) VALUES(?,?,?,?) ON CONFLICT(track_key) DO UPDATE SET lyrics=excluded.lyrics,provider=excluded.provider,updated_at=excluded.updated_at",
        ).use {
            it.setString(1, trackKey); it.setString(2, lyrics); it.setString(3, provider); it.setLong(4, updatedAt); it.executeUpdate()
        } }
    }

    fun deleteCachedLyrics(trackKey: String) {
        connection().use { db -> db.prepareStatement("DELETE FROM lyrics_cache WHERE track_key=?").use { it.setString(1, trackKey); it.executeUpdate() } }
    }

    fun clearLyricsCache() {
        connection().use { db ->
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM lyrics_cache")
                    statement.executeUpdate("DELETE FROM lyrics_variants")
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun lyricsCacheSizeBytes(): Long = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery(
            "SELECT (SELECT COALESCE(SUM(length(lyrics)),0) FROM lyrics_cache) + (SELECT COALESCE(SUM(length(line)),0) FROM lyrics_variants)",
        ).use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun cachedLyricVariant(trackKey: String, kind: String, target: String, sourceHash: String): CachedLyricVariant? = connection().use { db ->
        db.prepareStatement("SELECT line,updated_at FROM lyrics_variants WHERE track_key=? AND kind=? AND target=? AND source_hash=? ORDER BY position").use { statement ->
            statement.setString(1, trackKey)
            statement.setString(2, kind)
            statement.setString(3, target)
            statement.setString(4, sourceHash)
            statement.executeQuery().use { rs ->
                val lines = mutableListOf<String>()
                var updatedAt: Long? = null
                while (rs.next()) {
                    lines += rs.getString("line")
                    updatedAt = rs.getLong("updated_at")
                }
                if (updatedAt == null) null else CachedLyricVariant(lines, updatedAt)
            }
        }
    }

    fun cacheLyricVariant(trackKey: String, kind: String, target: String, sourceHash: String, lines: List<String>, updatedAt: Long = System.currentTimeMillis()) {
        require(trackKey.isNotBlank() && kind in setOf("translation", "romanization") && sourceHash.isNotBlank() && lines.isNotEmpty())
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("DELETE FROM lyrics_variants WHERE track_key=? AND kind=? AND target=? AND source_hash=?").use {
                    it.setString(1, trackKey); it.setString(2, kind); it.setString(3, target); it.setString(4, sourceHash); it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO lyrics_variants(track_key,kind,target,source_hash,position,line,updated_at) VALUES(?,?,?,?,?,?,?)").use { statement ->
                    lines.forEachIndexed { index, line ->
                        statement.setString(1, trackKey)
                        statement.setString(2, kind)
                        statement.setString(3, target)
                        statement.setString(4, sourceHash)
                        statement.setInt(5, index)
                        statement.setString(6, line)
                        statement.setLong(7, updatedAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun recordCachedTrack(track: CatalogTrack, path: String, sizeBytes: Long, cachedAt: Long = System.currentTimeMillis()) {
        require(sizeBytes > 0)
        connection().use { db ->
            db.autoCommit = false
            try {
                upsertTracks(db, listOf(track))
                db.prepareStatement("""INSERT INTO cached_tracks(track_id,path,size_bytes,cached_at,last_accessed_at) VALUES(?,?,?,?,?)
                    ON CONFLICT(track_id) DO UPDATE SET path=excluded.path,size_bytes=excluded.size_bytes,
                    cached_at=excluded.cached_at,last_accessed_at=excluded.last_accessed_at""").use {
                    it.setString(1, track.id)
                    it.setString(2, path)
                    it.setLong(3, sizeBytes)
                    it.setLong(4, cachedAt)
                    it.setLong(5, cachedAt)
                    it.executeUpdate()
                }
                db.commit()
            } catch (failure: Exception) {
                runCatching { db.rollback() }
                throw failure
            } finally { db.autoCommit = true }
        }
    }

    fun cachedTracks(): List<CachedCatalogTrack> = connection().use { db ->
        db.prepareStatement("""SELECT t.*,c.path,c.size_bytes,c.cached_at,c.last_accessed_at
            FROM cached_tracks c JOIN tracks t ON t.id=c.track_id ORDER BY c.cached_at DESC""").use { statement ->
            statement.executeQuery().use { rs -> buildList {
                while (rs.next()) add(CachedCatalogTrack(rs.toTrack(), rs.getString("path"), rs.getLong("size_bytes"), rs.getLong("cached_at"), rs.getLong("last_accessed_at")))
            } }
        }
    }

    fun touchCachedTrack(trackId: String, accessedAt: Long = System.currentTimeMillis()): CachedCatalogTrack? = connection().use { db ->
        val cached = db.prepareStatement("""SELECT t.*,c.path,c.size_bytes,c.cached_at,c.last_accessed_at
            FROM cached_tracks c JOIN tracks t ON t.id=c.track_id WHERE c.track_id=?""").use {
            it.setString(1, trackId)
            it.executeQuery().use { rs -> if (rs.next()) CachedCatalogTrack(rs.toTrack(), rs.getString("path"), rs.getLong("size_bytes"), rs.getLong("cached_at"), rs.getLong("last_accessed_at")) else null }
        } ?: return@use null
        db.prepareStatement("UPDATE cached_tracks SET last_accessed_at=? WHERE track_id=?").use {
            it.setLong(1, accessedAt); it.setString(2, trackId); it.executeUpdate()
        }
        cached.copy(lastAccessedAt = accessedAt)
    }

    fun forgetCachedTrack(trackId: String): CachedCatalogTrack? = connection().use { db ->
        val cached = db.prepareStatement("""SELECT t.*,c.path,c.size_bytes,c.cached_at,c.last_accessed_at
            FROM cached_tracks c JOIN tracks t ON t.id=c.track_id WHERE c.track_id=?""").use {
            it.setString(1, trackId)
            it.executeQuery().use { rs -> if (rs.next()) CachedCatalogTrack(rs.toTrack(), rs.getString("path"), rs.getLong("size_bytes"), rs.getLong("cached_at"), rs.getLong("last_accessed_at")) else null }
        } ?: return@use null
        db.prepareStatement("DELETE FROM cached_tracks WHERE track_id=?").use { it.setString(1, trackId); it.executeUpdate() }
        cached
    }

    fun addMusicFolder(path: Path) {
        val canonical = path.toAbsolutePath().normalize()
        require(Files.isDirectory(canonical)) { "Choose an existing music folder." }
        connection().use { db ->
            db.prepareStatement("INSERT INTO library_roots(path,added_at) VALUES(?,?) ON CONFLICT(path) DO NOTHING").use {
                it.setString(1, canonical.toString())
                it.setLong(2, System.currentTimeMillis())
                it.executeUpdate()
            }
        }
    }

    fun removeMusicFolder(path: Path) {
        connection().use { db -> db.prepareStatement("DELETE FROM library_roots WHERE path=?").use {
            it.setString(1, path.toAbsolutePath().normalize().toString())
            it.executeUpdate()
        } }
    }

    fun musicFolders(): List<Path> = connection().use { db ->
        db.createStatement().use { statement -> statement.executeQuery("SELECT path FROM library_roots ORDER BY added_at").use { rs ->
            buildList { while (rs.next()) add(Path.of(rs.getString(1))) }
        } }
    }

    fun getSetting(key: String): String? = connection().use { db ->
        db.prepareStatement("SELECT value FROM settings WHERE key=?").use {
            it.setString(1, key)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun setSetting(key: String, value: String) {
        connection().use { db -> db.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        } }
    }

    fun schemaVersion(): Int = connection().use { db ->
        db.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
    }

    fun verifyIntegrity(): Boolean = connection().use { db ->
        db.createStatement().use { statement ->
            statement.executeQuery("PRAGMA integrity_check").use { rs -> rs.next() && rs.getString(1) == "ok" }
        }
    }

    private fun queryTracks(sql: String, args: List<Any>): List<CatalogTrack> = connection().use { db ->
        db.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTrack()) } }
        }
    }

    private fun ResultSet.toTrack() = CatalogTrack(
        id = getString("id"),
        kind = getString("kind"),
        title = getString("title"),
        artist = getString("artist"),
        album = getString("album"),
        durationSeconds = getInt("duration_seconds").takeUnless { wasNull() },
        artworkUrl = getString("artwork_url"),
        source = getString("source"),
        sourceId = getString("source_id"),
        videoId = getString("video_id"),
        artistId = getString("artist_id"),
        albumId = getString("album_id"),
        browseId = getString("browse_id"),
        collectionId = getString("collection_id"),
        localPath = getString("local_path"),
        explicit = getInt("explicit") != 0,
        year = getInt("year").takeUnless { wasNull() },
        addedAt = getLong("added_at"),
        liked = getInt("liked") != 0,
        metadataVerified = getInt("metadata_verified") != 0,
    )

    private fun ResultSet.toPlaylist() = CatalogPlaylist(
        getString("id"), getString("name"), getString("description"), getString("source"), getLong("updated_at"), getInt("track_count"), getLong("created_at"),
    )

    companion object {
        private const val SCHEMA_VERSION = 10
        private val migrations = mapOf(
            1 to listOf(
                """CREATE TABLE tracks(
                    id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL DEFAULT 'song',title TEXT NOT NULL,
                    artist TEXT NOT NULL DEFAULT '',album TEXT NOT NULL DEFAULT '',duration_seconds INTEGER,
                    artwork_url TEXT,source TEXT NOT NULL,source_id TEXT,video_id TEXT,artist_id TEXT,
                    album_id TEXT,browse_id TEXT,collection_id TEXT,local_path TEXT,explicit INTEGER NOT NULL DEFAULT 0,
                    year INTEGER,added_at INTEGER NOT NULL,liked INTEGER NOT NULL DEFAULT 0
                )""",
                """CREATE TABLE playlists(
                    id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,description TEXT,source TEXT NOT NULL DEFAULT 'local',
                    created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL
                )""",
                """CREATE TABLE playlist_tracks(
                    playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                    track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,added_at INTEGER NOT NULL,
                    PRIMARY KEY(playlist_id,track_id)
                )""",
                """CREATE TABLE history(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,track_id TEXT NOT NULL,played_at INTEGER NOT NULL,listened_ms INTEGER NOT NULL
                )""",
                "CREATE TABLE settings(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
            ),
            2 to listOf(
                "CREATE INDEX tracks_title_idx ON tracks(lower(title))",
                "CREATE INDEX tracks_artist_idx ON tracks(lower(artist))",
                "CREATE INDEX tracks_source_idx ON tracks(source,source_id)",
                "CREATE INDEX tracks_local_idx ON tracks(local_path)",
                "CREATE INDEX history_played_at_idx ON history(played_at DESC)",
                """CREATE TABLE library_roots(
                    path TEXT PRIMARY KEY NOT NULL,added_at INTEGER NOT NULL,last_scanned_at INTEGER
                )""",
            ),
            3 to listOf("ALTER TABLE tracks ADD COLUMN last_played_at INTEGER"),
            4 to listOf("ALTER TABLE tracks ADD COLUMN metadata_verified INTEGER NOT NULL DEFAULT 1"),
            5 to listOf(
                "CREATE TABLE search_history(id INTEGER PRIMARY KEY AUTOINCREMENT,query TEXT NOT NULL,searched_at INTEGER NOT NULL)",
                "CREATE INDEX search_history_time_idx ON search_history(searched_at DESC)",
            ),
            6 to listOf("CREATE TABLE lyrics_cache(track_key TEXT PRIMARY KEY NOT NULL,lyrics TEXT NOT NULL,provider TEXT NOT NULL,updated_at INTEGER NOT NULL)"),
            7 to listOf("""CREATE TABLE cached_tracks(
                track_id TEXT PRIMARY KEY NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
                path TEXT NOT NULL UNIQUE,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),
                cached_at INTEGER NOT NULL,last_accessed_at INTEGER NOT NULL
            )"""),
            8 to listOf("""CREATE TABLE youtube_library_items(
                owner_id TEXT NOT NULL,item_id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '',album TEXT NOT NULL DEFAULT '',artwork_url TEXT,video_id TEXT,
                artist_id TEXT,album_id TEXT,browse_id TEXT,collection_id TEXT,duration_seconds INTEGER,
                position INTEGER NOT NULL DEFAULT 0,synced_at INTEGER NOT NULL,
                PRIMARY KEY(owner_id,kind,item_id)
            )""", "CREATE INDEX youtube_library_owner_kind_idx ON youtube_library_items(owner_id,kind,position)", """CREATE TABLE youtube_library_syncs(
                owner_id TEXT PRIMARY KEY NOT NULL,synced_at INTEGER NOT NULL,
                song_count INTEGER NOT NULL,album_count INTEGER NOT NULL,artist_count INTEGER NOT NULL,playlist_count INTEGER NOT NULL
            )"""),
            9 to listOf("""CREATE TABLE lyrics_variants(
                track_key TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('translation','romanization')),
                target TEXT NOT NULL,source_hash TEXT NOT NULL,position INTEGER NOT NULL,line TEXT NOT NULL,
                updated_at INTEGER NOT NULL,PRIMARY KEY(track_key,kind,target,source_hash,position)
            )""", "CREATE INDEX lyrics_variants_lookup_idx ON lyrics_variants(track_key,kind,target,source_hash)"),
            10 to listOf("""CREATE TABLE youtube_playlist_links(
                local_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,owner_id TEXT NOT NULL,
                remote_id TEXT NOT NULL,synced_at INTEGER NOT NULL,PRIMARY KEY(local_id,owner_id)
            )""", "CREATE UNIQUE INDEX youtube_playlist_links_remote_idx ON youtube_playlist_links(owner_id,remote_id)"),
        )
    }
}
