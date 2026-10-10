package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Properties
import com.music.spotify.SpotifyPublicTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import com.music.innertube.models.response.PlayerResponse
import kotlinx.coroutines.runBlocking

class DesktopBackendTest {
    @Test
    fun directInnerTubeAacUrlSkipsSlowPageExtraction() {
        val best = audioFormat(url = "https://audio.example/high.m4a", bitrate = 192_000)
        val slower = audioFormat(url = "https://audio.example/low.m4a", bitrate = 96_000)
        var fallbackCalls = 0

        val selected = runBlocking {
            selectDesktopPlayableAudioFormat(listOf(slower, best)) {
                fallbackCalls++
                error("NewPipe extraction should not run when a direct stream URL is available")
            }
        }

        assertEquals(best, selected)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun cipherOnlyInnerTubeResponseUsesTheNewPipeFallback() {
        val cipherOnly = audioFormat(url = null, bitrate = 192_000)
        val extracted = audioFormat(url = "https://audio.example/extracted.m4a", bitrate = 128_000)
        var fallbackCalls = 0

        val selected = runBlocking {
            selectDesktopPlayableAudioFormat(listOf(cipherOnly)) {
                fallbackCalls++
                listOf(extracted)
            }
        }

        assertEquals(extracted, selected)
        assertEquals(1, fallbackCalls)
    }

    private fun audioFormat(url: String?, bitrate: Int) = PlayerResponse.StreamingData.Format(
        itag = bitrate,
        url = url,
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = null,
        quality = "AUDIO_QUALITY_MEDIUM",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        cipher = null,
        audioTrack = null,
    )

    @Test
    fun androidTypefaceChoicesPersistAndApplyToSharedTypography() {
        val original = DesktopBackend.fontFamily()
        try {
            assertEquals(listOf("system", "google_sans", "sans_flex", "outfit", "plus_jakarta"), XanFonts.options.map { it.id })
            XanFonts.options.forEach { option ->
                XanFonts.select(option.id)
                assertEquals(option.id, DesktopBackend.fontFamily())
                assertEquals(option.id, XanFonts.selected)
            }
        } finally {
            XanFonts.select(original)
        }
    }

    @Test
    fun playlistImportsParseM3uAndCsvMetadataAndResolvePaths() {
        val directory = Files.createTempDirectory("xan-playlist-import-")
        try {
            val audio = directory.resolve("song.mp3")
            Files.write(audio, byteArrayOf(1, 2, 3))
            val m3u = PlaylistImport.parseM3u("#EXTM3U\n#EXTINF:215,Example Artist - Example Song\nsong.mp3\nmissing.mp3\nhttps://music.youtube.com/watch?v=abc")
            assertEquals(3, m3u.size)
            assertEquals("Example Artist", m3u.first().artist)
            assertEquals("Example Song", m3u.first().title)
            assertEquals(215, m3u.first().durationSeconds)
            assertEquals(audio.toAbsolutePath().normalize(), PlaylistImport.resolveLocal(m3u.first(), directory))
            assertEquals(null, PlaylistImport.resolveLocal(m3u[1], directory))
            assertEquals(null, PlaylistImport.resolveLocal(m3u[2], directory))

            val csv = PlaylistImport.parseCsv("Title,Artist,Album,URI,Duration,ISRC\n\"Song, The\",An Artist,\"Live, 2025\",relative.mp3,3:45,USABC1234567")
            assertEquals(1, csv.size)
            assertEquals("Song, The", csv.single().title)
            assertEquals("An Artist", csv.single().artist)
            assertEquals("Live, 2025", csv.single().album)
            assertEquals("relative.mp3", csv.single().location)
            assertEquals(225, csv.single().durationSeconds)
            assertEquals("USABC1234567", csv.single().isrc)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun csvImportMatchesCatalogTracksAndReportsUnresolvedRows() {
        val directory = Files.createTempDirectory("xan-csv-import-flow-")
        val audio = directory.resolve("Test Artist - Test Song.mp3")
        val csv = directory.resolve("migration.csv")
        Files.write(audio, byteArrayOf(1, 2, 3))
        Files.writeString(csv, "title,artist,album\nTest Song,Test Artist,Local Album\nMissing Track,Unknown Artist,\n")
        var playlistId: String? = null
        try {
            DesktopBackend.addMusicFolder(directory)
            assertTrue(DesktopBackend.scanMusicLibrary() >= 1)
            val report = DesktopBackend.importPlaylistFile(csv)
            val playlist = DesktopBackend.playlists().single { it.name == "migration" }
            playlistId = playlist.id
            assertEquals(1, report.imported.size)
            assertEquals("Test Song", report.imported.single().title)
            assertEquals(1, report.unresolved.size)
            assertEquals("Test Song", DesktopBackend.playlistTracks(playlist.id).single().title)
        } finally {
            playlistId?.let { DesktopBackend.deletePlaylist(it) }
            DesktopBackend.removeMusicFolder(directory)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun playlistCsvParserRejectsUnclosedQuotedCells() {
        val failure = runCatching { PlaylistImport.parseCsv("title,artist\n\"Never closed,Artist") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("unclosed quoted") == true)
    }

    @Test
    fun spotifyPlaylistMatchingRequiresTitleAndArtistAndChecksDuration() {
        val source = SpotifyPublicTrack("spotify-id", "A Song", "The Artist", 180_000)
        val match = MusicTrack("A Song", "The Artist feat. Guest", "Album", listOf(XanColors.Card), "♪", durationSeconds = 183)
        val wrongArtist = match.copy(artist = "Another Artist")
        val wrongDuration = match.copy(durationSeconds = 240)
        assertTrue(isSpotifyTrackMatch(source, match))
        assertFalse(isSpotifyTrackMatch(source, wrongArtist))
        assertFalse(isSpotifyTrackMatch(source, wrongDuration))
    }

    @Test
    fun listenBrainzPayloadIsValidAndEscapesTrackMetadata() {
        val track = MusicTrack("A \"Quoted\" Song", "Artist\\Name", "Album", listOf(XanColors.Card), "♪", durationSeconds = 201)
        val json = Json.parseToJsonElement(ListenBrainzClient.singleListenPayload(track, 1_800_000_000, "0.4.5")).jsonObject
        assertEquals("single", json.getValue("listen_type").jsonPrimitive.content)
        val item = json.getValue("payload").jsonArray.single().jsonObject
        assertEquals(1_800_000_000, item.getValue("listened_at").jsonPrimitive.content.toLong())
        val metadata = item.getValue("track_metadata").jsonObject
        assertEquals("A \"Quoted\" Song", metadata.getValue("track_name").jsonPrimitive.content)
        assertEquals("Artist\\Name", metadata.getValue("artist_name").jsonPrimitive.content)
        assertEquals(201_000, metadata.getValue("additional_info").jsonObject.getValue("duration_ms").jsonPrimitive.content.toInt())
    }

    @Test
    fun listenBrainzTokenValidationReadsUserAndRejectsInvalidToken() {
        assertEquals("bxane", ListenBrainzClient.parseValidationResponse("""{"valid":true,"user_name":"bxane"}"""))
        assertEquals("bx\"ane", ListenBrainzClient.parseValidationResponse("""{"valid":true,"user_name":"bx\"ane"}"""))
        assertTrue(runCatching { ListenBrainzClient.parseValidationResponse("""{"valid":false}""") }.isFailure)
    }

    @Test
    fun listenBrainzHttpFlowValidatesTokenAndSubmitsOneListen() {
        val authorization = AtomicReference<String>()
        val submittedBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/1/validate-token") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val valid = exchange.requestHeaders.getFirst("Authorization") == "Token valid-test-token"
            val response = if (valid) """{"valid":true,"user_name":"test-user"}""" else """{"valid":false}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.createContext("/1/submit-listens") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            submittedBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = """{"status":"ok"}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/1"
            assertEquals("test-user", ListenBrainzClient.validateToken("valid-test-token", base))
            assertEquals("Token valid-test-token", authorization.get())
            assertTrue(runCatching { ListenBrainzClient.validateToken("bad-token", base) }.isFailure)
            val track = MusicTrack("Song", "Artist", "Album", listOf(XanColors.Card), "♪", durationSeconds = 190)
            ListenBrainzClient.submitListen("valid-test-token", track, 1_800_000_000, "0.4.5", base)
            assertEquals("Token valid-test-token", authorization.get())
            val body = Json.parseToJsonElement(submittedBody.get()).jsonObject
            assertEquals("single", body.getValue("listen_type").jsonPrimitive.content)
            assertEquals("Song", body.getValue("payload").jsonArray.single().jsonObject.getValue("track_metadata").jsonObject.getValue("track_name").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun scrobbleLedgerPreventsDuplicateProviderSubmissionsButAllowsRetryAfterFailure() {
        val ledger = ScrobbleSubmissionLedger()
        assertTrue(ledger.claim("play-1", "Last.fm"))
        assertFalse(ledger.claim("play-1", "Last.fm"))
        ledger.finish("play-1", "Last.fm", succeeded = true)
        assertFalse(ledger.claim("play-1", "Last.fm"))
        assertTrue(ledger.claim("play-1", "ListenBrainz"))
        ledger.finish("play-1", "ListenBrainz", succeeded = false)
        assertTrue(ledger.claim("play-1", "ListenBrainz"))
        ledger.finish("play-1", "ListenBrainz", succeeded = true)
        assertFalse(ledger.claim("play-1", "ListenBrainz"))
    }

    @Test
    fun sponsorBlockHttpUsesHashedPrefixAndReturnsMatchingSkipSegments() {
        val requested = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val videoId = "abcdefghijk"
        val otherVideoId = "12345678901"
        server.createContext("/api/skipSegments/") { exchange ->
            requested.set(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery)
            val response = """[
                {"videoID":"$videoId","segments":[
                    {"segment":[12.5,18.0],"category":"sponsor","actionType":"skip"},
                    {"segment":[20,30],"category":"interaction","actionType":"skip"},
                    {"segment":[31,35],"category":"selfpromo","actionType":"mute"},
                    {"segment":[38,37],"category":"sponsor","actionType":"skip"}
                ]},
                {"videoID":"$otherVideoId","segments":[{"segment":[1,5],"category":"sponsor","actionType":"skip"}]}
            ]""".trimIndent()
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}/api/skipSegments"
            val segments = SponsorBlockClient.segments(videoId, listOf("sponsor", "selfpromo"), root)
            assertEquals(listOf(SponsorSegment(12_500, 18_000, "sponsor")), segments)
            assertTrue(requested.get().contains("/api/skipSegments/"))
            assertFalse(requested.get().contains(videoId))
            assertTrue(requested.get().contains("categories="))
            assertTrue(requested.get().contains("actionTypes="))
            assertEquals(18_050L, sponsorSegmentTarget(segments.single(), 0))
            assertEquals(17_000L, sponsorSegmentTarget(segments.single(), 17_000))
            assertEquals("sponsor", sponsorSegmentAt(segments, 15_000)?.category)
            assertEquals(null, sponsorSegmentAt(segments, 18_000))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun settingsRoundTripAndRestorePreviousValues() {
        assertEquals(DesktopBackend.firstInstalledAtMillis(), DesktopBackend.firstInstalledAtMillis())
        val originalFallback = DesktopBackend.fallbackEnabled()
        val originalMagnet = DesktopBackend.magnetNavigation()
        val originalMagnetSemicircle = DesktopBackend.magnetSemicircle()
        val originalPosition = DesktopBackend.magnetPosition()
        val originalLogo = DesktopBackend.goldLogo()
        val originalTheme = DesktopBackend.colorTheme()
        val originalFont = DesktopBackend.fontFamily()
        val originalReducedMotion = DesktopBackend.reducedMotion()
        val originalReducedTransparency = DesktopBackend.reducedTransparency()
        val originalListeningHistory = DesktopBackend.listeningHistoryEnabled()
        val originalSearchHistory = DesktopBackend.searchHistoryEnabled()
        val originalLyricsProviders = DesktopBackend.lyricsProviderOrder()
        val originalLastFmScrobbling = DesktopBackend.lastFmScrobblingEnabled()
        val originalListenBrainzScrobbling = DesktopBackend.listenBrainzScrobblingEnabled()
        val originalSponsorBlockEnabled = DesktopBackend.sponsorBlockEnabled()
        val originalSponsorBlockCategories = DesktopBackend.sponsorBlockCategories()
        try {
            DesktopBackend.setFallbackEnabled(!originalFallback)
            DesktopBackend.setMagnetNavigation(!originalMagnet)
            DesktopBackend.setMagnetSemicircle(!originalMagnetSemicircle)
            DesktopBackend.setMagnetPosition(.23f, .71f)
            DesktopBackend.setGoldLogo(!originalLogo)
            DesktopBackend.setColorTheme("midnight")
            DesktopBackend.setFontFamily("google_sans")
            DesktopBackend.setReducedMotion(!originalReducedMotion)
            DesktopBackend.setReducedTransparency(!originalReducedTransparency)
            DesktopBackend.setListeningHistoryEnabled(!originalListeningHistory)
            DesktopBackend.setSearchHistoryEnabled(!originalSearchHistory)
            DesktopBackend.setLyricsProviderOrder(originalLyricsProviders.reversed())
            DesktopBackend.setLastFmScrobblingEnabled(!originalLastFmScrobbling)
            DesktopBackend.setListenBrainzScrobblingEnabled(!originalListenBrainzScrobbling)
            DesktopBackend.setSponsorBlockEnabled(!originalSponsorBlockEnabled)
            DesktopBackend.setSponsorBlockCategories(listOf("sponsor", "intro", "filler"))
            assertEquals(!originalFallback, DesktopBackend.fallbackEnabled())
            assertEquals(!originalMagnet, DesktopBackend.magnetNavigation())
            assertEquals(!originalMagnetSemicircle, DesktopBackend.magnetSemicircle())
            assertEquals(.23f to .71f, DesktopBackend.magnetPosition())
            assertEquals(!originalLogo, DesktopBackend.goldLogo())
            assertEquals("midnight", DesktopBackend.colorTheme())
            assertEquals("google_sans", DesktopBackend.fontFamily())
            DesktopBackend.setFontFamily("not-a-font")
            assertEquals("system", DesktopBackend.fontFamily())
            DesktopBackend.setColorTheme("not-a-theme")
            assertEquals("xan", DesktopBackend.colorTheme())
            assertEquals(!originalReducedMotion, DesktopBackend.reducedMotion())
            assertEquals(!originalReducedTransparency, DesktopBackend.reducedTransparency())
            assertEquals(!originalListeningHistory, DesktopBackend.listeningHistoryEnabled())
            assertEquals(!originalSearchHistory, DesktopBackend.searchHistoryEnabled())
            assertEquals(originalLyricsProviders.reversed(), DesktopBackend.lyricsProviderOrder())
            assertEquals(!originalLastFmScrobbling, DesktopBackend.lastFmScrobblingEnabled())
            assertEquals(!originalListenBrainzScrobbling, DesktopBackend.listenBrainzScrobblingEnabled())
            assertEquals(!originalSponsorBlockEnabled, DesktopBackend.sponsorBlockEnabled())
            assertEquals(listOf("sponsor", "intro", "filler"), DesktopBackend.sponsorBlockCategories())
        } finally {
            DesktopBackend.setFallbackEnabled(originalFallback)
            DesktopBackend.setMagnetNavigation(originalMagnet)
            DesktopBackend.setMagnetSemicircle(originalMagnetSemicircle)
            if (originalPosition == null) DesktopBackend.clearMagnetPosition()
            else DesktopBackend.setMagnetPosition(originalPosition.first, originalPosition.second)
            DesktopBackend.setGoldLogo(originalLogo)
            DesktopBackend.setColorTheme(originalTheme)
            DesktopBackend.setFontFamily(originalFont)
            DesktopBackend.setReducedMotion(originalReducedMotion)
            DesktopBackend.setReducedTransparency(originalReducedTransparency)
            DesktopBackend.setListeningHistoryEnabled(originalListeningHistory)
            DesktopBackend.setSearchHistoryEnabled(originalSearchHistory)
            DesktopBackend.setLyricsProviderOrder(originalLyricsProviders)
            DesktopBackend.setLastFmScrobblingEnabled(originalLastFmScrobbling)
            DesktopBackend.setListenBrainzScrobblingEnabled(originalListenBrainzScrobbling)
            DesktopBackend.setSponsorBlockEnabled(originalSponsorBlockEnabled)
            DesktopBackend.setSponsorBlockCategories(originalSponsorBlockCategories)
        }
    }

    @Test
    fun libraryListsDownloadedAudioAndIgnoresOtherFiles() {
        val originalDirectory = DesktopBackend.downloadDirectoryLabel()
        val directory = Files.createTempDirectory("xan-library-flow-")
        try {
            Files.write(directory.resolve("saved-track.m4a"), byteArrayOf(1, 2, 3))
            Files.write(directory.resolve("not-audio.txt"), byteArrayOf(1, 2, 3))
            DesktopBackend.setDownloadDirectory(directory.toString())

            val files = DesktopBackend.downloadedFiles()
            assertEquals(listOf(directory.resolve("saved-track.m4a")), files)
            assertFalse(files.any { it.fileName.toString().endsWith(".txt") })
            val downloaded = DesktopBackend.downloadedTracks().single()
            assertEquals("saved-track", downloaded.title)
            assertEquals(directory.resolve("saved-track.m4a").toAbsolutePath().normalize().toString(), downloaded.localPath)
            assertEquals("local", downloaded.source)
            assertEquals(3L, DesktopBackend.downloadedBytes())
            assertEquals(1, DesktopBackend.clearDownloadedFiles())
            assertFalse(Files.exists(directory.resolve("saved-track.m4a")))
        } finally {
            DesktopBackend.setDownloadDirectory(originalDirectory)
            Files.deleteIfExists(directory.resolve("saved-track.m4a"))
            Files.deleteIfExists(directory.resolve("not-audio.txt"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun localLibraryScannerIndexesAudioFilesAndRemovesFolderTracks() {
        val directory = Files.createTempDirectory("xan-local-scan-")
        val audio = directory.resolve("Example Artist - Example Song.mp3")
        val ignored = directory.resolve("readme.txt")
        Files.write(audio, byteArrayOf(1, 2, 3))
        Files.write(ignored, byteArrayOf(4, 5, 6))
        try {
            DesktopBackend.addMusicFolder(directory)
            assertEquals(1, DesktopBackend.scanMusicLibrary())
            val song = DesktopBackend.catalogTracks().single { it.videoId == null && it.title == "Example Song" }
            assertEquals("Example Artist", song.artist)
            assertEquals("local", song.source)
            assertEquals(false, song.metadataVerified)

            DesktopBackend.removeMusicFolder(directory)
            assertFalse(DesktopBackend.catalogTracks().any { it.title == "Example Song" })
        } finally {
            DesktopBackend.removeMusicFolder(directory)
            Files.deleteIfExists(audio)
            Files.deleteIfExists(ignored)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun downloadedCollectionsPersistAndCanBeRestored() {
        val original = DesktopBackend.savedCollections()
        val album = MusicTrack(
            title = "Test Album",
            artist = "Test Artist",
            album = "2026",
            colors = listOf(XanColors.CardLight, XanColors.Card),
            motif = "♫",
            artworkUrl = "https://example.test/art.jpg",
            browseId = "browse-test",
            collectionId = "collection-test",
            kind = "album",
        )
        val playlist = album.copy(title = "Test Playlist", kind = "playlist")
        try {
            DesktopBackend.restoreSavedCollections(emptyList())
            DesktopBackend.rememberDownloadedCollection(album)
            DesktopBackend.rememberDownloadedCollection(playlist)
            assertEquals(listOf("playlist", "album"), DesktopBackend.savedCollections().map { it.kind })
            assertEquals("browse-test", DesktopBackend.savedCollections("album").single().browseId)
            assertEquals("collection-test", DesktopBackend.savedCollections("playlist").single().collectionId)

            val artist = album.copy(title = "Saved Artist", kind = "artist", collectionId = "artist-test")
            assertEquals(true, DesktopBackend.setCollectionSaved(artist, true))
            assertEquals(false, DesktopBackend.setCollectionSaved(artist.copy(title = "Renamed Artist"), true))
            assertEquals(true, DesktopBackend.isCollectionSaved(artist))
            assertEquals(true, DesktopBackend.setCollectionSaved(artist, false))
            assertEquals(false, DesktopBackend.isCollectionSaved(artist))
            assertEquals(listOf("playlist", "album"), DesktopBackend.savedCollections().map { it.kind })

            DesktopBackend.restoreSavedCollections(original)
            assertEquals(original.map { it.title }, DesktopBackend.savedCollections().map { it.title })
        } finally {
            DesktopBackend.restoreSavedCollections(original)
        }
    }

    @Test
    fun sqliteCatalogueMigratesPersistsSearchAndPlaylistOrder() {
        val directory = Files.createTempDirectory("xan-catalogue-")
        val database = directory.resolve("catalogue.sqlite3")
        try {
            val catalogue = CatalogStore(database)
            assertEquals(10, catalogue.schemaVersion())
            val tracks = listOf(
                CatalogTrack("ytm:song:one", "Patient Zero", "Taylor Swift", "Example Album", 218, source = "youtube_music", videoId = "one"),
                CatalogTrack("ytm:song:two", "Great Expectation", "SIENNA SPIRO", "Example Album", 194, source = "youtube_music", videoId = "two"),
            )
            catalogue.upsertTracks(tracks)
            catalogue.setLiked("ytm:song:one", true)
            catalogue.upsertTracks(listOf(tracks.first().copy(artworkUrl = "https://example.test/cover.jpg")))

            val reopened = CatalogStore(database)
            assertEquals(listOf("Patient Zero"), reopened.search("patient").map { it.title })
            assertEquals("https://example.test/cover.jpg", reopened.search("patient").single().artworkUrl)
            assertTrue(reopened.search("patient").single().liked)

            val playlist = reopened.createPlaylist("My playlist")
            reopened.replacePlaylistTracks(playlist.id, tracks.reversed())
            java.sql.DriverManager.getConnection("jdbc:sqlite:$database").use { db ->
                db.prepareStatement("UPDATE playlists SET created_at=100, updated_at=200 WHERE id=?").use {
                    it.setString(1, playlist.id)
                    it.executeUpdate()
                }
            }
            assertEquals(listOf("Great Expectation", "Patient Zero"), reopened.playlistTracks(playlist.id).map { it.title })
            assertEquals("My playlist", reopened.playlists().single().name)
            assertEquals(2, reopened.playlists().single().trackCount)
            assertEquals(100L, reopened.playlists().single().createdAt)
            assertEquals(200L, reopened.playlists().single().updatedAt)
            reopened.deletePlaylist(playlist.id)
            assertTrue(reopened.playlists().isEmpty())
            reopened.recordPlay("ytm:song:one", 1_800_000_000_000, 60_000)
            assertEquals("Patient Zero", reopened.recentHistory().single().track.title)
            assertEquals(1L, reopened.historyCount())
            reopened.clearHistory()
            assertEquals(0L, reopened.historyCount())

            reopened.recordSearch("Radiohead", 1)
            reopened.recordSearch("radiohead", 2)
            reopened.recordSearch("Daft Punk", 3)
            assertEquals(3L, reopened.searchHistoryCount())
            assertEquals("Daft Punk", reopened.recentSearches().first())
            reopened.recordCachedTrack(tracks.first(), directory.resolve("cache.m4a").toString(), 4_096, 10)
            assertEquals("Patient Zero", reopened.cachedTracks().single().track.title)
            assertEquals(4_096L, reopened.cachedTracks().single().sizeBytes)
            assertEquals(20L, reopened.touchCachedTrack("ytm:song:one", 20)?.lastAccessedAt)
            assertEquals(directory.resolve("cache.m4a").toString(), reopened.forgetCachedTrack("ytm:song:one")?.path)
            assertTrue(reopened.cachedTracks().isEmpty())
            assertEquals(2, reopened.recentSearches().size)
            reopened.clearSearchHistory()
            assertEquals(0L, reopened.searchHistoryCount())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun myTopFiftyUsesPersistedListeningTimeAndHonorsLimit() {
        val directory = Files.createTempDirectory("xan-top-tracks-")
        try {
            val catalogue = CatalogStore(directory.resolve("catalogue.sqlite3"))
            catalogue.upsertTracks(listOf(
                CatalogTrack("song:a", "A", "Artist", "Album", source = "youtube_music"),
                CatalogTrack("song:b", "B", "Artist", "Album", source = "youtube_music"),
            ))
            catalogue.recordPlay("song:a", 1_800_000_000_000, 45_000)
            catalogue.recordPlay("song:b", 1_800_000_001_000, 90_000)
            catalogue.recordPlay("song:a", 1_800_000_002_000, 60_000)

            assertEquals(listOf("A", "B"), catalogue.topPlayedTracks().map { it.title })
            assertEquals(listOf("A"), catalogue.topPlayedTracks(limit = 1).map { it.title })
            assertEquals(CatalogHistoryStats(3, 195_000, 2, 1_800_000_000_000, 1_800_000_002_000), catalogue.historyStats())
            assertEquals(listOf("A"), catalogue.topPlayedTrackStats(sinceMillis = 1_800_000_001_500).map { it.track.title })
            assertEquals(60_000, catalogue.historyStats(sinceMillis = 1_800_000_001_500).listenedMillis)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun librarySortAndViewPreferencesPersistAndCanBeRestored() {
        val originalSort = DesktopBackend.librarySort()
        val originalDescending = DesktopBackend.librarySortDescending()
        val originalGrid = DesktopBackend.libraryGrid()
        try {
            DesktopBackend.setLibrarySort("Name")
            DesktopBackend.setLibrarySortDescending(false)
            DesktopBackend.setLibraryGrid(false)
            assertEquals("Name", DesktopBackend.librarySort())
            DesktopBackend.setLibrarySort("Last updated")
            assertEquals("Last updated", DesktopBackend.librarySort())
            DesktopBackend.setLibrarySort("Song count")
            assertEquals("Last updated", DesktopBackend.librarySort())
            assertFalse(DesktopBackend.librarySortDescending())
            assertFalse(DesktopBackend.libraryGrid())
        } finally {
            DesktopBackend.setLibrarySort(originalSort)
            DesktopBackend.setLibrarySortDescending(originalDescending)
            DesktopBackend.setLibraryGrid(originalGrid)
        }
    }

    @Test
    fun sqliteCatalogueSearchEscapesSqlWildcards() {
        val directory = Files.createTempDirectory("xan-catalogue-search-")
        try {
            val catalogue = CatalogStore(directory.resolve("catalogue.sqlite3"))
            catalogue.upsertTracks(listOf(
                CatalogTrack("literal", "100% Real", "Artist", "Album"),
                CatalogTrack("other", "100 percent Real", "Artist", "Album"),
            ))
            assertEquals(listOf("100% Real"), catalogue.search("100%").map { it.title })
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun sqliteCatalogueUpgradesVersionFourDatabaseWithLyricsCache() {
        val directory = Files.createTempDirectory("xan-catalogue-v4-")
        val database = directory.resolve("catalogue.sqlite3")
        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 4") }
            }
            val upgraded = CatalogStore(database)
            assertEquals(10, upgraded.schemaVersion())
            upgraded.recordSearch("legacy upgrade")
            assertEquals(listOf("legacy upgrade"), upgraded.recentSearches())
            upgraded.cacheLyrics("video:42", "[00:00.00]line", "test")
            assertEquals("test", upgraded.cachedLyrics("video:42")?.provider)
            assertEquals("[00:00.00]line".length.toLong(), upgraded.lyricsCacheSizeBytes())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun backupArchiveRoundTripsCatalogueAndWhitelistedSettingsWithIntegrityChecks() {
        val directory = Files.createTempDirectory("xan-backup-")
        val sourceDatabase = directory.resolve("source.sqlite3")
        val backup = directory.resolve("xan.xanbackup")
        val restoredDatabase = directory.resolve("restored.sqlite3")
        try {
            CatalogStore(sourceDatabase).apply {
                upsertTracks(listOf(CatalogTrack("local:1", "Song", "Artist", "Album")))
                recordSearch("Mogwai")
                createPlaylist("Road trip")
                cacheLyrics("video:1", "[00:01.00]verse", "LRCLIB")
            }
            val settings = Properties().apply {
                setProperty("youtube_video_fallback", "false")
                setProperty("magnet_navigation", "true")
                setProperty("font_family", "google_sans")
            }
            BackupArchive.create(sourceDatabase, settings, backup)
            val restoredSettings = BackupArchive.read(backup, restoredDatabase)
            assertEquals(settings, restoredSettings)
            val restored = CatalogStore(restoredDatabase)
            assertTrue(restored.verifyIntegrity())
            assertEquals(listOf("Mogwai"), restored.recentSearches())
            assertEquals("Road trip", restored.playlists().single().name)
            assertEquals("LRCLIB", restored.cachedLyrics("video:1")?.provider)
            assertEquals(-1L, Files.mismatch(sourceDatabase, restoredDatabase))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun desktopLyricsParserHandlesLineWordAndPlainTiming() {
        val lines = parseDesktopLyrics("[00:01.25]<00:01.25>Hello <00:01.75>world\n[00:02.00]Again")
        assertEquals(2, lines.size)
        assertEquals(1_250L, lines[0].startsAtMillis)
        assertEquals("Hello world", lines[0].text)
        assertEquals(listOf(1_250L, 1_750L), lines[0].words.map { it.startsAtMillis })
        assertEquals(2_000L, lines[1].startsAtMillis)

        val plain = parseDesktopLyrics("First verse\nSecond verse")
        assertEquals(listOf(null, null), plain.map { it.startsAtMillis })
    }

    @Test
    fun desktopLyricsRepositoryServesPersistentCacheWithoutNetwork() = kotlinx.coroutines.runBlocking {
        val track = MusicTrack("Cache Test", "XAN Test", "", listOf(XanColors.CardLight, XanColors.Card), "♪", videoId = "lyrics-cache-test")
        val key = DesktopLyricsRepository.trackKey(track)
        val previous = DesktopBackend.cachedLyrics(key)
        try {
            DesktopBackend.cacheLyrics(key, "[00:00.00]cached line", "Test provider")
            val result = DesktopLyricsRepository.load(track)
            assertTrue(result.cached)
            assertEquals("Test provider", result.provider)
            assertEquals("cached line", result.lines.single().text)
        } finally {
            if (previous == null) DesktopBackend.deleteCachedLyrics(key)
            else DesktopBackend.cacheLyrics(key, previous.lyrics, previous.provider)
        }
    }

    @Test
    fun liveLyricsProviderSmokeWhenOptedIn() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("XAN_LIVE_LYRICS_SMOKE") == "1")
        val track = MusicTrack("Bohemian Rhapsody", "Queen", "A Night at the Opera", listOf(XanColors.CardLight, XanColors.Card), "♪", videoId = "live-lyrics-smoke")
        val key = DesktopLyricsRepository.trackKey(track)
        val previousLyrics = DesktopBackend.cachedLyrics(key)
        val previousProviders = DesktopBackend.lyricsProviderOrder()
        try {
            DesktopBackend.setLyricsProviderOrder(listOf("BetterLyrics", "YouLyPlus", "SimpMusic", "LRCLIB"))
            val result = DesktopLyricsRepository.load(track)
            assertTrue(result.lines.isNotEmpty())
            assertTrue(result.provider in setOf("BetterLyrics", "YouLyPlus", "SimpMusic", "LRCLIB"))
        } finally {
            if (previousLyrics == null) DesktopBackend.deleteCachedLyrics(key)
            else DesktopBackend.cacheLyrics(key, previousLyrics.lyrics, previousLyrics.provider)
            DesktopBackend.setLyricsProviderOrder(previousProviders)
        }
    }

    @Test
    fun lastFmScrobbleThresholdMatchesOfficialMinimumAndFourMinuteCap() {
        assertEquals(null, scrobbleThresholdMillis(30_000))
        assertEquals(15_500L, scrobbleThresholdMillis(31_000))
        assertEquals(120_000L, scrobbleThresholdMillis(240_000))
        assertEquals(240_000L, scrobbleThresholdMillis(900_000))
    }

    @Test
    fun playbackStreamSelectorAcceptsJavaFxSupportedAacAndRejectsOpus() {
        assertTrue(isDesktopPlayableAudioMimeType("audio/mp4; codecs=\"mp4a.40.2\""))
        assertFalse(isDesktopPlayableAudioMimeType("audio/webm; codecs=\"opus\""))
        assertFalse(isDesktopPlayableAudioMimeType("video/mp4; codecs=\"avc1.640028\""))
    }
}
