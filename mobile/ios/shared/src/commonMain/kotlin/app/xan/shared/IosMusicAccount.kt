package app.xan.shared

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.date.GMTDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class IosMusicAccountProfile(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val artworkUrl: String?,
)

internal data class IosMusicAccountChannel(
    val dataSyncId: String,
    val name: String,
    val channelHandle: String?,
    val artworkUrl: String?,
)

internal data class IosMusicAccountState(
    val profile: IosMusicAccountProfile,
    val selectedDataSyncId: String?,
    val channels: List<IosMusicAccountChannel>,
)

/** Reads the same signed-in profile and channel identities as Android's InnerTube account screens. */
internal object IosMusicAccount {
    private const val accountMenuEndpoint = "$IOS_YOUTUBE_MUSIC_ORIGIN/youtubei/v1/account/account_menu?prettyPrint=false"
    private const val accountSwitcherEndpoint = "$IOS_YOUTUBE_MUSIC_ORIGIN/getAccountSwitcherEndpoint?prettyPrint=false"
    private const val userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 Version/18.2 Mobile/15E148 Safari/604.1"
    private val client = newRoomHttpClient()

    suspend fun refresh(): IosMusicAccountState {
        val cookie = IosCredentialStorage.youtubeCookie()
            ?: error("Connect a YouTube Music account with its session cookie first.")
        val selectedId = normalizeIosDataSyncId(readIosPreference("account.dataSyncId"))
        val profile = fetchProfile(cookie, selectedId)
        val channels = fetchChannels(cookie)
        return IosMusicAccountState(profile, selectedId, channels)
    }

    private suspend fun fetchProfile(cookie: String, dataSyncId: String?): IosMusicAccountProfile {
        val request = buildJsonObject {
            put("context", iosYouTubeContext(dataSyncId = dataSyncId))
            put("deviceTheme", "DEVICE_THEME_SELECTED")
            put("userInterfaceTheme", "USER_INTERFACE_THEME_DARK")
        }
        val response = client.post(accountMenuEndpoint) {
            contentType(ContentType.Application.Json)
            headers { applyYoutubeHeaders(cookie) }
            setBody(request.toString())
        }
        require(response.status.value in 200..299) {
            "YouTube Music account profile returned HTTP ${response.status.value}. Check that the saved cookie is current."
        }
        return parseIosMusicAccountProfile(response.bodyAsText())
    }

    private suspend fun fetchChannels(cookie: String): List<IosMusicAccountChannel> {
        val response = client.get(accountSwitcherEndpoint) {
            headers { applyYoutubeHeaders(cookie) }
        }
        require(response.status.value in 200..299) {
            "YouTube Music channels returned HTTP ${response.status.value}. Check that the saved cookie is current."
        }
        return parseIosMusicAccountChannels(response.bodyAsText())
    }

    private fun io.ktor.http.HeadersBuilder.applyYoutubeHeaders(cookie: String) {
        append("X-Goog-Api-Format-Version", "1")
        append("X-YouTube-Client-Name", "67")
        append("X-YouTube-Client-Version", IOS_YOUTUBE_MUSIC_CLIENT_VERSION)
        append("Origin", IOS_YOUTUBE_MUSIC_ORIGIN)
        append("X-Origin", IOS_YOUTUBE_MUSIC_ORIGIN)
        append("Referer", "$IOS_YOUTUBE_MUSIC_ORIGIN/")
        append("User-Agent", userAgent)
        append("Cookie", cookie)
        iosSapisidAuthorization(cookie, IOS_YOUTUBE_MUSIC_ORIGIN, GMTDate().timestamp / 1_000L)
            ?.let { append("Authorization", it) }
    }
}

internal fun parseIosMusicAccountProfile(payload: String): IosMusicAccountProfile {
    val root = Json.parseToJsonElement(payload) as? JsonObject
        ?: error("YouTube Music returned an invalid account profile.")
    val renderer = root.accountNested(
        "actions", "0", "openPopupAction", "popup", "multiPageMenuRenderer", "header", "activeAccountHeaderRenderer",
    ) as? JsonObject ?: error("YouTube Music did not include the signed-in account profile.")
    val name = renderer.accountText("accountName", "runs", "0", "text")?.trim()
        ?.takeIf(String::isNotEmpty) ?: error("YouTube Music did not include an account name.")
    val thumbnails = renderer.accountNested("accountPhoto", "thumbnails") as? JsonArray
    return IosMusicAccountProfile(
        name = name,
        email = renderer.accountText("email", "runs", "0", "text")?.takeIf(String::isNotBlank),
        channelHandle = renderer.accountText("channelHandle", "runs", "0", "text")?.takeIf(String::isNotBlank),
        artworkUrl = thumbnails?.lastOrNull()?.accountObjectText("url")?.let(::safeIosArtworkUrl),
    )
}

internal fun parseIosMusicAccountChannels(payload: String): List<IosMusicAccountChannel> {
    val raw = payload.removePrefix(")]}'\n")
    val root = Json.parseToJsonElement(raw) as? JsonObject
        ?: error("YouTube Music returned an invalid channel list.")
    if (root.accountText("code") != "SUCCESS") return emptyList()
    val items = root.accountNested(
        "data", "contents", "0", "accountSectionListRenderer", "contents", "0", "accountItemSectionRenderer", "contents",
    ) as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val item = (element as? JsonObject)?.get("accountItem") as? JsonObject ?: return@mapNotNull null
        val name = item.accountText("accountName", "simpleText")?.trim()?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val dataSyncId = normalizeIosDataSyncId(
            (item.accountNested("serviceEndpoint", "selectActiveIdentityEndpoint", "supportedTokens") as? JsonArray)
                ?.firstNotNullOfOrNull { token ->
                    (token as? JsonObject)?.accountText("datasyncIdToken", "datasyncIdToken")
                },
        ) ?: return@mapNotNull null
        val thumbnails = item.accountNested("accountPhoto", "thumbnails") as? JsonArray
        IosMusicAccountChannel(
            dataSyncId = dataSyncId,
            name = name,
            channelHandle = item.accountText("channelHandle", "simpleText")?.takeIf(String::isNotBlank),
            artworkUrl = thumbnails?.lastOrNull()?.accountObjectText("url")?.let(::safeIosArtworkUrl),
        )
    }.distinctBy(IosMusicAccountChannel::dataSyncId)
}

private fun JsonObject.accountNested(vararg path: String): JsonElement? {
    var value: JsonElement? = this
    for (key in path) {
        value = if (key.toIntOrNull() != null) (value as? JsonArray)?.getOrNull(key.toInt()) else (value as? JsonObject)?.get(key)
            ?: return null
    }
    return value
}

private fun JsonObject.accountText(vararg path: String): String? = accountNested(*path)?.jsonPrimitive?.contentOrNull
private fun JsonElement?.accountObjectText(key: String): String? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
