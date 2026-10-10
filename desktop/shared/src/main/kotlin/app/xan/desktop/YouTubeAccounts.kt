package app.xan.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class YouTubeAccountProfile(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val thumbnailUrl: String?,
)

@Serializable
internal data class SavedYouTubeChannel(
    val dataSyncId: String,
    val name: String,
    val channelHandle: String? = null,
    val thumbnailUrl: String? = null,
)

internal data class YouTubeAccountState(
    val profile: YouTubeAccountProfile,
    val selectedDataSyncId: String?,
    val channels: List<SavedYouTubeChannel>,
)

internal fun useAccountForBrowse(preference: String?): Boolean =
    preference?.toBooleanStrictOrNull() ?: true

/** Stores channel identities only. The session cookie stays in the OS credential store. */
internal object YouTubeAccountCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(channels: List<SavedYouTubeChannel>): String = json.encodeToString(channels)

    fun decode(serialized: String): List<SavedYouTubeChannel> = runCatching {
        json.decodeFromString<List<SavedYouTubeChannel>>(serialized)
            .filter { it.dataSyncId.isNotBlank() && it.name.isNotBlank() }
            .distinctBy(SavedYouTubeChannel::dataSyncId)
    }.getOrDefault(emptyList())
}
