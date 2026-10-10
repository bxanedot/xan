package app.xan.music.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Canonical project links.
 *
 * Discord is intentionally sourced from README.md at runtime so the invite can
 * be changed on GitHub without shipping a new APK. DISCORD_FALLBACK is used
 * when the README cannot be reached (for example while the repository is private).
 */
object ProjectLinks {
    const val REPOSITORY = "https://github.com/bxanedot/xan"
    const val STAR_REPOSITORY = REPOSITORY
    const val RELEASES = "$REPOSITORY/releases"
    const val WEBSITE = "https://xan.lat"
    const val TELEGRAM = "https://t.me/+T9avk7iJ7_YyN2Rk"
    const val DISCORD_FALLBACK = "https://discord.gg/h4Wsu824Uf"
    const val LEAD_DEVELOPER = "https://github.com/bxanedot"
    const val GUNS_PROFILE = "https://guns.lol/bxane"
    const val PATREON = "https://www.patreon.com/bxane"
    const val KO_FI = "https://ko-fi.com/bxane"

    private const val README_RAW =
        "https://raw.githubusercontent.com/bxanedot/xan/main/README.md"
    private const val GITHUB_API = "https://api.github.com/repos/bxanedot/xan"

    suspend fun githubStars(): Int? = withContext(Dispatchers.IO) {
        val connection = runCatching {
            (URL(GITHUB_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "xan-android")
            }
        }.getOrNull() ?: return@withContext null

        try {
            if (connection.responseCode !in 200..299) return@withContext null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optInt("stargazers_count", -1)
                .takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun discordUrl(): String = withContext(Dispatchers.IO) {
        val connection = runCatching {
            (URL(README_RAW).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("User-Agent", "xan-android")
            }
        }.getOrNull() ?: return@withContext DISCORD_FALLBACK

        try {
            if (connection.responseCode !in 200..299) return@withContext DISCORD_FALLBACK
            val readme = connection.inputStream.bufferedReader().use { it.readText() }
            Regex("""https://discord\.gg/[A-Za-z0-9_-]+""")
                .find(readme)
                ?.value
                ?: DISCORD_FALLBACK
        } catch (_: Exception) {
            DISCORD_FALLBACK
        } finally {
            connection.disconnect()
        }
    }
}
