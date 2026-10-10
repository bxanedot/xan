package app.xan.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.util.date.GMTDate

internal const val IOS_YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
internal const val IOS_YOUTUBE_MUSIC_CLIENT_VERSION = "1.20260213.01.00"

internal fun normalizeIosDataSyncId(value: String?): String? = value
    ?.substringBefore("||")
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it != "null" }

internal fun iosActiveDataSyncId(): String? {
    return if (iosLoginForBrowseEnabled()) normalizeIosDataSyncId(readIosPreference("account.dataSyncId")) else null
}

internal fun iosLoginForBrowseEnabled(): Boolean =
    readIosPreference("useLoginForBrowse")?.toBooleanStrictOrNull() ?: true

internal fun iosYouTubeContext(
    clientName: String = "WEB_REMIX",
    clientVersion: String = IOS_YOUTUBE_MUSIC_CLIENT_VERSION,
    iosDevice: Boolean = false,
    dataSyncId: String? = iosActiveDataSyncId(),
): JsonObject = buildJsonObject {
    put("client", buildJsonObject {
        put("clientName", clientName)
        put("clientVersion", clientVersion)
        put("hl", "en")
        put("gl", "US")
        if (iosDevice) {
            put("deviceMake", "Apple")
            put("deviceModel", "iPhone")
            put("osName", "iPhone")
            put("osVersion", "18.2.22C152")
        }
    })
    normalizeIosDataSyncId(dataSyncId)?.let { id ->
        put("user", buildJsonObject { put("onBehalfOfUser", id) })
    }
}

/** YouTube's web account endpoints require a time-bound SAPISIDHASH alongside the cookie. */
internal fun iosSapisidAuthorization(
    cookie: String?,
    origin: String,
    epochSeconds: Long = GMTDate().timestamp / 1_000L,
): String? {
    if (epochSeconds < 0 || origin.isBlank()) return null
    val sapisid = cookie.orEmpty().split(';').firstNotNullOfOrNull { part ->
        val pair = part.trim().split('=', limit = 2)
        pair.getOrNull(1)?.trim()?.takeIf { pair.firstOrNull() == "SAPISID" && it.isNotEmpty() }
    } ?: return null
    val digest = iosSha1Hex("$epochSeconds $sapisid $origin")
    return "SAPISIDHASH ${epochSeconds}_$digest"
}

/** Small common SHA-1 implementation for the protocol-mandated YouTube auth digest. */
internal fun iosSha1Hex(value: String): String {
    val input = value.encodeToByteArray()
    val paddedLength = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedLength)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8L
    for (index in 0 until 8) {
        padded[paddedLength - 1 - index] = (bitLength ushr (index * 8)).toByte()
    }

    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val words = IntArray(80)
    var offset = 0
    while (offset < padded.size) {
        for (index in 0 until 16) {
            val start = offset + index * 4
            words[index] =
                ((padded[start].toInt() and 0xff) shl 24) or
                    ((padded[start + 1].toInt() and 0xff) shl 16) or
                    ((padded[start + 2].toInt() and 0xff) shl 8) or
                    (padded[start + 3].toInt() and 0xff)
        }
        for (index in 16 until 80) {
            words[index] = rotateLeft(words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16], 1)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (index in 0 until 80) {
            val (function, constant) = when (index) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }
            val temp = rotateLeft(a, 5) + function + e + constant + words[index]
            e = d
            d = c
            c = rotateLeft(b, 30)
            b = a
            a = temp
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        offset += 64
    }
    return listOf(h0, h1, h2, h3, h4).joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
