package app.xan.desktop

import com.atilika.kuromoji.ipadic.Tokenizer
import java.util.Locale

/** Offline Japanese lyric romanization, ported to desktop with Android's Kuromoji dictionary. */
internal object DesktopJapaneseRomanizer {
    private val tokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Tokenizer() }

    private val kanaToRomaji = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "o", "ン" to "n",
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        "ヴ" to "vu", "ー" to "",
    )

    fun containsJapanese(text: String): Boolean {
        val kanaCount = text.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val hanCount = text.count { it in '\u4E00'..'\u9FFF' }
        val hasJapaneseScript = kanaCount > 0 || hanCount > 0
        val looksChinese = hanCount > 0 && kanaCount.toDouble() / text.length.coerceAtLeast(1) < 0.1
        return hasJapaneseScript && !looksChinese
    }

    fun romanize(text: String): String {
        val tokens = tokenizer.tokenize(text)
        return tokens.mapIndexed { index, token ->
            val reading = token.reading?.takeIf { it.isNotBlank() && it != "*" } ?: token.surface
            val nextReading = tokens.getOrNull(index + 1)?.let { next ->
                next.reading?.takeIf { it.isNotBlank() && it != "*" } ?: next.surface
            }
            katakanaToRomaji(reading, nextReading)
        }.joinToString(" ")
    }

    private fun katakanaToRomaji(katakana: String, nextKatakana: String?): String {
        val output = StringBuilder(katakana.length)
        var index = 0
        while (index < katakana.length) {
            val pair = katakana.substring(index, (index + 2).coerceAtMost(katakana.length))
            val pairMapping = if (pair.length == 2) kanaToRomaji[pair] else null
            if (pairMapping != null) {
                output.append(pairMapping)
                index += 2
                continue
            }

            val current = katakana[index]
            when {
                current == 'ッ' -> {
                    val following = if (index + 1 < katakana.length) {
                        katakana.substring(index + 1, (index + 3).coerceAtMost(katakana.length))
                    } else {
                        nextKatakana?.take(2).orEmpty()
                    }
                    val followingRomaji = kanaToRomaji[following]
                        ?: following.firstOrNull()?.toString()?.let(kanaToRomaji::get)
                    followingRomaji?.firstOrNull()?.let(output::append)
                    index += 1
                }
                current == 'ー' -> {
                    output.lastOrNull()?.takeIf { it in "aeiou" }?.let(output::append)
                    index += 1
                }
                else -> {
                    val character = current.toString()
                    output.append(kanaToRomaji[character] ?: character)
                    index += 1
                }
            }
        }
        return output.toString().lowercase(Locale.ROOT)
    }
}
