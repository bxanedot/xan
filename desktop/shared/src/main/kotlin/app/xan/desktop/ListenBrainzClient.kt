package app.xan.desktop

import java.net.HttpURLConnection
import java.net.URI

internal data class ScrobbleOutcome(
    val delivered: List<String> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
    val deduplicated: List<String> = emptyList(),
)

/** Keeps a playback event idempotent per service and avoids concurrent duplicate sends. */
internal class ScrobbleSubmissionLedger(private val retention: Int = 512) {
    private val successful = LinkedHashSet<String>()
    private val inFlight = mutableSetOf<String>()

    @Synchronized fun claim(eventId: String, service: String): Boolean {
        val key = "$eventId:$service"
        if (key in successful || key in inFlight) return false
        inFlight += key
        return true
    }

    @Synchronized fun finish(eventId: String, service: String, succeeded: Boolean) {
        val key = "$eventId:$service"
        inFlight -= key
        if (succeeded) {
            successful += key
            while (successful.size > retention.coerceAtLeast(1)) successful.remove(successful.first())
        }
    }
}

/** Minimal ListenBrainz v1 client. Token validation and submissions stay behind this boundary. */
internal object ListenBrainzClient {
    private const val API_BASE = "https://api.listenbrainz.org/1"

    fun validateToken(token: String, apiBase: String = API_BASE): String {
        require(token.isNotBlank()) { "Enter a ListenBrainz user token." }
        val (status, body) = request("$apiBase/validate-token", "GET", token)
        check(status in 200..299) { "ListenBrainz token check failed (HTTP $status)." }
        return parseValidationResponse(body)
    }

    internal fun parseValidationResponse(body: String): String {
        check(Regex("\"valid\"\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            "ListenBrainz rejected this token. Check it and try again."
        }
        return Regex("\"user_name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(body)?.groupValues?.get(1)?.unescapeJson().orEmpty().ifBlank { "ListenBrainz user" }
    }

    fun submitListen(token: String, track: MusicTrack, listenedAtSeconds: Long, version: String, apiBase: String = API_BASE) {
        require(track.title.isNotBlank() && track.artist.isNotBlank()) { "A title and artist are required to submit a listen." }
        val (status, body) = request(
            "$apiBase/submit-listens",
            "POST",
            token,
            singleListenPayload(track, listenedAtSeconds, version),
        )
        check(status in 200..299) {
            "ListenBrainz submission failed (HTTP $status)${body.take(240).takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
        }
    }

    internal fun singleListenPayload(track: MusicTrack, listenedAtSeconds: Long, version: String): String {
        val metadata = buildList {
            add("\"artist_name\":${track.artist.jsonString()}")
            add("\"track_name\":${track.title.jsonString()}")
            track.album.takeIf(String::isNotBlank)?.let { add("\"release_name\":${it.jsonString()}") }
            val additional = buildList {
                track.durationSeconds?.let { add("\"duration_ms\":${it * 1000L}") }
                add("\"submission_client\":\"XAN Desktop\"")
                add("\"submission_client_version\":${version.jsonString()}")
            }.joinToString(",")
            add("\"additional_info\":{$additional}")
        }.joinToString(",")
        return """{"listen_type":"single","payload":[{"listened_at":$listenedAtSeconds,"track_metadata":{$metadata}}]}"""
    }

    private fun request(url: String, method: String, token: String, body: String? = null): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Token ${token.trim()}")
            connection.setRequestProperty("User-Agent", "XAN Desktop/${XAN_DESKTOP_VERSION}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to response
        } finally {
            connection.disconnect()
        }
    }

    private fun String.jsonString(): String = buildString(length + 2) {
        append('"')
        this@jsonString.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun String.unescapeJson(): String = replace(Regex("\\\\u([0-9a-fA-F]{4})")) {
        it.groupValues[1].toInt(16).toChar().toString()
    }.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
}
