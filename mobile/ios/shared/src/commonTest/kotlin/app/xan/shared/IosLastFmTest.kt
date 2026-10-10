package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosLastFmTest {
    @Test
    fun signsSortedLastFmParametersIncludingTheSharedSecret() {
        assertEquals(
            "api_keykey123methodauth.getTokensecret-value",
            lastFmSignatureInput(mapOf("method" to "auth.getToken", "api_key" to "key123"), "secret-value"),
        )
    }

    @Test
    fun usesAndroidScrobbleDurationAndDelayThresholds() {
        assertNull(lastFmScrobbleThresholdMillis(0))
        assertNull(lastFmScrobbleThresholdMillis(30_000))
        assertEquals(15_500L, lastFmScrobbleThresholdMillis(31_000))
        assertEquals(50_000L, lastFmScrobbleThresholdMillis(300_000))
    }
}
