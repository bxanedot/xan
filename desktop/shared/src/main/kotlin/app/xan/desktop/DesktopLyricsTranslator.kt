package app.xan.desktop

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends lyrics only after an explicit Translate action, using the user's own OpenRouter key. */
internal object DesktopLyricsTranslator {
    private const val endpoint = "https://openrouter.ai/api/v1/chat/completions"
    private const val defaultModel = "google/gemini-2.5-flash-lite"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translate(lines: List<String>, targetLanguage: String, apiKey: String, romanize: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add an OpenRouter API key in Settings → Lyrics first." }
        require(romanize || targetLanguage.isNotBlank()) { "Choose a target language in Settings → Lyrics first." }
        require(lines.isNotEmpty()) { "There are no lyrics to translate." }
        buildList(lines.size) {
            lines.chunked(24).forEach { chunk ->
                val systemPrompt = """You translate song lyrics. Return only a JSON array of strings with exactly ${chunk.size} entries. Keep one output for each input line, preserve blank lines and punctuation, and return no explanation."""
                val instruction = if (romanize) {
                    "Convert each line into a simple Latin-script phonetic romanization. Do not translate meaning. Use basic ASCII letters where possible and keep the original line order and punctuation."
                } else {
                    "Translate these lyrics into $targetLanguage. Keep their meaning and poetic tone, and preserve the line order."
                }
                val userPrompt = """$instruction

Input:
${chunk.joinToString("\n")}"""
                val body = buildJsonObject {
                    put("model", defaultModel)
                    put("temperature", 0.3)
                    put("max_tokens", chunk.size * 100)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                        add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                    })
                }.toString()
                val request = HttpRequest.newBuilder(URI(endpoint))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://github.com/bxanedot/xan")
                    .header("X-Title", "XAN Desktop")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val message = runCatching {
                        json.parseToJsonElement(response.body()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    error(message ?: "OpenRouter returned HTTP ${response.statusCode()}.")
                }
                val content = json.parseToJsonElement(response.body()).jsonObject
                    .getValue("choices").jsonArray.first().jsonObject
                    .getValue("message").jsonObject.getValue("content").jsonPrimitive.content
                val candidate = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val arrayStart = candidate.indexOf('[')
                val arrayEnd = candidate.lastIndexOf(']')
                require(arrayStart >= 0 && arrayEnd > arrayStart) { "The translation service did not return a lyrics array." }
                val translated = json.parseToJsonElement(candidate.substring(arrayStart, arrayEnd + 1)).jsonArray
                    .map { element -> element.jsonPrimitive.contentOrNull.orEmpty() }
                addAll(translated.take(chunk.size))
                repeat(chunk.size - translated.size) { add("") }
            }
        }
    }
}
