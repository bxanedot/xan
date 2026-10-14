package app.xan.music.utils

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import app.xan.music.constants.MagnetOnLeftKey
import app.xan.music.constants.MagnetSemicircleKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalMagnetMigrationTest {
    @Test
    fun `fresh installs start with the straight magnet`() = runTest {
        val before = emptyPreferences()
        assertTrue(OriginalMagnetMigration.shouldMigrate(before))
        val after = OriginalMagnetMigration.migrate(before)
        assertEquals(false, after[MagnetSemicircleKey])
        assertFalse(OriginalMagnetMigration.shouldMigrate(after))
    }

    @Test
    fun `upgrade selects straight layout without changing side preference`() = runTest {
        val before = preferencesOf(MagnetSemicircleKey to true, MagnetOnLeftKey to true)
        val after = OriginalMagnetMigration.migrate(before)
        assertEquals(false, after[MagnetSemicircleKey])
        assertEquals(true, after[MagnetOnLeftKey])
        assertEquals(true, before[MagnetSemicircleKey])
    }

    @Test
    fun `later semicircle choice survives subsequent launches`() = runTest {
        val optedBackIn = OriginalMagnetMigration.migrate(emptyPreferences())
            .toMutablePreferences().apply { this[MagnetSemicircleKey] = true }
        assertFalse(OriginalMagnetMigration.shouldMigrate(optedBackIn))
        assertEquals(true, optedBackIn[MagnetSemicircleKey])
    }
}
