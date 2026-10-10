package app.xan.music.utils.potoken

import org.junit.Assert.assertEquals
import org.junit.Test

class PoTokenApiKeyTest {
    @Test
    fun blankOverrideUsesBundledKey() {
        assertEquals("bundled-key", resolvePoTokenApiKey("  ", "bundled-key"))
    }

    @Test
    fun nonBlankOverrideIsTrimmedAndUsed() {
        assertEquals("custom-key", resolvePoTokenApiKey("  custom-key  ", "bundled-key"))
    }
}
