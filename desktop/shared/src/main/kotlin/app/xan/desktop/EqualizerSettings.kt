package app.xan.desktop

import javafx.scene.media.EqualizerBand
import javafx.scene.media.MediaPlayer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

internal data class EqualizerSettings(
    val enabled: Boolean = false,
    val advanced: Boolean = false,
    val gainsDb: List<Double> = List(5) { 0.0 },
) {
    init {
        require(gainsDb.size == XanEqualizer.bandDefinitions.size) { "XAN Equalizer requires five bands." }
        require(gainsDb.all { it.isFinite() && it in XanEqualizer.MIN_GAIN_DB..XanEqualizer.MAX_GAIN_DB }) {
            "Equalizer gains must be between ${XanEqualizer.MIN_GAIN_DB} dB and ${XanEqualizer.MAX_GAIN_DB} dB."
        }
    }
}

internal data class EqualizerRuntimeSnapshot(
    val enabled: Boolean,
    val gainsDb: List<Double>,
    val effectivePlayerVolume: Double,
)

internal object XanEqualizer {
    const val MIN_GAIN_DB = -12.0
    const val MAX_GAIN_DB = 12.0
    val bandDefinitions = listOf(
        EqBandDefinition("60 Hz", 60.0, 45.0),
        EqBandDefinition("250 Hz", 250.0, 180.0),
        EqBandDefinition("1 kHz", 1_000.0, 700.0),
        EqBandDefinition("4 kHz", 4_000.0, 2_800.0),
        EqBandDefinition("12 kHz", 12_000.0, 6_000.0),
    )

    fun simpleGainsDb(bassDb: Double, midDb: Double, trebleDb: Double): List<Double> {
        val bass = bassDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val mid = midDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val treble = trebleDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        return listOf(
            bass,
            bass * .5 + mid * .5,
            mid,
            mid * .5 + treble * .5,
            treble,
        )
    }

    /** Estimates the peak gain of the complete peaking-filter cascade, then adds 1 dB safety headroom. */
    fun headroomCompensationDb(gainsDb: List<Double>): Double {
        require(gainsDb.size == bandDefinitions.size)
        if (gainsDb.none { it > 0.0 }) return 0.0
        val sampleRate = 48_000.0
        val q = 1.41
        var peakDb = Double.NEGATIVE_INFINITY
        val low = ln(20.0)
        val high = ln(sampleRate / 2.0)
        repeat(2048) { index ->
            val frequency = kotlin.math.exp(low + (high - low) * index / 2047.0)
            val totalDb = bandDefinitions.indices.sumOf { bandIndex ->
                peakingFilterGainDb(frequency, bandDefinitions[bandIndex].centerHz, gainsDb[bandIndex], q, sampleRate)
            }
            peakDb = maxOf(peakDb, totalDb)
        }
        return if (peakDb > 0.0) -ceil(peakDb + 1.0) else 0.0
    }

    fun effectiveVolume(userVolume: Double, settings: EqualizerSettings): Double {
        if (!settings.enabled) return userVolume.coerceIn(0.0, 1.0)
        val compensation = 10.0.pow(headroomCompensationDb(settings.gainsDb) / 20.0)
        return (userVolume * compensation).coerceIn(0.0, 1.0)
    }

    fun apply(player: MediaPlayer, settings: EqualizerSettings, userVolume: Double) {
        val equalizer = player.audioEqualizer
        val bands = bandDefinitions.mapIndexed { index, definition ->
            EqualizerBand(definition.centerHz, definition.bandwidthHz, settings.gainsDb[index])
        }
        equalizer.bands.setAll(bands)
        equalizer.isEnabled = settings.enabled
        player.volume = effectiveVolume(userVolume, settings)
    }

    fun runtimeSnapshot(player: MediaPlayer): EqualizerRuntimeSnapshot = EqualizerRuntimeSnapshot(
        enabled = player.audioEqualizer.isEnabled,
        gainsDb = player.audioEqualizer.bands.map { it.gain },
        effectivePlayerVolume = player.volume,
    )

    private fun peakingFilterGainDb(frequency: Double, centerHz: Double, gainDb: Double, q: Double, sampleRate: Double): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cos(w0)
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cos(w0)
        val a2 = 1.0 - alpha / a
        val w = 2.0 * PI * frequency / sampleRate
        fun magnitude(real: Double, imaginary: Double) = kotlin.math.hypot(real, imaginary)
        val numerator = magnitude(b0 + b1 * cos(w) + b2 * cos(2.0 * w), -b1 * sin(w) - b2 * sin(2.0 * w))
        val denominator = magnitude(a0 + a1 * cos(w) + a2 * cos(2.0 * w), -a1 * sin(w) - a2 * sin(2.0 * w))
        return 20.0 * kotlin.math.log10(numerator / denominator)
    }
}

internal data class EqBandDefinition(val label: String, val centerHz: Double, val bandwidthHz: Double)
