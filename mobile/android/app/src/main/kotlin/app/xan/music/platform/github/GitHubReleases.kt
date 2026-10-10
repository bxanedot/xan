package app.xan.music.platform.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

data class GitHubReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

data class GitHubRelease(
    val tagName: String,
    val title: String,
    val body: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val htmlUrl: String,
    val assets: List<GitHubReleaseAsset>,
) {
    val displayDate: String
        get() = runCatching {
            OffsetDateTime.parse(publishedAt)
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        }.getOrElse { publishedAt }
}

object GitHubReleases {
    private const val API_URL = "https://api.github.com/repos/bxanedot/xan/releases?per_page=100"
    private const val ATOM_URL = "https://github.com/bxanedot/xan/releases.atom"
    private const val DOWNLOAD_URL = "https://github.com/bxanedot/xan/releases/download"
    private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
    private val stableTagPattern = Regex("^v?[0-9]+(?:\\.[0-9]+){1,3}$")

    suspend fun fetch(includePrereleases: Boolean): Result<List<GitHubRelease>> =
        withContext(Dispatchers.IO) {
            val apiResult = runCatching {
                parseApiReleases(
                    payload = requestText(API_URL, "application/vnd.github+json"),
                    includePrereleases = includePrereleases,
                )
            }
            if (apiResult.isSuccess) return@withContext apiResult

            val apiFailure = apiResult.exceptionOrNull()!!
            if (apiFailure is CancellationException) throw apiFailure

            val feedResult = runCatching {
                parseAtomFeed(
                    xml = requestText(ATOM_URL, "application/atom+xml, application/xml;q=0.9"),
                    includePrereleases = includePrereleases,
                )
            }
            if (feedResult.isSuccess) return@withContext feedResult

            val feedFailure = feedResult.exceptionOrNull()!!
            if (feedFailure is CancellationException) throw feedFailure
            feedFailure.addSuppressed(apiFailure)
            Result.failure(feedFailure)
        }

    private fun requestText(url: String, accept: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "xan-android")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("GitHub release request failed ($responseCode): $body")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseApiReleases(payload: String, includePrereleases: Boolean): List<GitHubRelease> {
        val array = JSONArray(payload)
        return buildList {
            for (index in 0 until array.length()) {
                val release = array.getJSONObject(index)
                if (release.optBoolean("draft", false)) continue

                val prerelease = release.optBoolean("prerelease", false)
                if (!includePrereleases && prerelease) continue

                val assetsJson = release.optJSONArray("assets") ?: JSONArray()
                val assets = buildList {
                    for (assetIndex in 0 until assetsJson.length()) {
                        val asset = assetsJson.getJSONObject(assetIndex)
                        add(
                            GitHubReleaseAsset(
                                name = asset.optString("name"),
                                sizeBytes = asset.optLong("size"),
                                downloadUrl = asset.optString("browser_download_url"),
                            )
                        )
                    }
                }

                val tag = release.optString("tag_name")
                add(
                    GitHubRelease(
                        tagName = tag,
                        title = release.optString("name").ifBlank { tag },
                        body = release.optString("body"),
                        publishedAt = release.optString("published_at")
                            .ifBlank { release.optString("created_at") },
                        prerelease = prerelease,
                        htmlUrl = release.optString("html_url"),
                        assets = assets,
                    )
                )
            }
        }
    }

    internal fun parseAtomFeed(xml: String, includePrereleases: Boolean): List<GitHubRelease> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry")

        return buildList {
            for (index in 0 until entries.length) {
                val entry = entries.item(index) as? Element ?: continue
                val title = entry.atomText("title")
                val htmlUrl = entry.atomLink()
                val tag = htmlUrl.substringAfterLast('/').ifBlank { title }
                if (tag.isBlank()) continue

                val prerelease = !stableTagPattern.matches(tag)
                if (!includePrereleases && prerelease) continue

                val encodedTag = tag.replace("/", "%2F")
                add(
                    GitHubRelease(
                        tagName = tag,
                        title = title.ifBlank { tag },
                        body = releaseHtmlToMarkdown(entry.atomText("content")),
                        publishedAt = entry.atomText("updated"),
                        prerelease = prerelease,
                        htmlUrl = htmlUrl,
                        assets = listOf(
                            GitHubReleaseAsset(
                                name = "xan.apk",
                                sizeBytes = 0L,
                                downloadUrl = "$DOWNLOAD_URL/$encodedTag/xan.apk",
                            )
                        ),
                    )
                )
            }
        }
    }

    private fun Element.atomText(name: String): String =
        (getElementsByTagNameNS(ATOM_NAMESPACE, name).item(0)?.textContent).orEmpty().trim()

    private fun Element.atomLink(): String {
        val links = getElementsByTagNameNS(ATOM_NAMESPACE, "link")
        for (index in 0 until links.length) {
            val link = links.item(index) as? Element ?: continue
            if (link.getAttribute("rel") == "alternate") return link.getAttribute("href")
        }
        return ""
    }

    private fun releaseHtmlToMarkdown(html: String): String = html
        .replace(Regex("(?i)<h[1-6][^>]*>"), "\n### ")
        .replace(Regex("(?i)</h[1-6]>"), "\n")
        .replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
        .replace(Regex("(?i)</li>"), "\n")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")) { match ->
            val entity = match.groupValues[1]
            when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" || entity == "#39" -> "'"
                entity == "nbsp" -> " "
                entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                    ?.takeIf { it in 0..Character.MAX_CODE_POINT }
                    ?.let { Character.toChars(it).concatToString() }
                    ?: match.value
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                    ?.takeIf { it in 0..Character.MAX_CODE_POINT }
                    ?.let { Character.toChars(it).concatToString() }
                    ?: match.value
                else -> match.value
            }
        }
        .trim()
}
