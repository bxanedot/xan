package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosLyricsRomanizationTest {
    @Test
    fun onlyAddsAChangedLineAndHonorsThePersistedDisplaySwitch() {
        val previous = IosLyricsRomanization.state.value
        IosLyricsRomanization.install(object : IosLyricsRomanizer {
            override fun romanizeLyrics(text: String): String? = when (text) {
                "東京" -> "dong jing"
                else -> text
            }
        })

        try {
            IosLyricsRomanization.setEnabled(true)
            IosLyricsRomanization.setUseAsMain(true)
            assertEquals("true", readIosPreference("lyrics.romanization.enabled"))
            assertEquals("true", readIosPreference("lyrics.romanization.useAsMain"))
            assertEquals("dong jing", IosLyricsRomanization.romanize("東京"))
            assertNull(IosLyricsRomanization.romanize("already Latin"))
            assertEquals(true, IosLyricsRomanization.state.value.useAsMain)

            IosLyricsRomanization.setEnabled(false)
            assertNull(IosLyricsRomanization.romanize("東京"))
        } finally {
            IosLyricsRomanization.setEnabled(previous.enabled)
            IosLyricsRomanization.setUseAsMain(previous.useAsMain)
            IosLyricsRomanization.install(null)
        }
    }
}
