package app.xan.music.utils

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.xan.music.constants.MagnetSemicircleKey

/** Move upgraded installs to the straight Magnet layout once. */
internal object MagnetStraightDefaultMigration : DataMigration<Preferences> {
    private val appliedKey = booleanPreferencesKey("magnetStraightDefaultApplied")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[appliedKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[MagnetSemicircleKey] = false
            this[appliedKey] = true
        }

    override suspend fun cleanUp() = Unit
}
