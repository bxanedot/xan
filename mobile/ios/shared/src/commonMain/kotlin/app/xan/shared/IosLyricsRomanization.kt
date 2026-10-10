package app.xan.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface IosLyricsRomanizer {
    fun romanizeLyrics(text: String): String?
}

internal data class IosLyricsRomanizationSettings(
    val enabled: Boolean = true,
    val useAsMain: Boolean = false,
)

internal object IosLyricsRomanization {
    private var romanizer: IosLyricsRomanizer? = null
    private val mutableState = MutableStateFlow(
        IosLyricsRomanizationSettings(
            enabled = readIosPreference("lyrics.romanization.enabled")?.toBooleanStrictOrNull() ?: true,
            useAsMain = readIosPreference("lyrics.romanization.useAsMain")?.toBooleanStrictOrNull() ?: false,
        ),
    )
    val state = mutableState.asStateFlow()

    fun install(value: IosLyricsRomanizer?) {
        romanizer = value
    }

    fun setEnabled(value: Boolean) {
        writeIosPreference("lyrics.romanization.enabled", value.toString())
        mutableState.value = mutableState.value.copy(enabled = value)
    }

    fun setUseAsMain(value: Boolean) {
        writeIosPreference("lyrics.romanization.useAsMain", value.toString())
        mutableState.value = mutableState.value.copy(useAsMain = value)
    }

    fun romanize(text: String): String? {
        if (!mutableState.value.enabled || text.isBlank()) return null
        val romanized = runCatching { romanizer?.romanizeLyrics(text.take(2_000)) }.getOrNull()
        return romanized?.takeIf { it.isNotBlank() && it != text }
    }
}
