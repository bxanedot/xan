package app.xan.music.utils

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.xan.music.constants.MagnetSemicircleKey

/** Move upgraded installs to the inward-facing arc used by the new Magnet design. */
internal object MagnetArcDefaultMigration : DataMigration<Preferences> {
    private val appliedKey = booleanPreferencesKey("magnetScreenshotArcDefaultApplied")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[appliedKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[MagnetSemicircleKey] = true
            this[appliedKey] = true
        }

    override suspend fun cleanUp() = Unit
}
