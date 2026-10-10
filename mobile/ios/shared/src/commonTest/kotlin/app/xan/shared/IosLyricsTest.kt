package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosLyricsTest {
    @Test
    fun normalizesAndMovesPersistedProviderOrder() {
        assertEquals(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, normalizeIosLyricsProviderOrder(null))
        assertEquals(
            listOf("Kugou", "YouTubeMusic") + DEFAULT_IOS_LYRICS_PROVIDER_ORDER.filterNot { it == "Kugou" || it == "YouTubeMusic" },
            normalizeIosLyricsProviderOrder("Kugou,Unknown,Kugou,YouTubeMusic"),
        )

        val moved = moveIosLyricsProvider(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, "Kugou", -1)
        assertEquals("Kugou", moved[6])
        assertEquals("LrcLib", moved[7])
        assertEquals(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, moveIosLyricsProvider(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, "BetterLyrics", -1))
        assertEquals(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, moveIosLyricsProvider(DEFAULT_IOS_LYRICS_PROVIDER_ORDER, "YouTubeMusic", 1))
    }

    @Test
    fun ranksWordTimingBeforeLineTimingAndUsesSavedOrderForTies() {
        val plain = requireNotNull(parseIosLyrics("Plain line", synced = false)).copy(provider = "plain")
        val firstTimed = requireNotNull(parseIosLyrics("[00:01.00]First timed", synced = true)).copy(provider = "first")
        val preferredTimed = requireNotNull(parseIosLyrics("[00:01.00]Preferred timed", synced = true)).copy(provider = "preferred")
        val wordTimed = requireNotNull(parseIosLyrics("[00:01.00]<00:01.00>Word timed", synced = true)).copy(provider = "word")

        assertEquals(wordTimed, rankIosLyricsDocuments(listOf(0 to plain, 1 to firstTimed, 9 to wordTimed)))
        assertEquals(preferredTimed, rankIosLyricsDocuments(listOf(0 to plain, 5 to firstTimed, 2 to preferredTimed)))
    }

    @Test
    fun parsesSimpMusicWordSyncedLyricsAndMatchesTrackDuration() {
        val payload = """{"type":"success","data":[
            {"durationSeconds":120,"syncedLyrics":"[00:00.00]Wrong result"},
            {"durationSeconds":181,"richSyncLyrics":"[00:00.00]<00:00.00>Keep <00:00.50>moving","syncedLyrics":"[00:00.00]Keep moving","plainLyric":"Keep moving"}
        ]}"""

        val document = requireNotNull(parseIosSimpMusicLyrics(payload, 180))

        assertEquals("SimpMusic", document.provider)
        assertTrue(document.isSynced)
        assertEquals(0L, document.lines.single().startMillis)
        assertEquals("Keep moving", document.lines.single().text)
        assertEquals(listOf(IosLyricsWord(0L, null, "Keep "), IosLyricsWord(500L, null, "moving")), document.lines.single().words)
        assertNull(parseIosSimpMusicLyrics(payload, 240))
        assertNull(parseIosSimpMusicLyrics("""{"type":"failure","data":[]}""", 180))
    }

    @Test
    fun parsesNamespacedTtmlLinesAndEntities() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tt="urn:example">
              <body><div>
                <p begin="00:00:01.250"><span begin="00:00:01.250" end="00:00:01.500">Hello</span> <span begin="00:00:01.500" end="00:00:02.000">world &amp; more</span></p>
                <p begin="00:00:03.500">Second line</p>
              </div></body>
            </tt>
        """.trimIndent()

        val document = parseIosTtmlLyrics(ttml, "BetterLyrics")

        assertEquals("BetterLyrics", document?.provider)
        assertTrue(document?.isSynced == true)
        assertEquals(
            listOf(1_250L to "Hello world & more", 3_500L to "Second line"),
            document?.lines?.map { it.startMillis to it.text },
        )
        assertEquals(
            listOf(IosLyricsWord(1_250, 1_500, "Hello"), IosLyricsWord(1_500, 2_000, "world & more")),
            document?.lines?.first()?.words,
        )
        assertNull(parseIosTtmlLyrics("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><p begin='00:01'>&x;</p>", "BetterLyrics"))
        assertNull(parseIosTtmlLyrics("<p begin='00:01'>&unresolved;</p>", "BetterLyrics"))
    }

    @Test
    fun validatesBiniLyricsUrlsAndChoosesTrustedTimedResult() {
        val payload = """{"results":[
          {"timing_type":"word","lyricsUrl":"https://lyrics-storage.binimum.org/song.ttml"},
          {"timing_type":"word","lyricsUrl":"https://attacker.example/lyrics"}
        ]}"""

        assertEquals("https://lyrics-storage.binimum.org/song.ttml", parseIosBiniLyricsUrl(payload))
        assertTrue(isTrustedIosBiniLyricsUrl("https://lyrics-storage.binimum.org/song.ttml"))
        assertTrue(isTrustedIosBiniLyricsUrl("https://lrc.red/s/GB5KW2202504.ttml"))
        assertTrue(isTrustedIosBiniLyricsUrl("https://user@lyrics-storage.binimum.org/song.ttml").not())
        assertTrue(isTrustedIosBiniLyricsUrl("https://lrc.red.attacker.example/song.ttml").not())
        assertTrue(isTrustedIosBiniLyricsUrl("https://lrc.red:8443/song.ttml").not())
        assertEquals(
            "https://lrc.red/s/GB5KW2202504.ttml",
            parseIosBiniLyricsUrl("""{"results":[{"timing_type":"word","lyricsUrl":"https://lrc.red/s/GB5KW2202504.ttml"}]}"""),
        )
        assertNull(parseIosBiniLyricsUrl("""{"results":[{"lyricsUrl":"http://lyrics-storage.binimum.org/a"}]}"""))
    }

    @Test
    fun parsesUnisonLrcAndTtmlFormats() {
        val lrc = parseIosUnisonLyrics("""{"success":true,"data":{"format":"lrc","lyrics":"[00:01.20]A line"}}""")
        assertEquals("Unison", lrc?.provider)
        assertTrue(lrc?.isSynced == true)
        val ttml = parseIosUnisonLyrics("""{"success":true,"data":{"format":"ttml","lyrics":"<tt><p begin='00:02.000'>A line</p></tt>"}}""")
        assertEquals(listOf(IosLyricsLine(2_000, "A line")), ttml?.lines)
        assertNull(parseIosUnisonLyrics("""{"success":false,"data":{"format":"lrc","lyrics":"[00:01.00]No"}}"""))
    }

    @Test
    fun parsesYouLyPlusWordSyncedResponseAndRejectsMismatches() {
        val payload = """{"trackName":"Example Song","artistName":"Example Artist","duration":120,"lyrics":[
            {"text":"Hello world","time":0,"syllabus":[
              {"text":"Hello","time":0,"duration":700},
              {"text":"world","time":700,"duration":800}
            ]}
        ]}"""

        val document = requireNotNull(parseIosYouLyPlusLyrics(payload, "Example Song", "Example Artist", 121))

        assertEquals("YouLyPlus", document.provider)
        assertTrue(document.isSynced)
        assertEquals("Hello world", document.lines.single().text)
        assertEquals(
            listOf(IosLyricsWord(0, 700, "Hello "), IosLyricsWord(700, 1_500, "world")),
            document.lines.single().words,
        )
        assertNull(parseIosYouLyPlusLyrics(payload, "Different Song", "Example Artist", 120))
        assertNull(parseIosYouLyPlusLyrics(payload, "Example Song", "Example Artist", 150))
        val nestedDuration = """{"metadata":{"totalDuration":"2:00.000"},"lyrics":[{"text":"Timed line","time":0}]}"""
        assertTrue(parseIosYouLyPlusLyrics(nestedDuration, "Example Song", "Example Artist", 121) != null)
        assertNull(parseIosYouLyPlusLyrics(nestedDuration, "Example Song", "Example Artist", 150))
    }

    @Test
    fun parsesPaxsenixWordTimedLyricsAndAppleMusicSongMetadata() {
        val payload = """{"type":"Syllable","content":[{"timestamp":1000,"text":[
            {"text":"Hello","timestamp":1000,"endtime":1500},
            {"text":"world","timestamp":1500,"endtime":2100}
        ]}]}"""
        val document = requireNotNull(parseIosPaxsenixLyrics(payload))

        assertEquals("Paxsenix", document.provider)
        assertEquals("Hello world", document.lines.single().text)
        assertEquals(
            listOf(IosLyricsWord(1_000, 1_500, "Hello"), IosLyricsWord(1_500, 2_100, "world")),
            document.lines.single().words,
        )

        val appleSearch = """{"results":{"songs":{"data":[{"id":"song-1"}]}},"resources":{"songs":{"song-1":{"attributes":{"name":"Example Song","artistName":"Example Artist","durationInMillis":120000}}}}}"""
        assertEquals(
            listOf(IosAppleMusicSong("song-1", "Example Song", "Example Artist", 120.0)),
            parseIosAppleMusicSongs(appleSearch),
        )
    }

    @Test
    fun parsesKuGouSearchCandidatesAndBase64Lrc() {
        assertEquals(
            listOf(IosKuGouSongHash(213, "0123456789abcdef")),
            parseIosKuGouSongHashes("""{"data":{"info":[{"duration":213,"hash":"0123456789abcdef"}]}}"""),
        )
        assertEquals(
            listOf(IosKuGouCandidate(77L, "access_key-1")),
            parseIosKuGouCandidates("""{"candidates":[{"id":77,"accesskey":"access_key-1"}]}"""),
        )
        val document = requireNotNull(parseIosKuGouLyrics("""{"content":"WzAwOjAxLjAwXUhlbGxv"}"""))
        assertEquals("KuGou", document.provider)
        assertEquals(listOf(IosLyricsLine(1_000, "Hello")), document.lines)
        assertTrue(parseIosKuGouCandidates("""{"candidates":[{"id":-1,"accesskey":"bad"}]}""").isEmpty())
    }

    @Test
    fun parsesMusixmatchResponsesAndBuildsDateSignedUrls() {
        assertEquals("token-123", parseIosMusixmatchToken("""{"message":{"header":{"status_code":200},"body":{"user_token":"token-123"}}}"""))
        assertNull(parseIosMusixmatchToken("""{"message":{"header":{"status_code":401},"body":{"user_token":"no"}}}"""))
        assertEquals(
            listOf(IosMusixmatchTrack(12L, "Example Song", "Example Artist", 120, true)),
            parseIosMusixmatchTracks("""{"message":{"body":{"track_list":[{"track":{"track_id":12,"track_name":"Example Song","artist_name":"Example Artist","track_length":120,"has_subtitles":1}}]}}}"""),
        )

        val subtitle = """{"message":{"body":{"subtitle":{"subtitle_body":"[{\"text\":\"First line\",\"time\":{\"total\":1.25}}]"}}}}"""
        val document = requireNotNull(parseIosMusixmatchSubtitle(subtitle))
        assertEquals("Musixmatch", document.provider)
        assertEquals(listOf(IosLyricsLine(1_250, "First line")), document.lines)

        assertEquals("19700101", formatIosMusixmatchDate(0L))
        val signed = buildIosMusixmatchSignedUrl(
            "https://example.test/api",
            "/search",
            listOf("q_track" to "Never Gonna Give You Up", "usertoken" to "abc"),
            "20261009",
        ) { message ->
            assertEquals(
                "https://example.test/api/search?q_track=Never+Gonna+Give+You+Up&usertoken=abc20261009",
                message,
            )
            "sig+/="
        }
        assertEquals(
            "https://example.test/api/search?q_track=Never+Gonna+Give+You+Up&usertoken=abc&signature=sig%2B%2F%3D&signature_protocol=sha256",
            signed,
        )
    }

    @Test
    fun parsesTimedLyricsIncludingMultipleTimestampsAndCentiseconds() {
        val document = parseIosLyrics(
            """
                [ar:Example Artist]
                [ti:Example Song]
                [00:01.25][00:02.500]First line
                [00:03:750]Second line
            """.trimIndent(),
            synced = true,
        )

        requireNotNull(document)
        assertTrue(document.isSynced)
        assertEquals(
            listOf(
                IosLyricsLine(1_250, "First line"),
                IosLyricsLine(2_500, "First line"),
                IosLyricsLine(3_750, "Second line"),
            ),
            document.lines,
        )
    }

    @Test
    fun parsesRichLrcWordTimingWithoutLeavingMarkupInTheDisplayedLine() {
        val document = requireNotNull(parseIosLyrics("[00:01.00]<00:01.00>Hello <00:01.50>world", synced = true))

        assertEquals("Hello world", document.lines.single().text)
        assertEquals(
            listOf(IosLyricsWord(1_000, null, "Hello "), IosLyricsWord(1_500, null, "world")),
            document.lines.single().words,
        )
        assertEquals(0, activeIosLyricsWord(document.lines.single().words, 1_250))
        assertEquals(1, activeIosLyricsWord(document.lines.single().words, 1_500))
    }

    @Test
    fun parsesPlainLyricsWithoutPretendingTheyAreTimed() {
        val document = requireNotNull(parseIosLyrics("First line\nSecond line", synced = false))
        assertFalse(document.isSynced)
        assertEquals(listOf(IosLyricsLine(null, "First line"), IosLyricsLine(null, "Second line")), document.lines)
        assertEquals(-1, activeIosLyricsLine(document.lines, 10_000))
    }

    @Test
    fun activeLineFollowsPlaybackPosition() {
        val lines = parseIosLyrics("[00:01.00]First\n[00:02.50]Second\n[00:04.00]Third", synced = true)?.lines.orEmpty()
        assertEquals(-1, activeIosLyricsLine(lines, 0))
        assertEquals(0, activeIosLyricsLine(lines, 1_900))
        assertEquals(1, activeIosLyricsLine(lines, 3_000))
        assertEquals(2, activeIosLyricsLine(lines, 10_000))
    }

    @Test
    fun ignoresMetadataAndRejectsOversizedOrEmptyLyrics() {
        assertNull(parseIosLyrics("[ar:Artist]\n[offset:-100]", synced = true))
        assertNull(parseIosLyrics("x".repeat(192 * 1024 + 1), synced = false))
    }

    @Test
    fun encodesTheSameTranscriptParamsAsAndroid() {
        assertEquals("CgthYmNkZWZnaGlqaw==", encodeYouTubeTranscriptParams("abcdefghijk"))
    }

    @Test
    fun parsesYouTubeTranscriptCuesAsPlaybackSyncedLines() {
        val document = requireNotNull(parseIosYouTubeTranscript(
            """
                {"actions":[{"updateEngagementPanelAction":{"content":{"transcriptRenderer":{"body":{"transcriptBodyRenderer":{"cueGroups":[
                  {"transcriptCueGroupRenderer":{"cues":[{"transcriptCueRenderer":{"startOffsetMs":1250,"cue":{"simpleText":"♪ First line ♪"}}}]}} ,
                  {"transcriptCueGroupRenderer":{"cues":[{"transcriptCueRenderer":{"startOffsetMs":3420,"cue":{"simpleText":"Second line"}}}]}}
                ]}}}}}}]}
            """.trimIndent(),
        ))

        assertEquals("YouTube Subtitle", document.provider)
        assertTrue(document.isSynced)
        assertEquals(listOf(IosLyricsLine(1_250, "First line"), IosLyricsLine(3_420, "Second line")), document.lines)
    }

    @Test
    fun ignoresMalformedYouTubeTranscriptResponses() {
        assertNull(parseIosYouTubeTranscript("{}"))
        assertNull(parseIosYouTubeTranscript(" "))
    }
}
