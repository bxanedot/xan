package app.xan.music.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiniLyricsUrlTest {
    @Test
    fun acceptsOnlyTheTrustedHttpsLyricsHosts() {
        assertTrue(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://lyrics-storage.binimum.org/song.ttml"))
        assertTrue(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://lrc.red/s/GB5KW2202504.ttml"))
        assertTrue(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://lrc.red:443/s/song.ttml"))
        assertFalse(BiniLyricsProvider.isTrustedBiniLyricsUrl("http://lrc.red/s/song.ttml"))
        assertFalse(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://lrc.red.attacker.example/song.ttml"))
        assertFalse(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://user@lrc.red/s/song.ttml"))
        assertFalse(BiniLyricsProvider.isTrustedBiniLyricsUrl("https://lrc.red:8443/s/song.ttml"))
    }
}
