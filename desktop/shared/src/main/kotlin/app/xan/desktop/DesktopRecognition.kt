package app.xan.desktop

import com.music.shazamkit.Shazam
import com.music.shazamkit.models.RecognitionResult
import com.music.shazamkit.models.RecognitionStatus
import app.xan.music.recognition.AudioResampler
import app.xan.music.recognition.DecodedAudio
import app.xan.music.recognition.ShazamSignatureGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

internal data class RecognitionHistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val shazamUrl: String?,
    val recognizedAtEpochMillis: Long,
)

internal class RecognitionHistoryStore(
    private val readValue: () -> String?,
    private val writeValue: (String) -> Unit,
) {
    fun entries(): List<RecognitionHistoryEntry> = readValue().orEmpty()
        .lineSequence().filter(String::isNotBlank).mapNotNull { row ->
            runCatching {
                val values = row.split('|').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                require(values.size == 7)
                RecognitionHistoryEntry(
                    trackId = values[0], title = values[1], artist = values[2],
                    album = values[3].ifBlank { null }, artworkUrl = values[4].ifBlank { null },
                    shazamUrl = values[5].ifBlank { null }, recognizedAtEpochMillis = values[6].toLong(),
                )
            }.getOrNull()
        }.toList()

    @Synchronized
    fun record(entry: RecognitionHistoryEntry) {
        val current = entries().filterNot { it.trackId == entry.trackId && it.recognizedAtEpochMillis == entry.recognizedAtEpochMillis }
        writeValue(encode(listOf(entry) + current.take(99)))
    }

    fun clear() = writeValue("")

    private fun encode(entries: List<RecognitionHistoryEntry>): String {
        val encode = { value: String -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }
        return entries.joinToString("\n") { entry ->
            listOf(entry.trackId, entry.title, entry.artist, entry.album.orEmpty(), entry.artworkUrl.orEmpty(), entry.shazamUrl.orEmpty(), entry.recognizedAtEpochMillis.toString())
                .joinToString("|") { encode(it) }
        }
    }
}

internal class MusicRecognitionFlow(
    private val recordPcm: suspend () -> ByteArray,
    private val lookup: suspend (String, Long) -> Result<RecognitionResult>,
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val status: StateFlow<RecognitionStatus> = mutableStatus.asStateFlow()

    suspend fun recognize(): RecognitionStatus = mutex.withLock {
        mutableStatus.value = RecognitionStatus.Listening
        try {
            val pcm = recordPcm()
            mutableStatus.value = RecognitionStatus.Processing
            val resampled = AudioResampler.resample(
                DecodedAudio(data = pcm, channelCount = 1, sampleRate = INPUT_RATE, pcmEncoding = PCM16),
                OUTPUT_RATE,
            ).getOrThrow()
            val signature = ShazamSignatureGenerator.fromI16(resampled.data)
            val sampleDurationMillis = resampled.data.size / 2L * 1000L / OUTPUT_RATE
            lookup(signature, sampleDurationMillis).fold(
                onSuccess = { result -> RecognitionStatus.Success(result) },
                onFailure = { failure ->
                    val reason = failure.message ?: "Music recognition failed. Try again."
                    if (reason.contains("no match", ignoreCase = true) || reason.contains("not found", ignoreCase = true)) {
                        RecognitionStatus.NoMatch("No match found. Try again with clearer audio.")
                    } else RecognitionStatus.Error(reason)
                },
            ).also { mutableStatus.value = it }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val error = RecognitionStatus.Error(
                failure.message?.takeIf(String::isNotBlank)
                    ?: "Could not recognize music. Check microphone access and try again.",
            )
            mutableStatus.value = error
            error
        }
    }

    fun reset() { mutableStatus.value = RecognitionStatus.Ready }

    private companion object {
        const val INPUT_RATE = 44_100
        const val OUTPUT_RATE = 16_000
        const val PCM16 = 2
    }
}

internal object DesktopRecognitionService {
    private val flow = MusicRecognitionFlow(::recordTenSeconds, Shazam::recognize)
    val status: StateFlow<RecognitionStatus> = flow.status

    suspend fun recognize(): RecognitionStatus = flow.recognize()
    fun reset() = flow.reset()

    private suspend fun recordTenSeconds(): ByteArray = withContext(Dispatchers.IO) {
        val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, 16, 1, 2, 44_100f, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        check(AudioSystem.isLineSupported(info)) {
            "This device does not expose a compatible microphone input. Check your system audio settings."
        }
        val line = AudioSystem.getLine(info) as TargetDataLine
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        try {
            line.open(format, buffer.size * 2)
            line.start()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = line.read(buffer, 0, buffer.size)
                check(count > 0) { "Microphone capture stopped before enough audio was recorded." }
                output.write(buffer, 0, count)
            }
        } finally {
            runCatching { line.stop() }
            line.close()
        }
        val pcm = output.toByteArray()
        check(pcm.size >= 44_100 * 2) { "Microphone captured too little audio. Check microphone access and try again." }
        pcm
    }
}

internal fun RecognitionResult.toHistoryEntry(now: Instant = Instant.now()) = RecognitionHistoryEntry(
    trackId = trackId,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = coverArtHqUrl ?: coverArtUrl,
    shazamUrl = shazamUrl,
    recognizedAtEpochMillis = now.toEpochMilli(),
)
