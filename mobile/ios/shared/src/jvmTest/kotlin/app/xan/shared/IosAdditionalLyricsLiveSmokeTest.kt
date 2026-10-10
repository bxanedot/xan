package app.xan.shared

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/** Opt in with -Pxan.liveIosPaxsenix=true or -Pxan.liveIosKuGou=true. */
class IosAdditionalLyricsLiveSmokeTest {
    @Test
    fun fetchesWordSyncedPaxsenixLyrics() = runBlocking {
        assumeTrue("Set -Pxan.liveIosPaxsenix=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosPaxsenix"))
        val result = withTimeout(45_000) {
            IosLyrics.fetchPaxsenix("Never Gonna Give You Up", "Rick Astley", 213, "Whenever You Need Somebody")
        }
        assertNotNull("Paxsenix did not return matching lyrics.", result)
        assertFalse("Paxsenix returned no lyrics lines.", result?.lines.isNullOrEmpty())
    }

    @Test
    fun fetchesKuGouLyrics() = runBlocking {
        assumeTrue("Set -Pxan.liveIosKuGou=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosKuGou"))
        val result = withTimeout(30_000) {
            IosLyrics.fetchKuGou("Never Gonna Give You Up", "Rick Astley", 213, "Whenever You Need Somebody")
        }
        assertNotNull("KuGou did not return matching lyrics.", result)
        assertFalse("KuGou returned no synced lines.", result?.lines.isNullOrEmpty())
    }

    @Test
    fun fetchesMusixmatchLyricsWithSignedRequests() = runBlocking {
        assumeTrue("Set -Pxan.liveIosMusixmatch=true to enable the network smoke test.", java.lang.Boolean.getBoolean("xan.liveIosMusixmatch"))
        IosLyrics.installNativeCrypto(JvmLyricsCrypto)
        try {
            val result = withTimeout(30_000) {
                IosLyrics.fetchMusixmatch("Never Gonna Give You Up", "Rick Astley", 213, "Whenever You Need Somebody")
            }
            assertNotNull("Musixmatch did not return matching lyrics.", result)
            assertFalse("Musixmatch returned no lyric lines.", result?.lines.isNullOrEmpty())
        } finally {
            IosLyrics.installNativeCrypto(null)
        }
    }

    private object JvmLyricsCrypto : IosNativeCrypto {
        override fun hmacSha256Base64(value: String, key: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
        }

        override fun currentUnixTimeSeconds(): Long = Instant.now().epochSecond
    }
}
