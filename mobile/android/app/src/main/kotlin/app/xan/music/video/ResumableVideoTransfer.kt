package app.xan.music.video

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Properties

internal class VideoHttpStatusException(val code: Int) : IOException("Source returned HTTP $code.") {
    val retryable: Boolean get() = code == 408 || code == 425 || code == 429 || code in 500..599
}

/** Download one progressive stream, retaining validated partial bytes across retries. */
internal suspend fun transferVideoStream(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    destination: File,
    onProgress: suspend (downloaded: Long, total: Long) -> Unit,
) {
    val metadataFile = File(destination.parentFile, "${destination.name}.resume")
    val metadata = Properties().apply {
        if (metadataFile.isFile) runCatching { metadataFile.inputStream().use(::load) }
    }
    val validatorHeader = metadata.getProperty("etag")
        ?.takeUnless { it.startsWith("W/") }
        ?.let { "ETag" to it }
        ?: metadata.getProperty("lastModified")?.let { "Last-Modified" to it }
    val validator = validatorHeader?.second
    val canResume = destination.isFile && destination.length() > 0 &&
        metadata.getProperty("url") == url && !validator.isNullOrBlank()
    val offset = if (canResume) destination.length() else 0L
    val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
        if (offset > 0) {
            header("Range", "bytes=$offset-")
            header("If-Range", validator!!)
        }
    }.build()
    client.newCall(request).execute().use { response ->
        if (response.code == 416 && offset > 0) {
            val fullSize = response.header("Content-Range")?.substringAfter("bytes */", "")?.toLongOrNull()
            if (fullSize == offset) {
                onProgress(offset, offset)
                return
            }
            // A stale or oversized partial file cannot be resumed safely.
            destination.delete()
            metadataFile.delete()
            throw IOException("The partial video no longer matches the source; retrying from the start.")
        }
        if (!response.isSuccessful) throw VideoHttpStatusException(response.code)
        val body = response.body
        val append = offset > 0 && response.code == 206
        val range = response.header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it) }
        if (response.code == 206 && (range == null || range.groupValues[1].toLong() != if (append) offset else 0L)) {
            throw IOException("The source returned an unexpected byte range.")
        }
        if (append && validatorHeader != null && response.header(validatorHeader.first) != validatorHeader.second) {
            // A 206 response is only safe to append if it is for the exact same
            // representation as the saved prefix. Discard the prefix so retry
            // starts cleanly instead of producing a corrupted media file.
            destination.delete()
            metadataFile.delete()
            throw IOException("The video changed while downloading; retrying from the start.")
        }
        val start = if (append) offset else 0L
        val responseLength = body.contentLength()
        val total = range?.groupValues?.get(3)?.toLongOrNull()
            ?: responseLength.takeIf { it >= 0 }?.plus(start)
            ?: -1L
        val parentDirectory = destination.parentFile
            ?: throw IOException("Could not determine the temporary download folder.")
        if (total > 0 && parentDirectory.usableSpace < total - start) {
            throw IOException("There is not enough storage to download this video.")
        }
        Properties().apply {
            setProperty("url", url)
            response.header("ETag")?.let { setProperty("etag", it) }
            response.header("Last-Modified")?.let { setProperty("lastModified", it) }
            metadataFile.outputStream().use { store(it, null) }
        }
        var bytes = start
        onProgress(bytes, total)
        body.byteStream().use { input ->
            java.io.FileOutputStream(destination, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    bytes += count
                    onProgress(bytes, total)
                }
                output.fd.sync()
            }
        }
        if (bytes == 0L || (total >= 0 && bytes != total)) {
            throw IOException("The stream ended before all bytes were received.")
        }
        onProgress(bytes, bytes)
    }
}

/** Both sizes must be known before an overall percentage is meaningful. */
internal fun combinedVideoDownloadSize(videoBytes: Long, audioBytes: Long): Long =
    if (videoBytes < 0 || audioBytes < 0) -1L else videoBytes + audioBytes
