package app.xan.music.utils.potoken

import android.webkit.CookieManager
import app.xan.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        if (!webViewSupported) {
            Timber.tag(TAG).d("WebView not available")
            return null
        }

        if (!webViewBadImpl) {
            try {
                return withTimeout(15_000L) {
                    getWebClientPoToken(videoId, sessionId, forceRecreate = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Timber.tag(TAG).w(e, "Bundled BotGuard PO Token provider failed")
                if (e is BadWebViewException) webViewBadImpl = true
            }
        }

        // An independent on-device route when the bundled challenge or its API
        // request stops working. No external provider server is needed.
        return try {
            WebPoClientTokenProvider.generate(CipherDeobfuscator.appContext, videoId, sessionId)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            Timber.tag(TAG).w(e, "WebPoClient PO Token provider failed")
            null
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        Timber.tag(TAG).d("Web PO Token requested")

        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired || webPoTokenSessionId != sessionId

                if (shouldRecreate) {
                    Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    webPoTokenSessionId = sessionId

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    // create a new webPoTokenGenerator
                    webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)

                    // The streaming poToken needs to be generated exactly once before generating
                    // any other (player) tokens.
                    webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenSessionId!!)
                    Timber.tag(TAG).d("Streaming poToken generated for sessionId=${webPoTokenSessionId?.take(20)}...")
                }

                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if the app goes in the background and the WebView
                // content is lost
                Timber.tag(TAG).e(throwable, "Failed to obtain poToken, retrying")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        Timber.tag(TAG).d("poToken generated successfully: player=${playerPot.take(20)}..., streaming=${streamingPot.take(20)}...")

        return PoTokenResult(playerPot, streamingPot)
    }
}
