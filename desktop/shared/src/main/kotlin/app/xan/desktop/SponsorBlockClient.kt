package app.xan.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class SponsorSegment(val startMillis: Long, val endMillis: Long, val category: String)

/** Read-only SponsorBlock client. Uses the API's 4-character hash-prefix endpoint for K-Anonymity. */
internal object SponsorBlockClient {
    private const val API_ROOT = "https://sponsor.ajay.app/api/skipSegments"
    private val cache = ConcurrentHashMap<String, CachedSegments>()
    private val cacheTtlMillis = 6 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    fun segments(videoId: String, categories: List<String>, apiRoot: String = API_ROOT): List<SponsorSegment> {
        require(Regex("^[A-Za-z0-9_-]{11}$").matches(videoId)) { "SponsorBlock needs a valid YouTube video ID." }
        val selected = categories.distinct().filter { it in SponsorBlockCategories.all }
        if (selected.isEmpty()) return emptyList()
        val key = "$apiRoot:$videoId:${selected.sorted().joinToString(",")}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.fetchedAt < cacheTtlMillis }?.let { return it.segments }
        val prefix = sha256(videoId).take(4)
        val categoriesJson = selected.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        val actionsJson = "[\"skip\"]"
        val query = "categories=${encode(categoriesJson)}&actionTypes=${encode(actionsJson)}"
        val url = "$apiRoot/$prefix?$query"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("User-Agent", "XAN Desktop/$XAN_DESKTOP_VERSION")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return emptyList<SponsorSegment>().also { cache[key] = CachedSegments(it, System.currentTimeMillis()) }
            check(status in 200..299) { "SponsorBlock request failed (HTTP $status)." }
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val result = parseResponse(videoId, body, selected)
            cache[key] = CachedSegments(result, System.currentTimeMillis())
            return result
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(videoId: String, body: String, requestedCategories: List<String>): List<SponsorSegment> {
        val results = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        val accepted = requestedCategories.toSet()
        return results.flatMap { element ->
            val video = element as? JsonObject ?: return@flatMap emptyList()
            if (video["videoID"]?.jsonPrimitive?.content != videoId) return@flatMap emptyList()
            val segments = video["segments"] as? JsonArray ?: return@flatMap emptyList()
            segments.mapNotNull { segmentElement ->
                val item = segmentElement as? JsonObject ?: return@mapNotNull null
                val category = item["category"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val action = item["actionType"]?.jsonPrimitive?.content ?: "skip"
                if (category !in accepted || action != "skip") return@mapNotNull null
                val times = item["segment"] as? JsonArray ?: return@mapNotNull null
                if (times.size < 2) return@mapNotNull null
                val start = (times[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null) * 1000.0
                val end = (times[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null) * 1000.0
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return@mapNotNull null
                SponsorSegment(start.toLong(), end.toLong(), category)
            }
        }.sortedBy { it.startMillis }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private data class CachedSegments(val segments: List<SponsorSegment>, val fetchedAt: Long)
}

internal object SponsorBlockCategories {
    val all = listOf("sponsor", "selfpromo", "interaction", "music_offtopic", "intro", "outro", "preview", "hook", "filler")
    val default = listOf("sponsor", "selfpromo", "interaction", "music_offtopic")
    val labels = mapOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Self-promotion",
        "interaction" to "Subscription reminders",
        "music_offtopic" to "Non-music section",
        "intro" to "Intro / intermission",
        "outro" to "Endcards / credits",
        "preview" to "Preview / recap",
        "hook" to "Hook / greeting",
        "filler" to "Filler / tangents",
    )
}

internal fun sponsorSegmentAt(segments: List<SponsorSegment>, positionMillis: Long): SponsorSegment? =
    segments.firstOrNull { positionMillis >= it.startMillis && positionMillis < it.endMillis }

internal fun sponsorSegmentTarget(segment: SponsorSegment, durationMillis: Long): Long =
    (segment.endMillis + 50L).let { end -> if (durationMillis > 0) end.coerceAtMost(durationMillis) else end }
