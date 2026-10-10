package app.xan.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class IosMusicHomeChip(
    val title: String,
    val browseId: String,
    val params: String?,
)

internal sealed interface IosMusicHomeItem {
    data class Song(val track: IosMusicSearchTrack) : IosMusicHomeItem
    data class Artist(val artist: IosMusicArtistResult) : IosMusicHomeItem
    data class Album(val album: IosMusicAlbumResult) : IosMusicHomeItem
    data class Playlist(
        val browseId: String,
        val playlistId: String?,
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
    ) : IosMusicHomeItem
}

internal data class IosMusicHomeSection(val title: String, val items: List<IosMusicHomeItem>)
internal data class IosMusicHomePage(val chips: List<IosMusicHomeChip>, val sections: List<IosMusicHomeSection>)
internal data class IosMusicAccountLibrary(
    val playlists: List<IosMusicHomeItem.Playlist>,
    val albums: List<IosMusicAlbumResult>,
    val artists: List<IosMusicArtistResult>,
)

internal fun parseIosMusicAccountLibrary(payloads: List<String>): IosMusicAccountLibrary {
    val items = mutableListOf<IosMusicHomeItem>()
    payloads.forEach { payload ->
        val root = Json.parseToJsonElement(payload).asJsonObjectOrNull()
            ?: error("YouTube Music returned an invalid account library.")
        root.visitJsonObjects { container ->
            container.nestedObject("musicTwoRowItemRenderer")?.toIosHomeItem()?.let(items::add)
            container.nestedObject("musicResponsiveListItemRenderer")?.let(::parseIosAccountLibraryRenderer)?.let(items::add)
        }
    }
    return IosMusicAccountLibrary(
        playlists = items.filterIsInstance<IosMusicHomeItem.Playlist>().distinctBy(IosMusicHomeItem.Playlist::browseId),
        albums = items.filterIsInstance<IosMusicHomeItem.Album>().map(IosMusicHomeItem.Album::album).distinctBy(IosMusicAlbumResult::browseId),
        artists = items.filterIsInstance<IosMusicHomeItem.Artist>().map(IosMusicHomeItem.Artist::artist).distinctBy(IosMusicArtistResult::browseId),
    )
}

/** Parses Android-compatible YouTube Music Home browse responses for iOS and iPadOS. */
internal fun parseIosMusicHomePage(payload: String): IosMusicHomePage {
    val root = Json.parseToJsonElement(payload).asJsonObjectOrNull()
        ?: error("YouTube Music returned an invalid Home feed.")
    val sectionList = root.homeSectionList()
        ?: error("YouTube Music did not include a Home feed.")
    val chips = sectionList.nestedArray("header", "chipCloudRenderer", "chips").orEmpty().mapNotNull { element ->
        val renderer = element.asJsonObjectOrNull()?.nestedObject("chipCloudChipRenderer") ?: return@mapNotNull null
        val title = renderer.allRunText("text")?.trim().orEmpty()
        val endpoint = renderer.nestedObject("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
        val browseId = endpoint.text("browseId")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,200}")) }
            ?: return@mapNotNull null
        title.takeIf(String::isNotBlank)?.let { IosMusicHomeChip(it, browseId, endpoint.text("params")) }
    }.distinctBy(IosMusicHomeChip::title)

    val sections = sectionList.nestedArray("contents").orEmpty().mapNotNull { element ->
        val shelf = element.asJsonObjectOrNull()?.nestedObject("musicCarouselShelfRenderer") ?: return@mapNotNull null
        val title = shelf.allRunText("header", "musicCarouselShelfBasicHeaderRenderer", "title")
            ?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val items = shelf.nestedArray("contents").orEmpty().mapNotNull { item ->
            item.asJsonObjectOrNull()?.nestedObject("musicTwoRowItemRenderer")?.toIosHomeItem()
        }
        items.takeIf { it.isNotEmpty() }?.let { IosMusicHomeSection(title, it) }
    }
    return IosMusicHomePage(chips, sections)
}

