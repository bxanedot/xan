package app.xan.music.utils

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.xan.music.constants.MagnetSemicircleKey

/** Seed the screenshot-matching arc layout once, before settings are read. */
internal object OriginalMagnetMigration : DataMigration<Preferences> {
    private val restoredKey = booleanPreferencesKey("originalMagnetRestored")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[restoredKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[MagnetSemicircleKey] = true
            this[restoredKey] = true
        }

    override suspend fun cleanUp() = Unit
}
