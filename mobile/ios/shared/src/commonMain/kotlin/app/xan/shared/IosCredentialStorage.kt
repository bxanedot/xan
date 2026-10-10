package app.xan.shared

interface YouTubeCredentialStore {
    fun loadCookie(): String?
    fun saveCookie(cookie: String?): Boolean
}

interface IosNativeCrypto {
    fun hmacSha256Base64(value: String, key: String): String
    fun currentUnixTimeSeconds(): Long
}

interface IosLastFmSecretStore : IosNativeCrypto {
    fun loadLastFmSecret(name: String): String?
    fun saveLastFmSecret(name: String, value: String?): Boolean
    fun md5Hex(value: String): String
    fun openLastFmAuthorizationUrl(url: String): Boolean
}

interface IosListenBrainzTokenStore {
    fun loadListenBrainzToken(): String?
    fun saveListenBrainzToken(token: String?): Boolean
}

/** Keeps the provider credential behind a native store supplied by the app host. */
object IosCredentialStorage {
    private var store: YouTubeCredentialStore? = null

    fun install(store: YouTubeCredentialStore?) {
        this.store = store
    }

    fun youtubeCookie(): String? = runCatching { store?.loadCookie()?.takeIf(String::isNotBlank) }.getOrNull()

    fun hasYouTubeCookie(): Boolean = youtubeCookie() != null

    fun isYouTubeCookieValid(rawCookie: String): Boolean {
        val cookie = rawCookie.trim()
        if (cookie.length !in 32..16_384) return false
        if (cookie.any { it !in '\u0020'..'\u007E' }) return false
        return cookie.split(';').any { part ->
            part.trim().substringBefore('=') == "SAPISID" && part.substringAfter('=', "").isNotBlank()
        }
    }

    fun saveYouTubeCookie(rawCookie: String): Boolean {
        val cookie = rawCookie.trim()
        if (!isYouTubeCookieValid(cookie)) return false
        return runCatching { store?.saveCookie(cookie) == true }.getOrDefault(false)
    }

    fun clearYouTubeCookie(): Boolean = runCatching { store?.saveCookie(null) == true }.getOrDefault(false)
}
