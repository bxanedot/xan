package app.xan.desktop

/** Offline romanization for the non-Latin scripts enabled by Android's lyrics panel. */
internal object DesktopLyricsRomanizer {
    private val devanagari = mapOf(
        "अ" to "a", "आ" to "aa", "इ" to "i", "ई" to "ee", "उ" to "u", "ऊ" to "oo", "ऋ" to "ri", "ए" to "e", "ऐ" to "ai", "ओ" to "o", "औ" to "au",
        "क" to "k", "ख" to "kh", "ग" to "g", "घ" to "gh", "ङ" to "ng", "च" to "ch", "छ" to "chh", "ज" to "j", "झ" to "jh", "ञ" to "ny",
        "ट" to "t", "ठ" to "th", "ड" to "d", "ढ" to "dh", "ण" to "n", "त" to "t", "थ" to "th", "द" to "d", "ध" to "dh", "न" to "n",
        "प" to "p", "फ" to "ph", "ब" to "b", "भ" to "bh", "म" to "m", "य" to "y", "र" to "r", "ल" to "l", "व" to "v", "श" to "sh", "ष" to "sh", "स" to "s", "ह" to "h",
        "क्ष" to "ksh", "त्र" to "tr", "ज्ञ" to "gy", "श्र" to "shr", "ा" to "aa", "ि" to "i", "ी" to "ee", "ु" to "u", "ू" to "oo", "ृ" to "ri", "े" to "e", "ै" to "ai", "ो" to "o", "ौ" to "au",
        "ं" to "n", "ः" to "h", "ँ" to "n", "़" to "", "्" to "", "०" to "0", "१" to "1", "२" to "2", "३" to "3", "४" to "4", "५" to "5", "६" to "6", "७" to "7", "८" to "8", "९" to "9", "ॐ" to "Om", "ऽ" to "",
        "क़" to "q", "ख़" to "kh", "ग़" to "g", "ज़" to "z", "ड़" to "r", "ढ़" to "rh", "फ़" to "f", "य़" to "y",
    )
    private val gurmukhi = mapOf(
        "ੳ" to "o", "ਅ" to "a", "ੲ" to "e", "ਸ" to "s", "ਹ" to "h", "ਕ" to "k", "ਖ" to "kh", "ਗ" to "g", "ਘ" to "gh", "ਙ" to "ng",
        "ਚ" to "ch", "ਛ" to "chh", "ਜ" to "j", "ਝ" to "jh", "ਞ" to "ny", "ਟ" to "t", "ਠ" to "th", "ਡ" to "d", "ਢ" to "dh", "ਣ" to "n",
        "ਤ" to "t", "ਥ" to "th", "ਦ" to "d", "ਧ" to "dh", "ਨ" to "n", "ਪ" to "p", "ਫ" to "ph", "ਬ" to "b", "ਭ" to "bh", "ਮ" to "m",
        "ਯ" to "y", "ਰ" to "r", "ਲ" to "l", "ਵ" to "v", "ੜ" to "r", "ਸ਼" to "sh", "ਖ਼" to "kh", "ਗ਼" to "g", "ਜ਼" to "z", "ਫ਼" to "f", "ਲ਼" to "l",
        "ਾ" to "aa", "ਿ" to "i", "ੀ" to "ee", "ੁ" to "u", "ੂ" to "oo", "ੇ" to "e", "ੈ" to "ai", "ੋ" to "o", "ੌ" to "au", "ੰ" to "n", "ਂ" to "n", "ੱ" to "", "੍" to "", "਼" to "", "ੴ" to "Ek Onkar",
        "੦" to "0", "੧" to "1", "੨" to "2", "੩" to "3", "੪" to "4", "੫" to "5", "੬" to "6", "੭" to "7", "੮" to "8", "੯" to "9",
    )
    private val cyrillic = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Ґ' to "G", 'Д' to "D", 'Е' to "E", 'Ё' to "Yo", 'Є' to "Ye", 'Ж' to "Zh", 'З' to "Z", 'Ѕ' to "Dz", 'И' to "I", 'І' to "I", 'Ї' to "Yi", 'Й' to "Y", 'Ј' to "Y", 'К' to "K", 'Л' to "L", 'Љ' to "Lj", 'М' to "M", 'Н' to "N", 'Њ' to "Nj", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'Ћ' to "Ć", 'У' to "U", 'Ў' to "Ŭ", 'Ф' to "F", 'Х' to "Kh", 'Ц' to "Ts", 'Ч' to "Ch", 'Џ' to "Dž", 'Ш' to "Sh", 'Щ' to "Shch", 'Ъ' to "ʺ", 'Ы' to "Y", 'Ь' to "ʹ", 'Э' to "E", 'Ю' to "Yu", 'Я' to "Ya", 'Ѓ' to "Ǵ", 'Ќ' to "Ḱ", 'Ө' to "Ö", 'Ң' to "Ng", 'Қ' to "Q", 'Һ' to "H",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ґ' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'є' to "ye", 'ж' to "zh", 'з' to "z", 'ѕ' to "dz", 'и' to "i", 'і' to "i", 'ї' to "yi", 'й' to "y", 'ј' to "y", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u", 'ў' to "ŭ", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'џ' to "dž", 'ш' to "sh", 'щ' to "shch", 'ъ' to "ʺ", 'ы' to "y", 'ь' to "ʹ", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ѓ' to "ǵ", 'ќ' to "ḱ", 'ө' to "ö", 'ң' to "ng", 'қ' to "q", 'һ' to "h",
    )
    private val koreanOnsets = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val koreanVowels = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val koreanFinals = arrayOf("", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lp", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t")

    fun romanize(text: String, japaneseContext: Boolean = DesktopOptionalFeaturePacks.containsJapanese(text)): String? = when {
        japaneseContext -> DesktopOptionalFeaturePacks.romanizeJapanese(text)
        text.any { it in '\uAC00'..'\uD7A3' } -> romanizeKorean(text)
        text.any { it in '\u0900'..'\u097F' } -> replaceLongest(text, devanagari)
        text.any { it in '\u0A00'..'\u0A7F' } -> replaceLongest(text, gurmukhi)
        text.any { it in '\u0400'..'\u04FF' } -> text.map { cyrillic[it] ?: it.toString() }.joinToString("")
        else -> null
    }?.takeIf { it.isNotBlank() && it != text }

    private fun romanizeKorean(text: String): String = buildString {
        text.forEach { char ->
            if (char !in '\uAC00'..'\uD7A3') {
                append(char)
            } else {
                val offset = char.code - 0xAC00
                val onset = offset / (21 * 28)
                val vowel = offset % (21 * 28) / 28
                val final = offset % 28
                append(koreanOnsets[onset]).append(koreanVowels[vowel]).append(koreanFinals[final])
            }
        }
    }

    private fun replaceLongest(text: String, replacements: Map<String, String>): String {
        val keys = replacements.keys.sortedByDescending { it.length }
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val key = keys.firstOrNull { text.startsWith(it, index) }
            if (key == null) output.append(text[index++]) else {
                output.append(replacements.getValue(key))
                index += key.length
            }
        }
        return output.toString()
    }
}
