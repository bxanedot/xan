package app.xan.music.utils.potoken

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tries YouTube's own WebPoClient in the Android WebView when the bundled
 * BotGuard implementation cannot mint a token. This is an Android adaptation
 * of the WebPoClient approach from coletdjnz/yt-dlp-getpot-wpc (MIT license).
 * The WebPoClient name and location are undocumented and may change.
 */
internal object WebPoClientTokenProvider {
    private const val TIMEOUT_MS = 18_000L
    private const val BRIDGE_NAME = "XanPoBridge"
    private const val PAGE_URL = "https://www.youtube.com/?themeRefresh=1"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun generate(context: Context, videoId: String, sessionId: String): PoTokenResult =
        withContext(Dispatchers.Main) {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(context.applicationContext)
                    var completed = false
                    var scriptStarted = false

                    fun close() {
                        webView.stopLoading()
                        webView.removeJavascriptInterface(BRIDGE_NAME)
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }

                    fun finish(result: Result<PoTokenResult>) {
                        if (completed) return
                        completed = true
                        close()
                        if (continuation.isActive) {
                            result.fold(
                                onSuccess = { continuation.resume(it) },
                                onFailure = { continuation.resumeWithException(it) },
                            )
                        }
                    }

                    val mainHandler = Handler(Looper.getMainLooper())
                    webView.addJavascriptInterface(
                        Bridge(
                            onSuccess = { player, streaming ->
                                mainHandler.post {
                                    if (player.isBlank() || streaming.isBlank() ||
                                        player == "undefined" || streaming == "undefined" ||
                                        player == "null" || streaming == "null") {
                                        finish(Result.failure(PoTokenException("WebPoClient returned an empty token")))
                                    } else {
                                        finish(Result.success(PoTokenResult(player, streaming)))
                                    }
                                }
                            },
                            onFailure = { reason ->
                                mainHandler.post {
                                    finish(Result.failure(PoTokenException("WebPoClient: $reason")))
                                }
                            },
                        ),
                        BRIDGE_NAME,
                    )

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = DESKTOP_USER_AGENT
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (completed || scriptStarted) return
                            if (Uri.parse(url).host != "www.youtube.com") {
                                finish(Result.failure(PoTokenException("YouTube WebPoClient page redirected")))
                                return
                            }
                            scriptStarted = true
                            view.evaluateJavascript(mintScript(videoId, sessionId), null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                finish(Result.failure(PoTokenException("YouTube WebPoClient page failed to load")))
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        mainHandler.post {
                            if (!completed) {
                                completed = true
                                close()
                            }
                        }
                    }
                    webView.loadUrl(PAGE_URL)
                }
            }
        }

    private fun mintScript(videoId: String, sessionId: String): String {
        val quotedVideoId = JSONObject.quote(videoId)
        val quotedSessionId = JSONObject.quote(sessionId)
        return """
            (async () => {
                const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
                try {
                    let factory;
                    for (let attempt = 0; attempt < 9; attempt++) {
                        factory = window.top['havuokmhhs-0']?.bevasrs?.wpc;
                        if (typeof factory === 'function') break;
                        await wait(700);
                    }
                    if (typeof factory !== 'function') throw Error('WebPoClient is unavailable');
                    const client = await factory();
                    const mint = async binding => {
                        for (let attempt = 0; attempt < 8; attempt++) {
                            try {
                                return await client.mws({c: binding, mc: false, me: false});
                            } catch (error) {
                                if (!String(error).includes('SDF:notready')) throw error;
                                await wait(500);
                            }
                        }
                        throw Error('WebPoClient did not become ready');
                    };
                    const streaming = await mint($quotedSessionId);
                    const player = await mint($quotedVideoId);
                    $BRIDGE_NAME.success(String(player), String(streaming));
                } catch (error) {
                    $BRIDGE_NAME.failure(String(error));
                }
            })();
        """.trimIndent()
    }

    private class Bridge(
        private val onSuccess: (String, String) -> Unit,
        private val onFailure: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun success(player: String, streaming: String) = onSuccess(player, streaming)

        @JavascriptInterface
        fun failure(reason: String) = onFailure(reason)
    }
}
