package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerSettingsTest {
    @Test
    fun simpleControlsMapToFiveFrequencyBands() {
        assertEquals(listOf(8.0, 5.0, 2.0, 0.0, -2.0), XanEqualizer.simpleGainsDb(8.0, 2.0, -2.0))
    }

    @Test
    fun positiveGainAddsHeadroomAndFlatCurveDoesNotChangeVolume() {
        val flat = EqualizerSettings(enabled = true)
        assertEquals(0.0, XanEqualizer.headroomCompensationDb(flat.gainsDb), 0.0)
        val boosted = EqualizerSettings(enabled = true, gainsDb = listOf(9.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(XanEqualizer.headroomCompensationDb(boosted.gainsDb) < 0.0)
        assertTrue(XanEqualizer.effectiveVolume(0.8, boosted) < 0.8)
        assertEquals(0.8, XanEqualizer.effectiveVolume(0.8, boosted.copy(enabled = false)), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gainsCannotExceedNativeRange() {
        EqualizerSettings(gainsDb = listOf(13.0, 0.0, 0.0, 0.0, 0.0))
    }
}
