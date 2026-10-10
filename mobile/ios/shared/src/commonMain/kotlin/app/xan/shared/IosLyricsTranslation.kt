package app.xan.shared

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

internal enum class IosLyricsTranslationMode(val storedValue: String) {
    TRANSLATE("Literal"),
    ROMANIZED("Romanized"),
    TRANSCRIBED("Transcribed"),
    ;

    companion object {
        fun fromPreference(value: String?): IosLyricsTranslationMode =
            entries.firstOrNull { it.storedValue == value } ?: TRANSLATE
    }
}

internal data class IosLyricsTranslationConfig(
    val endpoint: String = DEFAULT_IOS_LYRICS_TRANSLATION_ENDPOINT,
    val model: String = DEFAULT_IOS_LYRICS_TRANSLATION_MODEL,
    val targetLanguage: String = "en",
    val mode: IosLyricsTranslationMode = IosLyricsTranslationMode.TRANSLATE,
)

internal data class IosLyricsTranslationState(
    val trackId: String? = null,
    val lines: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

interface IosLyricsTranslationKeyStore {
    fun loadLyricsTranslationKey(): String?
    fun saveLyricsTranslationKey(key: String?): Boolean
}

private const val DEFAULT_IOS_LYRICS_TRANSLATION_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
private const val DEFAULT_IOS_LYRICS_TRANSLATION_MODEL = "google/gemini-2.5-flash-lite"
private const val MAX_IOS_TRANSLATION_LINES = 500
private const val MAX_IOS_TRANSLATION_CHARS = 48 * 1024
private const val MAX_IOS_TRANSLATION_RESPONSE_CHARS = 512 * 1024
private const val IOS_TRANSLATION_CACHE_KEY = "lyrics.translation.cache"
private const val MAX_IOS_TRANSLATION_CACHE_ENTRIES = 30
private const val MAX_IOS_TRANSLATION_CACHE_CHARS = 256 * 1024

/** User-triggered OpenAI-compatible lyrics translation and romanization. API credentials live in Keychain. */
internal object IosLyricsTranslation {
    private var client = newRoomHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = linkedMapOf<String, List<String>>().apply { putAll(loadIosLyricsTranslationCache()) }
    private var keyStore: IosLyricsTranslationKeyStore? = null
    private val mutableState = MutableStateFlow(IosLyricsTranslationState())
    val state = mutableState.asStateFlow()

    fun install(store: IosLyricsTranslationKeyStore?) {
        keyStore = store
    }

    internal fun installHttpClientForTests(testClient: io.ktor.client.HttpClient?) {
        client.close()
        client = testClient ?: newRoomHttpClient()
    }

    internal fun reloadCacheForTests() {
        cache.clear()
        cache.putAll(loadIosLyricsTranslationCache())
    }

    internal fun cachePreferenceForTests(): String? = readIosPreference(IOS_TRANSLATION_CACHE_KEY)

    internal fun setCachePreferenceForTests(value: String?) {
        writeIosPreference(IOS_TRANSLATION_CACHE_KEY, value.orEmpty())
        reloadCacheForTests()
    }

    fun cachedTranslationCount(): Int = cache.size

    fun clearSavedTranslations() {
        cache.clear()
        runCatching { writeIosPreference(IOS_TRANSLATION_CACHE_KEY, "[]") }
        clear()
    }

    fun hasApiKey(): Boolean = runCatching { keyStore?.loadLyricsTranslationKey()?.isNotBlank() == true }.getOrDefault(false)

    fun saveApiKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.length !in 8..512 || key.any(Char::isWhitespace)) return false
        return runCatching { keyStore?.saveLyricsTranslationKey(key) == true }.getOrDefault(false)
    }

    fun clearApiKey(): Boolean = runCatching { keyStore?.saveLyricsTranslationKey(null) == true }.getOrDefault(false)

    fun config(): IosLyricsTranslationConfig = IosLyricsTranslationConfig(
        endpoint = readIosPreference("lyrics.translationEndpoint")?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_IOS_LYRICS_TRANSLATION_ENDPOINT,
        model = readIosPreference("lyrics.translationModel")?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_IOS_LYRICS_TRANSLATION_MODEL,
        targetLanguage = readIosPreference("lyrics.translationLanguage")?.trim()?.takeIf(String::isNotBlank) ?: "en",
        mode = IosLyricsTranslationMode.fromPreference(readIosPreference("lyrics.translationMode")),
    )

    fun setEndpoint(value: String) = writeIosPreference("lyrics.translationEndpoint", value.trim().take(2_000))
    fun setModel(value: String) = writeIosPreference("lyrics.translationModel", value.trim().take(200))
    fun setTargetLanguage(value: String) = writeIosPreference("lyrics.translationLanguage", value.trim().take(40))
    fun setMode(value: IosLyricsTranslationMode) = writeIosPreference("lyrics.translationMode", value.storedValue)

    suspend fun translate(trackId: String, title: String, artist: String, sourceLines: List<String>) {
        if (sourceLines.isEmpty() || sourceLines.size > MAX_IOS_TRANSLATION_LINES) {
            mutableState.value = IosLyricsTranslationState(trackId, error = "This lyrics document is too large to translate.")
            return
        }
        val lines = sourceLines.map { it.take(2_000) }
        if (lines.sumOf(String::length) > MAX_IOS_TRANSLATION_CHARS) {
            mutableState.value = IosLyricsTranslationState(trackId, error = "This lyrics document is too large to translate.")
            return
        }
        val apiKey = runCatching { keyStore?.loadLyricsTranslationKey()?.takeIf(String::isNotBlank) }.getOrNull()
        if (apiKey == null) {
            mutableState.value = IosLyricsTranslationState(trackId, error = "Save an OpenRouter-compatible API key in Lyrics settings first.")
            return
        }
        val config = config()
        if (config.targetLanguage.isBlank() || config.targetLanguage.length > 40 || config.model.length > 200 || !isSafeIosTranslationEndpoint(config.endpoint)) {
            mutableState.value = IosLyricsTranslationState(trackId, error = "Check the translation endpoint, model, and target language in Lyrics settings.")
            return
        }
        val safeTitle = title.take(200)
        val safeArtist = artist.take(200)
        val cacheKey = iosLyricsTranslationCacheKey(trackId, safeTitle, safeArtist, lines, config)
        cache[cacheKey]?.let { cached ->
            mutableState.value = IosLyricsTranslationState(trackId, cached)
            return
        }
        mutableState.value = IosLyricsTranslationState(trackId = trackId, isLoading = true)
        try {
            val prompt = iosLyricsTranslationPrompt(safeTitle, safeArtist, lines, config.targetLanguage, config.mode)
            val requestBody = buildJsonObject {
                put("model", config.model)
                put("temperature", 0.3)
                put("max_tokens", (lines.size * 100).coerceIn(100, 32_000))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", "Return only a valid JSON array of strings with exactly ${lines.size} entries. Keep each source line aligned with one output line and preserve empty lines.")
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }
            var lastError: String? = null
            for (attempt in 0..2) {
                val (status, payload) = withTimeout(90_000L) {
                    val response = client.post(config.endpoint) {
                        contentType(ContentType.Application.Json)
                        header("Authorization", "Bearer $apiKey")
                        header("HTTP-Referer", "https://github.com/bxanedot/xan")
                        header("X-Title", "XAN")
                        setBody(requestBody.toString())
                    }
                    response.status.value to response.bodyAsText()
                }
                require(payload.length <= MAX_IOS_TRANSLATION_RESPONSE_CHARS) { "Translation provider returned an oversized response." }
                if (status in 200..299) {
                    val translated = parseIosLyricsTranslationResponse(payload, lines.size)
                    cache[cacheKey] = translated
                    trimIosLyricsTranslationCache(cache)
                    persistIosLyricsTranslationCache(cache)
                    mutableState.value = IosLyricsTranslationState(trackId, translated)
                    return
                }
                lastError = translationError(payload, status)
                if (status < 500 && status != 429) break
                if (attempt < 2) delay(500L * (attempt + 1))
            }
            mutableState.value = IosLyricsTranslationState(trackId, error = lastError ?: "Translation service did not return lyrics.")
        } catch (cancelled: CancellationException) {
            if (mutableState.value.trackId == trackId) mutableState.value = IosLyricsTranslationState()
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = IosLyricsTranslationState(
                trackId = trackId,
                error = failure.message?.take(240) ?: "Could not translate these lyrics.",
            )
        }
    }

    fun clear() {
        mutableState.value = IosLyricsTranslationState()
    }

    fun restoreCached(trackId: String, title: String, artist: String, sourceLines: List<String>): Boolean {
        if (sourceLines.isEmpty() || sourceLines.size > MAX_IOS_TRANSLATION_LINES) return false
        val config = config()
        val lines = sourceLines.map { it.take(2_000) }
        if (lines.sumOf(String::length) > MAX_IOS_TRANSLATION_CHARS) return false
        val key = iosLyricsTranslationCacheKey(trackId, title.take(200), artist.take(200), lines, config)
        val translated = cache[key] ?: return false
        if (translated.size != sourceLines.size) return false
        mutableState.value = IosLyricsTranslationState(trackId, translated)
        return true
    }

    private fun translationError(payload: String, status: Int): String {
        val remote = runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return remote?.take(200) ?: "Translation provider returned HTTP $status."
    }
}

internal fun isSafeIosTranslationEndpoint(endpoint: String): Boolean = runCatching {
    val url = URLBuilder(endpoint).build()
    url.protocol.name == "https" && url.host.isNotBlank() && endpoint.length <= 2_000
}.getOrDefault(false)

internal fun iosLyricsTranslationPrompt(
    title: String,
    artist: String,
    lines: List<String>,
    targetLanguage: String,
    mode: IosLyricsTranslationMode,
): String {
    val task = when (mode) {
        IosLyricsTranslationMode.TRANSLATE -> "Translate the lyrics into $targetLanguage. Keep the poetic meaning and natural phrasing."
        IosLyricsTranslationMode.ROMANIZED -> "Romanize the lyrics into basic Latin letters without translating their meaning. Use plain ASCII where possible."
        IosLyricsTranslationMode.TRANSCRIBED -> "Write the pronunciation of the lyrics in the native script used by $targetLanguage. Do not translate their meaning."
    }
    val source = buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } }
    return "$task\nTrack: $title — $artist\nReturn a JSON array containing exactly ${lines.size} strings, one for each source line. Preserve empty lines.\nSource lines:\n$source"
}

internal fun iosLyricsTranslationCacheKey(
    trackId: String,
    title: String,
    artist: String,
    lines: List<String>,
    config: IosLyricsTranslationConfig,
): String = buildJsonArray {
    add(JsonPrimitive(trackId))
    add(JsonPrimitive(title))
    add(JsonPrimitive(artist))
    add(JsonPrimitive(config.endpoint))
    add(JsonPrimitive(config.model))
    add(JsonPrimitive(config.targetLanguage))
    add(JsonPrimitive(config.mode.storedValue))
    add(buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
}.toString()

internal fun parseIosLyricsTranslationResponse(payload: String, expectedLines: Int): List<String> {
    require(payload.length <= MAX_IOS_TRANSLATION_RESPONSE_CHARS) { "Translation provider returned an oversized response." }
    val root = Json.parseToJsonElement(payload).jsonObject
    val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        ?: error("Translation provider returned no translated text.")
    val jsonContent = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val translated = Json.parseToJsonElement(jsonContent).jsonArray.map { element ->
        val primitive = element as? JsonPrimitive ?: error("Translation provider returned a non-text line.")
        check(primitive.isString) { "Translation provider returned a non-text line." }
        primitive.content
    }
    require(translated.size == expectedLines) { "Translation provider returned ${translated.size} lines; expected $expectedLines." }
    return translated
}

private fun loadIosLyricsTranslationCache(): Map<String, List<String>> = runCatching {
    val encoded = readIosPreference(IOS_TRANSLATION_CACHE_KEY)?.takeIf {
        it.isNotBlank() && it.length <= MAX_IOS_TRANSLATION_CACHE_CHARS
    } ?: return emptyMap()
    Json.parseToJsonElement(encoded).jsonArray.mapNotNull { entry ->
        val pair = entry.jsonArray
        val key = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val lineArray = pair.getOrNull(1) as? JsonArray ?: return@mapNotNull null
        val lines = lineArray.map { line ->
            val primitive = line as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            primitive.content
        }
        key to lines
    }.takeLast(MAX_IOS_TRANSLATION_CACHE_ENTRIES).toMap(LinkedHashMap())
}.getOrDefault(emptyMap())

private fun persistIosLyricsTranslationCache(cache: LinkedHashMap<String, List<String>>) {
    runCatching {
        var encoded = encodeIosLyricsTranslationCache(cache)
        while (encoded.length > MAX_IOS_TRANSLATION_CACHE_CHARS && cache.isNotEmpty()) {
            cache.remove(cache.keys.first())
            encoded = encodeIosLyricsTranslationCache(cache)
        }
        writeIosPreference(IOS_TRANSLATION_CACHE_KEY, encoded)
    }
}

private fun trimIosLyricsTranslationCache(cache: LinkedHashMap<String, List<String>>) {
    while (cache.size > MAX_IOS_TRANSLATION_CACHE_ENTRIES) cache.remove(cache.keys.first())
}

private fun encodeIosLyricsTranslationCache(cache: Map<String, List<String>>): String = buildJsonArray {
    cache.forEach { (key, lines) ->
        add(buildJsonArray {
            add(JsonPrimitive(key))
            add(buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
        })
    }
}.toString()
