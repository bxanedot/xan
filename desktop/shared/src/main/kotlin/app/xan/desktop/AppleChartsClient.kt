package app.xan.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

internal data class AppleChartItem(
    val rank: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val url: String?,
)

internal data class AppleCharts(
    val songs: List<AppleChartItem>,
    val albums: List<AppleChartItem>,
) {
    val artists: List<AppleChartItem>
        get() = songs.groupBy { it.artist.replace(Regex("\\s(?:feat\\.|ft\\.|&|,).*$", RegexOption.IGNORE_CASE), "").trim() }
            .entries.sortedByDescending { it.value.size }.take(15).mapIndexed { index, (name, tracks) ->
                AppleChartItem(index + 1, name, "Trending artist", tracks.firstNotNullOfOrNull { it.artworkUrl }, null)
            }
}

/** Public Apple RSS charts are used for discovery only; playback always resolves through YouTube Music. */
internal object AppleChartsClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        HttpClient(CIO) {
            engine { requestTimeout = 15_000 }
        }
    }

    suspend fun fetch(countryCode: String = Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" }): AppleCharts {
        val country = countryCode.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
        val songs = fetchChart(country, "100/songs")
        val albums = fetchChart(country, "20/albums")
        if (songs.isEmpty() && albums.isEmpty()) error("Apple Music charts returned no results for ${country.uppercase(Locale.ROOT)}.")
        return AppleCharts(songs, albums)
    }

    private suspend fun fetchChart(country: String, chart: String): List<AppleChartItem> {
        val response = client.get("https://rss.applemarketingtools.com/api/v2/$country/music/most-played/$chart.json")
        check(response.status.value in 200..299) { "Apple Music charts returned HTTP ${response.status.value}." }
        return parseChart(response.bodyAsText())
    }

    internal fun parseChart(source: String): List<AppleChartItem> {
        val results = json.parseToJsonElement(source).jsonObject["feed"]?.jsonObject?.get("results")?.jsonArray
            ?: error("Apple Music returned an invalid chart response.")
        return results.mapIndexedNotNull { index, value ->
            val item = value.jsonObject
            val title = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val artist = item["artistName"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "Unknown artist"
            val artwork = item["artworkUrl100"]?.jsonPrimitive?.contentOrNull?.replace(Regex("\\d+x\\d+"), "800x800")
            AppleChartItem(index + 1, title, artist, artwork, item["url"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
