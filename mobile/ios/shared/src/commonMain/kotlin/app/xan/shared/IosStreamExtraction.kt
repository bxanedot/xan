package app.xan.shared

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface IosStreamExtractionCallback {
    fun onResolved(url: String)
    fun onFailure(message: String)
}

/** Implemented by the native Swift extractor; URLs are consumed only by XAN's player. */
interface IosNativeStreamExtractor {
    fun resolveAudio(videoId: String, callback: IosStreamExtractionCallback)
    fun cancel()
}

internal fun safeIosAudioStreamUrl(raw: String): String? {
    val candidate = raw.trim().takeIf { it.length in 1..16_384 } ?: return null
    val parsed = runCatching { Url(candidate) }.getOrNull() ?: return null
    val host = parsed.host.lowercase()
    if (parsed.protocol.name != "https" || parsed.port != 443 || parsed.fragment.isNotEmpty()) return null
    if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) return null
    return candidate.takeIf { host == "googlevideo.com" || host.endsWith(".googlevideo.com") }
}

internal suspend fun resolveIosAudioWithFallback(
    videoId: String,
    extractor: IosNativeStreamExtractor?,
    direct: suspend () -> IosResolvedAudio,
): IosResolvedAudio {
    try {
        return withTimeoutOrNull(30_000) { direct() } ?: error("YouTube Music's stream request timed out.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (extractor == null) throw failure
    }
    return withTimeoutOrNull(45_000) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { extractor.cancel() }
            extractor.resolveAudio(videoId, object : IosStreamExtractionCallback {
                override fun onResolved(url: String) {
                    if (!continuation.isActive) return
                    val safe = safeIosAudioStreamUrl(url)
                    if (safe == null) continuation.resumeWithException(IllegalStateException("The native extractor returned an unsafe audio URL."))
                    else continuation.resume(IosResolvedAudio(safe, "audio/mp4", 0))
                }

                override fun onFailure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message.ifBlank { "Native YouTube audio extraction failed." }))
                }
            })
        }
    } ?: error("YouTube audio extraction timed out. Try this track again.")
}
