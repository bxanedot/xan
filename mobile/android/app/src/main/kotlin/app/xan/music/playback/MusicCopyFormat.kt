package app.xan.music.playback

import java.security.MessageDigest
import java.io.InputStream

enum class MusicCopyFormat(val extension: String, val mimeType: String, val encoderArguments: List<String>) {
    MP3("mp3", "audio/mpeg", listOf("-c:a", "libmp3lame", "-b:a", "320k")),
    AAC("aac", "audio/aac", listOf("-c:a", "aac", "-b:a", "256k", "-f", "adts")),
    M4A("m4a", "audio/mp4", listOf("-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart")),
    FLAC("flac", "audio/flac", listOf("-c:a", "flac")),
    WAV("wav", "audio/wav", listOf("-c:a", "pcm_s16le")),
    OPUS("opus", "audio/ogg", listOf("-c:a", "libopus", "-b:a", "160k")),
    OGG("ogg", "audio/ogg", listOf("-c:a", "libvorbis", "-q:a", "6"));

    fun conversionArguments(input: String, output: String, title: String, artist: String): List<String> =
        listOf("-nostdin", "-y", "-i", input, "-map", "0:a:0", "-vn", "-metadata", "title=$title",
            "-metadata", "artist=$artist") + encoderArguments + output

    companion object {
        fun selected(names: Set<String>): List<MusicCopyFormat> = entries.filter { it.name in names }
    }
}

/** Stable song identity prevents equally named tracks from overwriting one another. */
fun musicCopyIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun musicCopyChecksum(input: InputStream, checkCancelled: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun musicCopyFileName(title: String, id: String, format: MusicCopyFormat): String {
    val safeTitle = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
        .trim().trim('.').take(64).ifBlank { "Track" }
    return "$safeTitle-${musicCopyIdentity(id).take(12)}.${format.extension}"
}
