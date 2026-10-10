package app.xan.music.playback

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec

/** Complete network details needed when a resolved media URL is reused. */
internal data class CachedResolvedStream(
    val url: String,
    val expiresAtMillis: Long,
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Null for sources that must be fetched as one whole file, such as TIDAL. */
    val rangeLengthBytes: Long? = null,
    val isWholeFileStream: Boolean = false,
    val contentLengthBytes: Long? = null,
) {
    fun resolve(dataSpec: DataSpec): DataSpec = dataSpec.buildUpon()
        .setUri(url.toUri())
        .setHttpRequestHeaders(mergeStreamRequestHeaders(dataSpec.httpRequestHeaders, requestHeaders))
        .setCustomData(
            if (isWholeFileStream || rangeLengthBytes == null) dataSpec.customData
            else StreamRangePolicy(rangeLengthBytes, contentLengthBytes),
        )
        .build()
}

/** Physical requests are bounded without presenting each chunk as end-of-media. */
internal data class StreamRangePolicy(val chunkLength: Long, val contentLength: Long?)

internal fun streamRequestLength(position: Long, requestedLength: Long, policy: StreamRangePolicy): Long =
    minOf(
        policy.chunkLength,
        requestedLength.takeIf { it >= 0 } ?: Long.MAX_VALUE,
        policy.contentLength?.let { (it - position).coerceAtLeast(0) } ?: Long.MAX_VALUE,
    )

internal fun mergeStreamRequestHeaders(
    original: Map<String, String>,
    resolved: Map<String, String>,
): Map<String, String> = buildMap {
    fun putReplacingCaseInsensitive(name: String, value: String) {
        val existing = keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existing != null) remove(existing)
        put(name, value)
    }

    (original.asSequence() + resolved.asSequence())
        .filterNot { (name, _) -> name.equals("Range", ignoreCase = true) }
        .forEach { (name, value) -> putReplacingCaseInsensitive(name, value) }
}
