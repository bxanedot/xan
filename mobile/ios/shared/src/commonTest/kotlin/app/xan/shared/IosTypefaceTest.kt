package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class IosTypefaceTest {
    @Test
    fun androidFontOptionsAndDefaultArePreserved() {
        assertEquals("system", IosTypeface.fromPreference(null))
        assertEquals("system", IosTypeface.fromPreference("unknown"))
        assertEquals(
            listOf("system", "google_sans", "sans_flex", "outfit", "plus_jakarta_sans"),
            IosTypeface.options.map { it.id },
        )
    }

    @Test
    fun storedAndroidFontIdsRestoreAcrossPlatforms() {
        IosTypeface.options.forEach { option ->
            assertEquals(option.id, IosTypeface.fromPreference(option.id))
        }
    }
}
