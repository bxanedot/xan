package app.xan.music.listentogether

import java.util.Locale

/** Accept pasted/lowercase room codes without sending whitespace or punctuation in the URL. */
fun normalizeRoomCode(value: String): String = value
    .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    .uppercase(Locale.ROOT)

fun isValidRoomCode(value: String): Boolean =
    value.length in MIN_ROOM_CODE_LENGTH..MAX_ROOM_CODE_LENGTH &&
        value.all { it in 'A'..'Z' || it in '0'..'9' }