internal fun JsonObject.toIosHomeItem(): IosMusicHomeItem? {
    val title = allRunText("title")?.trim()?.takeIf(String::isNotBlank) ?: return null
    val browseEndpoint = nestedObject("navigationEndpoint", "browseEndpoint")
    val browseId = browseEndpoint?.text("browseId")
    val pageType = browseEndpoint?.nestedText("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").orEmpty()
    val videoId = nestedText("navigationEndpoint", "watchEndpoint", "videoId")
        ?: nestedText("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId")
    val artwork = nestedArray("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.lastOrNull()?.asJsonObjectOrNull()?.text("url")?.let(::safeIosArtworkUrl)
    val subtitleRuns = nestedArray("subtitle", "runs").orEmpty().mapNotNull { it.asJsonObjectOrNull() }
    val subtitle = subtitleRuns.mapNotNull { it.text("text") }.joinToString("").trim().takeIf(String::isNotBlank)
    val artistRun = subtitleRuns.firstOrNull { run ->
        run.nestedText("navigationEndpoint", "browseEndpoint", "browseId")?.startsWith("UC") == true
    }
    val artist = artistRun?.text("text")?.takeIf(String::isNotBlank)
        ?: subtitleRuns.mapNotNull { it.text("text") }.firstOrNull { it.isNotBlank() && it !in setOf("·", "•", "|") }
        ?: "YouTube Music"
    val duration = nestedArray("subtitle", "runs").orEmpty().mapNotNull { it.asJsonObjectOrNull()?.text("text") }
        .lastOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }
        ?.split(':')?.mapNotNull(String::toIntOrNull)?.fold(0) { total, part -> total * 60 + part }

    if (videoId != null) {
        return IosMusicHomeItem.Song(IosMusicSearchTrack(videoId, title, artist, null, duration, artwork))
    }
    if (browseId.isNullOrBlank()) return null
    when {
        pageType.contains("ARTIST", ignoreCase = true) || browseId.startsWith("UC") ->
            return IosMusicHomeItem.Artist(IosMusicArtistResult(browseId, title, subtitle, artwork))
        pageType.contains("ALBUM", ignoreCase = true) || browseId.startsWith("MPREb_") || browseId.startsWith("OLAK") -> {
            val year = subtitleRuns.mapNotNull { it.text("text") }.firstNotNullOfOrNull(String::toIntOrNull)
            return IosMusicHomeItem.Album(IosMusicAlbumResult(browseId, playlistId(), title, artist, year, artwork))
        }
        else -> return IosMusicHomeItem.Playlist(browseId, playlistId(), title, subtitle, artwork)
    }
}

private fun JsonObject.playlistId(): String? = sequenceOf(
    nestedText("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "playlistId"),
    nestedText("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId"),
    nestedText("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "anyWatchEndpoint", "playlistId"),
).firstOrNull { !it.isNullOrBlank() }

private fun JsonObject.homeSectionList(): JsonObject? = nestedObject(
    "contents", "singleColumnBrowseResultsRenderer", "tabs", "0", "tabRenderer", "content", "sectionListRenderer",
)

private fun JsonObject.visitJsonObjects(visit: (JsonObject) -> Unit) {
    visit(this)
    values.forEach { value ->
        when (value) {
            is JsonObject -> value.visitJsonObjects(visit)
            is JsonArray -> value.forEach { child -> child.asJsonObjectOrNull()?.visitJsonObjects(visit) }
            else -> Unit
        }
    }
}

private fun JsonObject.nested(vararg path: String): JsonElement? {
    var value: JsonElement? = this
    path.forEach { key ->
        value = if (key.toIntOrNull() != null) (value as? JsonArray)?.getOrNull(key.toInt()) else (value as? JsonObject)?.get(key)
    }
    return value
}

private fun JsonObject.nestedObject(vararg path: String): JsonObject? = nested(*path).asJsonObjectOrNull()
private fun JsonObject.nestedArray(vararg path: String): JsonArray? = nested(*path).asJsonArrayOrNull()
private fun JsonObject.nestedText(vararg path: String): String? = nested(*path)?.jsonPrimitive?.contentOrNull
private fun JsonObject.allRunText(vararg path: String): String? = nestedArray(*path, "runs")
    ?.mapNotNull { it.asJsonObjectOrNull()?.text("text") }?.joinToString("")
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray
