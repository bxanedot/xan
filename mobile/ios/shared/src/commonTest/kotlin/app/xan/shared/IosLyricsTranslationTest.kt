package app.xan.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosLyricsTranslationTest {
    @Test
    fun userTriggeredTranslationSendsTheSavedKeyAndKeepsLineAlignment() = runBlocking {
        val previousConfig = IosLyricsTranslation.config()
        val previousCache = IosLyricsTranslation.cachePreferenceForTests()
        var requestCount = 0
        var authorization: String? = null
        val mockClient = HttpClient(MockEngine { request ->
            requestCount += 1
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = ByteReadChannel("""{"choices":[{"message":{"content":"[\"Primera\",\"\",\"Tercera\"]"}}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val keyStore = MemoryLyricsTranslationKeyStore()
        IosLyricsTranslation.install(keyStore)
        IosLyricsTranslation.installHttpClientForTests(mockClient)
        IosLyricsTranslation.setCachePreferenceForTests("")
        IosLyricsTranslation.setEndpoint("https://translator.example.test/v1/chat/completions")
        IosLyricsTranslation.setModel("smoke-model")
        IosLyricsTranslation.setTargetLanguage("es")
        IosLyricsTranslation.setMode(IosLyricsTranslationMode.TRANSLATE)
        assertTrue(IosLyricsTranslation.saveApiKey("test-translation-key"))

        try {
            val sourceLines = listOf("First line", "", "Third line")
            IosLyricsTranslation.translate("translation-flow-track", "Example", "Artist", sourceLines)

            assertEquals("Bearer test-translation-key", authorization)
            assertEquals(1, requestCount)
            assertEquals(sourceLines.indices.map { listOf("Primera", "", "Tercera")[it] }, IosLyricsTranslation.state.value.lines)
            assertEquals("translation-flow-track", IosLyricsTranslation.state.value.trackId)
            IosLyricsTranslation.reloadCacheForTests()
            IosLyricsTranslation.clear()
            assertTrue(IosLyricsTranslation.restoreCached("translation-flow-track", "Example", "Artist", sourceLines))
            IosLyricsTranslation.translate("translation-flow-track", "Example", "Artist", sourceLines)
            assertEquals(1, requestCount, "a cached translation should survive cache reload")
            IosLyricsTranslation.setModel("different-model")
            IosLyricsTranslation.translate("translation-flow-track", "Example", "Artist", sourceLines)
            assertEquals(2, requestCount, "changing the model must not reuse a result from the previous provider configuration")
            IosLyricsTranslation.clearSavedTranslations()
            assertEquals(0, IosLyricsTranslation.cachedTranslationCount())
            assertEquals("[]", IosLyricsTranslation.cachePreferenceForTests())
        } finally {
            IosLyricsTranslation.clearApiKey()
            IosLyricsTranslation.clear()
            IosLyricsTranslation.install(null)
            IosLyricsTranslation.setEndpoint(previousConfig.endpoint)
            IosLyricsTranslation.setModel(previousConfig.model)
            IosLyricsTranslation.setTargetLanguage(previousConfig.targetLanguage)
            IosLyricsTranslation.setMode(previousConfig.mode)
            IosLyricsTranslation.setCachePreferenceForTests(previousCache)
            IosLyricsTranslation.installHttpClientForTests(null)
            mockClient.close()
        }
    }

    @Test
    fun parsesExactlyOneTranslatedEntryPerSourceLine() {
        val payload = """{"choices":[{"message":{"content":"[\"Hola\",\"\"]"}}]}"""

        assertEquals(listOf("Hola", ""), parseIosLyricsTranslationResponse(payload, expectedLines = 2))
        assertFailsWith<IllegalArgumentException> {
            parseIosLyricsTranslationResponse(payload, expectedLines = 1)
        }
    }

    @Test
    fun rejectsMissingOrNonTextProviderLines() {
        assertFailsWith<IllegalStateException> {
            parseIosLyricsTranslationResponse("{}", expectedLines = 1)
        }
        assertFailsWith<IllegalStateException> {
            parseIosLyricsTranslationResponse(
                """{"choices":[{"message":{"content":"[1]"}}]}""",
                expectedLines = 1,
            )
        }
    }

    @Test
    fun promptsPreserveTheDifferenceBetweenTranslationAndRomanization() {
        val lines = listOf("東京", "안녕하세요")
        val romanization = iosLyricsTranslationPrompt("Song", "Artist", lines, "en", IosLyricsTranslationMode.ROMANIZED)
        val translation = iosLyricsTranslationPrompt("Song", "Artist", lines, "en", IosLyricsTranslationMode.TRANSLATE)

        assertTrue(romanization.contains("without translating their meaning"))
        assertTrue(translation.contains("Translate the lyrics into en"))
        assertTrue(romanization.contains("\"東京\""))
        assertFalse(IosLyricsTranslationMode.fromPreference("unknown") == IosLyricsTranslationMode.ROMANIZED)
    }

    @Test
    fun acceptsOnlySecureApiEndpoints() {
        assertTrue(isSafeIosTranslationEndpoint("https://openrouter.ai/api/v1/chat/completions"))
        assertFalse(isSafeIosTranslationEndpoint("http://openrouter.ai/api/v1/chat/completions"))
        assertFalse(isSafeIosTranslationEndpoint("not a URL"))
    }

    private class MemoryLyricsTranslationKeyStore : IosLyricsTranslationKeyStore {
        private var key: String? = null
        override fun loadLyricsTranslationKey(): String? = key
        override fun saveLyricsTranslationKey(key: String?): Boolean {
            this.key = key
            return true
        }
    }
}
