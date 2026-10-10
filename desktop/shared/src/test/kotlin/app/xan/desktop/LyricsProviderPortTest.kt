package app.xan.desktop

import app.xan.music.betterlyrics.TTMLParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderPortTest {
    @Test
    fun ttmlWordTimingFromBetterLyricsMapsIntoDesktopLyrics() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div><p begin="00:00:01.000">
                <span begin="00:00:01.000" end="00:00:01.350">Hello</span>
                <span begin="00:00:01.400" end="00:00:01.800">world</span>
              </p></div></body>
            </tt>
        """.trimIndent()
        val lrc = TTMLParser.toLRC(TTMLParser.parseTTML(ttml))

        val parsed = parseDesktopLyrics(lrc).single()
        assertEquals("Hello world", parsed.text)
        assertEquals(listOf("Hello" to 1000L, "world" to 1400L), parsed.words.map { it.text to it.startsAtMillis })
    }

    @Test
    fun ttmlParserRejectsDocumentTypeDeclarations() {
        val malicious = """
            <!DOCTYPE tt [ <!ENTITY injected "must-not-be-read"> ]>
            <tt><body><p begin="00:00:01.000">&injected;</p></body></tt>
        """.trimIndent()

        assertTrue(TTMLParser.parseTTML(malicious).isEmpty())
    }

    @Test
    fun desktopLyricsProviderOrderIncludesAndroidFallbackSources() {
        val providers = DesktopBackend.lyricsProviderOrder()
        assertEquals(setOf("BetterLyrics", "YouLyPlus", "SimpMusic", "LRCLIB"), providers.toSet())
        assertEquals(providers.size, providers.distinct().size)
    }
}
