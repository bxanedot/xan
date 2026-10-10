package app.xan.music.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data class representing decoded PCM audio and its format.
 */
data class DecodedAudio(
    val data: ByteArray,
    val channelCount: Int,
    val sampleRate: Int,
    val pcmEncoding: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedAudio
        return data.contentEquals(other.data) &&
            channelCount == other.channelCount &&
            sampleRate == other.sampleRate &&
            pcmEncoding == other.pcmEncoding
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + channelCount
        result = 31 * result + sampleRate
        result = 31 * result + pcmEncoding
        return result
    }
}

/**
 * Small PCM16 resampler used by Shazam recognition.
 *
 * Keeping this path independent from Media3's AudioProcessor interface avoids
 * API changes between Media3 releases and also makes the mono microphone path
 * deterministic on devices that ship newer Android audio components.
 */
object AudioResampler {

    suspend fun resample(
        decodedAudio: DecodedAudio,
        outputSampleRate: Int,
    ): Result<DecodedAudio> = withContext(Dispatchers.Default) {
        runCatching {
            require(decodedAudio.sampleRate > 0) { "Input sample rate must be positive" }
            require(outputSampleRate > 0) { "Output sample rate must be positive" }
            require(decodedAudio.channelCount > 0) { "Input channel count must be positive" }
            require(decodedAudio.pcmEncoding == 2) { "Only PCM16 audio is supported" }
            require(decodedAudio.data.size % 2 == 0) { "PCM16 data must contain complete samples" }

            val inputSampleCount = decodedAudio.data.size / 2
            require(inputSampleCount > 0) { "Audio data is empty" }
            val inputFrames = inputSampleCount / decodedAudio.channelCount
            require(inputFrames > 0) { "Audio data does not contain a complete frame" }

            if (decodedAudio.sampleRate == outputSampleRate && decodedAudio.channelCount == 1) {
                return@runCatching decodedAudio
            }

            val input = ShortArray(inputSampleCount)
            ByteBuffer.wrap(decodedAudio.data)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(input)

            val outputFrames = (inputFrames.toLong() * outputSampleRate / decodedAudio.sampleRate)
                .toInt()
                .coerceAtLeast(1)
            val output = ByteBuffer.allocate(outputFrames * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (frame in 0 until outputFrames) {
                ensureActive()
                val sourcePosition = if (outputFrames == 1) {
                    0.0
                } else {
                    frame.toDouble() * (inputFrames - 1).toDouble() / (outputFrames - 1).toDouble()
                }
                val sourceFrame = sourcePosition.toInt().coerceIn(0, inputFrames - 1)
                val nextFrame = (sourceFrame + 1).coerceAtMost(inputFrames - 1)
                val fraction = sourcePosition - sourceFrame

                var mixed = 0.0
                for (channel in 0 until decodedAudio.channelCount) {
                    val first = input[sourceFrame * decodedAudio.channelCount + channel].toDouble()
                    val second = input[nextFrame * decodedAudio.channelCount + channel].toDouble()
                    mixed += first + (second - first) * fraction
                }
                val mono = (mixed / decodedAudio.channelCount)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(mono.toShort())
            }

            DecodedAudio(
                data = output.array(),
                channelCount = 1,
                sampleRate = outputSampleRate,
                pcmEncoding = decodedAudio.pcmEncoding,
            )
        }
    }
}
