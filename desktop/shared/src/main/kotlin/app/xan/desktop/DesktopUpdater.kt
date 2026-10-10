package app.xan.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class DesktopPlatform(
    val label: String,
    val os: String,
    val architecture: String,
    val extensions: Set<String>,
) {
    companion object {
        fun current(): DesktopPlatform {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val rawArchitecture = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
            val architecture = when (rawArchitecture) {
                "aarch64", "arm64" -> "arm64"
                "x86_64", "amd64", "x64" -> "x64"
                else -> error("XAN updates are not available for the $rawArchitecture architecture yet.")
            }
            val (os, extensions, label) = when {
                osName.contains("win") -> Triple("windows", setOf("msi", "exe"), "Windows $architecture")
                osName.contains("mac") || osName.contains("darwin") -> Triple("macos", setOf("dmg", "pkg"), "macOS $architecture")
                osName.contains("linux") -> Triple("linux", setOf("deb", "rpm", "appimage"), "Linux $architecture")
                else -> error("XAN updates are not available for $osName yet.")
            }
            return DesktopPlatform(label, os, architecture, extensions)
        }
    }
}

internal data class DesktopRelease(
    val tag: String,
    val name: String,
    val version: String,
    val publishedAt: String,
    val releaseUrl: String,
    val changelog: String,
)

internal data class DesktopUpdateAsset(
    val version: String,
    val name: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val release: DesktopRelease,
)

internal data class DesktopUpdateCheck(
    val latestRelease: DesktopRelease?,
    val latestDesktopAsset: DesktopUpdateAsset?,
    val compatibleUpdate: DesktopUpdateAsset?,
)

internal data class DesktopCommit(val sha: String, val message: String, val committedAt: String, val url: String)

internal data class DesktopUpdateState(
    val checkedAt: Long? = null,
    val check: DesktopUpdateCheck? = null,
    val recentCommits: List<DesktopCommit> = emptyList(),
    val downloadedInstaller: Path? = null,
    val downloadingBytes: Long? = null,
    val downloadSizeBytes: Long? = null,
    val error: String? = null,
    val notice: String? = null,
)

/** Checks only releases published by the XAN GitHub repository and verifies the GitHub SHA-256 digest. */
internal class DesktopUpdateClient(
    private val releasesEndpoint: URI,
    private val updatesDirectory: Path,
    private val currentVersion: String,
    private val platform: DesktopPlatform,
) {
    suspend fun check(): DesktopUpdateCheck = withContext(Dispatchers.IO) {
        val connection = releasesEndpoint.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "XAN-Desktop/$currentVersion")
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "GitHub update check failed (HTTP $code)." }
            val payload = connection.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_RELEASES_RESPONSE_BYTES + 1)
                check(bytes.size <= MAX_RELEASES_RESPONSE_BYTES) { "GitHub returned an unexpectedly large releases response." }
                bytes.toString(Charsets.UTF_8)
            }
            parseReleases(payload, currentVersion, platform, releasesEndpoint)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun recentCommits(): List<DesktopCommit> = withContext(Dispatchers.IO) {
        val endpoint = URI(
            releasesEndpoint.scheme, releasesEndpoint.userInfo, releasesEndpoint.host, releasesEndpoint.port,
            releasesEndpoint.path.substringBeforeLast('/') + "/commits", "per_page=6", null,
        )
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "XAN-Desktop/$currentVersion")
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "GitHub commit lookup failed (HTTP $code)." }
            val bytes = connection.inputStream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
            check(bytes.size <= 2 * 1024 * 1024) { "GitHub returned an unexpectedly large commit response." }
            parseRecentCommits(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(asset: DesktopUpdateAsset, onProgress: (Long, Long) -> Unit = { _, _ -> }): Path = withContext(Dispatchers.IO) {
        require(isTrustedDownloadUrl(URI(asset.downloadUrl), releasesEndpoint)) { "The update download URL is not a trusted XAN GitHub release asset." }
        require(asset.sha256.matches(SHA256_PATTERN)) { "GitHub did not provide a valid SHA-256 digest for this installer." }
        require(asset.sizeBytes in 1..MAX_INSTALLER_BYTES) { "The installer size is missing or exceeds the 2 GB safety limit." }
        require(asset.name.matches(ASSET_FILE_NAME_PATTERN)) { "The installer filename contains unsupported characters." }

        Files.createDirectories(updatesDirectory)
        val destination = updatesDirectory.resolve(asset.name).normalize()
        require(destination.parent == updatesDirectory.toAbsolutePath().normalize() || destination.parent == updatesDirectory.normalize()) {
            "The installer path is outside XAN's update folder."
        }
        val temporary = Files.createTempFile(updatesDirectory, ".xan-update-", ".part")
        var published = false
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        val connection = URI(asset.downloadUrl).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "XAN-Desktop/$currentVersion")
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "The installer download failed (HTTP $code)." }
            check(isTrustedDownloadUrl(connection.url.toURI(), releasesEndpoint)) { "GitHub redirected the installer to an untrusted download host." }
            val contentLength = connection.contentLengthLong
            check(contentLength < 0 || contentLength == asset.sizeBytes) { "GitHub reported a different installer size than the release metadata." }
            connection.inputStream.use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= asset.sizeBytes && received <= MAX_INSTALLER_BYTES) { "The installer download exceeded its advertised size." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(received, asset.sizeBytes)
                    }
                }
            }
            check(received == asset.sizeBytes) { "The installer download is incomplete ($received of ${asset.sizeBytes} bytes)." }
            val actualDigest = digest.digest().toHexString()
            check(actualDigest.equals(asset.sha256, ignoreCase = true)) { "Installer SHA-256 verification failed. The file was discarded." }
            runCatching { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            published = true
            destination
        } catch (failure: Throwable) {
            Files.deleteIfExists(temporary)
            if (published) Files.deleteIfExists(destination)
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    fun findVerifiedDownload(expectedAsset: DesktopUpdateAsset?): Path? {
        if (expectedAsset == null || !Files.isDirectory(updatesDirectory)) return null
        if (expectedAsset.name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in platform.extensions) return null
        if (!isTrustedDownloadUrl(URI(expectedAsset.downloadUrl), releasesEndpoint)) return null
        val installer = updatesDirectory.resolve(expectedAsset.name).normalize()
        if (installer.parent != updatesDirectory.toAbsolutePath().normalize() && installer.parent != updatesDirectory.normalize()) return null
        return installer.takeIf { Files.isRegularFile(it) && runCatching { sha256(it).equals(expectedAsset.sha256, ignoreCase = true) }.getOrDefault(false) }
    }

    fun clearDownloads(): Int {
        if (!Files.isDirectory(updatesDirectory)) return 0
        val ownedExtensions = platform.extensions + setOf("part")
        return Files.list(updatesDirectory).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith(".xan-update-") ||
                        name.endsWith(".sha256", ignoreCase = true) ||
                        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ownedExtensions
                }
                .filter { Files.deleteIfExists(it) }
                .count()
                .toInt()
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RELEASES_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val MAX_INSTALLER_BYTES = 2L * 1024 * 1024 * 1024
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
        private val ASSET_FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,180}")
        private val json = Json { ignoreUnknownKeys = true }
        private val tagVersionPattern = Regex("^v?(\\d+(?:\\.\\d+){1,3}(?:-[0-9A-Za-z.-]+)?)$")
        private val assetNamePattern = Regex("(?i)^xan[-_]?v?(\\d+(?:\\.\\d+){1,3})(?:[-_](.*))?\\.([a-z0-9]+)$")

        fun parseReleases(payload: String, currentVersion: String, platform: DesktopPlatform, apiEndpoint: URI = URI("https://api.github.com")): DesktopUpdateCheck {
            val releases = json.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
                runCatching { element.jsonObject.toDesktopRelease(apiEndpoint) }.getOrNull()
            }
            val stable = releases.filterNot { it.draft || it.prerelease }
                .sortedWith(compareByDescending<ParsedRelease> { SemanticVersion.parse(it.public.version) })
            val latest = stable.firstOrNull()
            val current = SemanticVersion.parse(currentVersion)
            val desktopAssets = stable.asSequence().flatMap { release ->
                release.assets.asSequence().mapNotNull { asset -> asset.toUpdateAsset(release, platform) }
            }.toList()
            val latestDesktop = desktopAssets.maxByOrNull { SemanticVersion.parse(it.version) }
            val compatible = desktopAssets.filter { SemanticVersion.parse(it.version) > current }
                .maxByOrNull { SemanticVersion.parse(it.version) }
            return DesktopUpdateCheck(latestRelease = latest?.publicRelease(), latestDesktopAsset = latestDesktop, compatibleUpdate = compatible)
        }

        fun parseRecentCommits(payload: String): List<DesktopCommit> = json.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
            runCatching {
                val root = element.jsonObject
                val url = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val uri = URI(url)
                if (uri.scheme != "https" || !uri.host.equals("github.com", true) || !uri.path.startsWith("/bxanedot/xan/commit/")) return@runCatching null
                val commit = root.getValue("commit").jsonObject
                val author = commit["author"]?.jsonObject
                DesktopCommit(
                    sha = root.getValue("sha").jsonPrimitive.content,
                    message = commit.getValue("message").jsonPrimitive.content.lineSequence().first().take(240),
                    committedAt = author?.get("date")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = url,
                )
            }.getOrNull()
        }.take(6)

        private data class ParsedRelease(
            val public: DesktopRelease,
            val draft: Boolean,
            val prerelease: Boolean,
            val assets: List<ReleaseAsset>,
        )

        private data class ReleaseAsset(val name: String, val size: Long, val digest: String?, val url: String)

        private fun JsonObject.toDesktopRelease(apiEndpoint: URI): ParsedRelease {
            val tag = getValue("tag_name").jsonPrimitive.content
            val version = tagVersionPattern.matchEntire(tag)?.groupValues?.get(1)
                ?: error("Release tag is not a semantic version.")
            val public = DesktopRelease(
                tag = tag,
                name = getString("name")?.takeIf(String::isNotBlank) ?: tag,
                version = version,
                publishedAt = getString("published_at").orEmpty(),
                releaseUrl = getString("html_url")?.takeIf(::isXanReleasePage).orEmpty(),
                changelog = getString("body")?.take(12_000).orEmpty(),
            )
            val assets = (this["assets"] as? JsonArray).orEmpty().mapNotNull { item ->
                runCatching {
                    val asset = item.jsonObject
                    val name = asset.getString("name") ?: return@runCatching null
                    val url = asset.getString("browser_download_url") ?: return@runCatching null
                    if (!isTrustedDownloadUrl(URI(url), apiEndpoint)) return@runCatching null
                    ReleaseAsset(name, asset.getLong("size") ?: 0L, asset.getString("digest"), url)
                }.getOrNull()
            }
            return ParsedRelease(public, getBoolean("draft"), getBoolean("prerelease"), assets)
        }

        private fun ParsedRelease.publicRelease() = public

        private fun ReleaseAsset.toUpdateAsset(release: ParsedRelease, platform: DesktopPlatform): DesktopUpdateAsset? {
            val match = assetNamePattern.matchEntire(name) ?: return null
            val version = match.groupValues[1]
            val suffix = match.groupValues[2].lowercase(Locale.ROOT)
            val extension = match.groupValues[3].lowercase(Locale.ROOT)
            if (extension !in platform.extensions) return null
            val tokens = suffix.split('-', '_', '.').filter(String::isNotBlank).toSet()
            val osTokens = when (platform.os) {
                "windows" -> setOf("windows", "win", "win64")
                "macos" -> setOf("macos", "mac", "darwin", "osx")
                else -> setOf("linux")
            }
            val architectureTokens = if (platform.architecture == "x64") {
                setOf("x64", "x86_64", "amd64")
            } else setOf("arm64", "aarch64")
            val universal = "universal" in tokens
            val knownPlatformTokens = setOf("windows", "win", "win64", "macos", "mac", "darwin", "osx", "linux")
            val knownArchitectureTokens = setOf("x64", "x86_64", "amd64", "arm64", "aarch64")
            if (tokens.any { it in knownPlatformTokens } && tokens.none { it in osTokens }) return null
            if (tokens.any { it in knownArchitectureTokens } && tokens.none { it in architectureTokens }) return null
            if (tokens.none { it in knownArchitectureTokens } && !universal) return null
            val releaseVersion = SemanticVersion.parse(release.public.version)
            if (SemanticVersion.parse(version) != releaseVersion) return null
            val digest = digest?.removePrefix("sha256:")?.takeIf { it.matches(Regex("(?i)[0-9a-f]{64}")) } ?: return null
            return DesktopUpdateAsset(version, name, url, digest, size, release.public)
        }

        private fun isXanReleasePage(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host.equals("github.com", true) && uri.path.startsWith("/bxanedot/xan/releases/")
        }.getOrDefault(false)

        private fun isTrustedDownloadUrl(uri: URI, endpoint: URI): Boolean {
            if (uri.host.isNullOrBlank()) return false
            val host = uri.host.lowercase(Locale.ROOT)
            val isGitHubHost = host == "github.com" || host.endsWith(".githubusercontent.com")
            val isTestLoopback = endpoint.host in setOf("127.0.0.1", "localhost", "::1") &&
                host in setOf("127.0.0.1", "localhost", "::1") && uri.scheme == "http"
            return (uri.scheme == "https" && isGitHubHost) || isTestLoopback
        }

        private fun JsonObject.getString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.getLong(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        private fun JsonObject.getBoolean(key: String): Boolean = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    }
}

private data class SemanticVersion(val parts: List<Int>, val prerelease: String?) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val compared = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        if (prerelease == null && other.prerelease != null) return 1
        if (prerelease != null && other.prerelease == null) return -1
        return (prerelease ?: "").compareTo(other.prerelease ?: "")
    }

    companion object {
        fun parse(value: String): SemanticVersion {
            val match = Regex("^v?(\\d+(?:\\.\\d+){1,3})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
                .matchEntire(value.trim()) ?: error("Invalid semantic version: $value")
            return SemanticVersion(match.groupValues[1].split('.').map(String::toInt), match.groupValues[2].ifBlank { null })
        }
    }
}

/** App-wide update settings, cached check results and verified installer handoff. */
internal object DesktopUpdater {
    private const val AUTO_UPDATES_KEY = "desktop_auto_updates"
    private const val UPDATE_NOTIFICATIONS_KEY = "desktop_update_notifications"
    private const val LAST_CHECK_KEY = "desktop_update_last_check"
    private const val CHECK_CACHE_KEY = "desktop_update_check_cache"
    private const val COMMITS_CACHE_KEY = "desktop_update_commits_cache"
    private const val DOWNLOADED_ASSET_KEY = "desktop_update_downloaded_asset"
    private val endpoint = URI("https://api.github.com/repos/bxanedot/xan/releases?per_page=100")
    private val client by lazy {
        DesktopUpdateClient(endpoint, DesktopBackend.updateDirectory(), XAN_DESKTOP_VERSION, DesktopPlatform.current())
    }
    private val _state = MutableStateFlow(loadCachedState())
    val state: StateFlow<DesktopUpdateState> = _state.asStateFlow()

    fun automaticUpdatesEnabled(): Boolean = DesktopBackend.catalogueSetting(AUTO_UPDATES_KEY)?.toBooleanStrictOrNull() ?: true

    fun setAutomaticUpdatesEnabled(enabled: Boolean) = DesktopBackend.setCatalogueSetting(AUTO_UPDATES_KEY, enabled.toString())

    fun notificationsEnabled(): Boolean = DesktopBackend.catalogueSetting(UPDATE_NOTIFICATIONS_KEY)?.toBooleanStrictOrNull() ?: true

    fun setNotificationsEnabled(enabled: Boolean) = DesktopBackend.setCatalogueSetting(UPDATE_NOTIFICATIONS_KEY, enabled.toString())

    suspend fun checkForUpdates(): DesktopUpdateState = withContext(Dispatchers.IO) {
        try {
            val result = client.check()
            val previousAvailableVersion = _state.value.check?.compatibleUpdate?.version
            val commits = runCatching { client.recentCommits() }.getOrNull() ?: cachedCommits()
            if (commits.isNotEmpty()) DesktopBackend.setCatalogueSetting(COMMITS_CACHE_KEY, encodeCommits(commits))
            val checkedAt = System.currentTimeMillis()
            val storedInstaller = downloadedAssetMetadata()
            val newerInstallerAvailable = result.compatibleUpdate?.let { latest ->
                storedInstaller != null && SemanticVersion.parse(latest.version) > SemanticVersion.parse(storedInstaller.version)
            } == true
            if (newerInstallerAvailable) {
                client.clearDownloads()
                DesktopBackend.setCatalogueSetting(DOWNLOADED_ASSET_KEY, "")
            }
            DesktopBackend.setCatalogueSetting(LAST_CHECK_KEY, checkedAt.toString())
            DesktopBackend.setCatalogueSetting(CHECK_CACHE_KEY, encodeCheck(result))
            _state.value = DesktopUpdateState(
                checkedAt = checkedAt,
                check = result,
                recentCommits = commits,
                downloadedInstaller = if (newerInstallerAvailable) null else client.findVerifiedDownload(storedInstaller),
                notice = updateNotice(result),
            )
            result.compatibleUpdate?.takeIf { it.version != previousAvailableVersion }?.let(::notifyAboutUpdate)
            DiagnosticLog.info(DesktopBackend.diagnosticsDirectory(), "desktop_update_check_complete", "Checked GitHub for a compatible XAN desktop update.", mapOf("available" to (result.compatibleUpdate != null).toString(), "platform" to DesktopPlatform.current().label))
        } catch (failure: Throwable) {
            _state.value = _state.value.copy(
                downloadedInstaller = client.findVerifiedDownload(downloadedAssetMetadata()),
                error = failure.message ?: "Could not check for updates. Check your internet connection and try again.",
                notice = if (_state.value.check == null) "Showing the last saved update information, if available." else _state.value.notice,
            )
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "desktop_update_check_failed", "Could not check GitHub for XAN desktop updates.", failure)
        }
        _state.value
    }

    suspend fun downloadUpdate(): Path = withContext(Dispatchers.IO) {
        val asset = _state.value.check?.compatibleUpdate ?: error("There is no compatible desktop installer available to download.")
        _state.value = _state.value.copy(error = null, notice = "Downloading and verifying ${asset.name}…", downloadingBytes = 0, downloadSizeBytes = asset.sizeBytes)
        try {
            val path = client.download(asset) { received, total ->
                _state.value = _state.value.copy(downloadingBytes = received, downloadSizeBytes = total)
            }
            DesktopBackend.setCatalogueSetting(DOWNLOADED_ASSET_KEY, asset.toJson().toString())
            _state.value = _state.value.copy(downloadedInstaller = path, downloadingBytes = null, downloadSizeBytes = null, error = null, notice = "Installer downloaded and SHA-256 verified.")
            DiagnosticLog.info(DesktopBackend.diagnosticsDirectory(), "desktop_update_download_verified", "Downloaded and verified a XAN desktop installer.", mapOf("version" to asset.version, "file" to asset.name))
            path
        } catch (failure: Throwable) {
            _state.value = _state.value.copy(downloadedInstaller = client.findVerifiedDownload(downloadedAssetMetadata()), downloadingBytes = null, downloadSizeBytes = null, error = failure.message ?: "The update download failed.", notice = null)
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "desktop_update_download_failed", "Could not download or verify the XAN desktop installer.", failure)
            throw failure
        }
    }

    fun openInstaller(): String {
        val expectedAsset = downloadedAssetMetadata()
        val path = _state.value.downloadedInstaller?.takeIf(Files::isRegularFile)
            ?: client.findVerifiedDownload(expectedAsset)
            ?: error("Download and verify an update before opening its installer.")
        val verified = client.findVerifiedDownload(expectedAsset)
        require(path == verified) { "The installer could not be verified. Download it again before installation." }
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            "This desktop environment cannot open installers automatically. Open this file manually: $path"
        }
        Desktop.getDesktop().open(path.toFile())
        _state.value = _state.value.copy(downloadedInstaller = path, notice = "Installer opened. Follow your system's prompts, then restart XAN.", error = null)
        DiagnosticLog.info(DesktopBackend.diagnosticsDirectory(), "desktop_update_installer_opened", "Opened the verified XAN update installer.", mapOf("file" to path.fileName.toString()))
        return "Installer opened. Follow your system's prompts, then restart XAN."
    }

    fun clearDownloads(): Int {
        val removed = client.clearDownloads()
        DesktopBackend.setCatalogueSetting(DOWNLOADED_ASSET_KEY, "")
        _state.value = _state.value.copy(downloadedInstaller = null, notice = if (removed == 0) "There are no downloaded update files to clear." else "Removed $removed update file${if (removed == 1) "" else "s"}.", error = null)
        return removed
    }

    suspend fun checkOnStartup() {
        if (runCatching { DesktopPlatform.current() }.isFailure) return
        if (!automaticUpdatesEnabled()) return
        val lastCheck = DesktopBackend.catalogueSetting(LAST_CHECK_KEY)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - lastCheck < TimeUnit.HOURS.toMillis(24)) return
        val result = checkForUpdates()
        if (result.error == null && result.check?.compatibleUpdate != null && result.downloadedInstaller == null) {
            runCatching { downloadUpdate() }
        }
    }

    private fun loadCachedState(): DesktopUpdateState {
        val check = DesktopBackend.catalogueSetting(CHECK_CACHE_KEY)?.let(::decodeCheck)
        val commits = cachedCommits()
        val checkedAt = DesktopBackend.catalogueSetting(LAST_CHECK_KEY)?.toLongOrNull()
        val downloaded = runCatching { client.findVerifiedDownload(downloadedAssetMetadata()) }.getOrNull()
        return DesktopUpdateState(checkedAt = checkedAt, check = check, recentCommits = commits, downloadedInstaller = downloaded, notice = check?.let(::updateNotice))
    }

    private fun updateNotice(check: DesktopUpdateCheck): String = when {
        check.compatibleUpdate != null -> "Version ${check.compatibleUpdate.version} is ready for ${DesktopPlatform.current().label}."
        check.latestDesktopAsset != null && SemanticVersion.parse(check.latestDesktopAsset.version) == SemanticVersion.parse(XAN_DESKTOP_VERSION) -> "XAN Desktop is up to date for ${DesktopPlatform.current().label} (version ${check.latestDesktopAsset.version})."
        check.latestDesktopAsset != null -> "The latest desktop installer is version ${check.latestDesktopAsset.version}; there is no newer update for ${DesktopPlatform.current().label}."
        check.latestRelease == null -> "No stable XAN releases were found."
        else -> "No desktop installer is available for ${DesktopPlatform.current().label} yet. The latest published XAN release is ${check.latestRelease.tag}."
    }

    private fun notifyAboutUpdate(asset: DesktopUpdateAsset) {
        if (!notificationsEnabled() || !SystemTray.isSupported()) return
        runCatching {
            val image = javax.imageio.ImageIO.read(DesktopUpdater::class.java.getResource("/${DesktopBackend.appLogo().resource}"))
                ?: error("XAN notification icon is unavailable.")
            val icon = TrayIcon(image, "XAN")
            icon.isImageAutoSize = true
            val tray = SystemTray.getSystemTray()
            tray.add(icon)
            icon.displayMessage("XAN update available", "Version ${asset.version} is ready for ${DesktopPlatform.current().label}.", TrayIcon.MessageType.INFO)
            Timer("xan-update-notification-cleanup", true).schedule(object : TimerTask() {
                override fun run() { runCatching { tray.remove(icon) } }
            }, 8_000)
        }.onFailure { failure ->
            DiagnosticLog.warn(DesktopBackend.diagnosticsDirectory(), "desktop_update_notification_failed", "Could not show a system update notification.", failure)
        }
    }

    private fun downloadedAssetMetadata(): DesktopUpdateAsset? = DesktopBackend.catalogueSetting(DOWNLOADED_ASSET_KEY)
        ?.takeIf(String::isNotBlank)?.let { serialized -> runCatching { Json.parseToJsonElement(serialized).jsonObject.toAsset() }.getOrNull() }

    private fun encodeCheck(check: DesktopUpdateCheck): String = buildJsonObject {
        check.latestRelease?.let { put("latest", it.toJson()) }
        check.latestDesktopAsset?.let { put("latestDesktop", it.toJson()) }
        check.compatibleUpdate?.let { put("update", it.toJson()) }
    }.toString()

    private fun DesktopUpdateAsset.toJson() = buildJsonObject {
        put("version", version); put("name", name); put("downloadUrl", downloadUrl)
        put("sha256", sha256); put("sizeBytes", sizeBytes); put("release", release.toJson())
    }

    private fun encodeCommits(commits: List<DesktopCommit>) = buildJsonArray {
        commits.forEach { commit -> add(buildJsonObject {
            put("sha", commit.sha); put("message", commit.message); put("committedAt", commit.committedAt); put("url", commit.url)
        }) }
    }.toString()

    private fun cachedCommits(): List<DesktopCommit> = DesktopBackend.catalogueSetting(COMMITS_CACHE_KEY)?.let { serialized ->
        runCatching {
            Json.parseToJsonElement(serialized).jsonArray.map { value ->
                val item = value.jsonObject
                DesktopCommit(item.getString("sha").orEmpty(), item.getString("message").orEmpty(), item.getString("committedAt").orEmpty(), item.getString("url").orEmpty())
            }.take(6)
        }.getOrNull()
    }.orEmpty()

    private fun decodeCheck(serialized: String): DesktopUpdateCheck? = runCatching {
        val root = Json.parseToJsonElement(serialized).jsonObject
        val latest = root["latest"]?.jsonObject?.toRelease()
        val latestDesktop = root["latestDesktop"]?.jsonObject?.toAsset()
        val update = root["update"]?.jsonObject?.toAsset()
        DesktopUpdateCheck(latest, latestDesktop, update)
    }.getOrNull()

    private fun JsonObject.toAsset() = DesktopUpdateAsset(
        version = getString("version") ?: error("Missing version"), name = getString("name") ?: error("Missing filename"),
        downloadUrl = getString("downloadUrl") ?: error("Missing URL"), sha256 = getString("sha256") ?: error("Missing digest"),
        sizeBytes = getLong("sizeBytes") ?: 0, release = getValue("release").jsonObject.toRelease(),
    )

    private fun DesktopRelease.toJson() = buildJsonObject {
        put("tag", tag); put("name", name); put("version", version); put("publishedAt", publishedAt); put("releaseUrl", releaseUrl); put("changelog", changelog)
    }

    private fun JsonObject.toRelease() = DesktopRelease(
        tag = getString("tag") ?: "", name = getString("name") ?: "", version = getString("version") ?: "",
        publishedAt = getString("publishedAt") ?: "", releaseUrl = getString("releaseUrl") ?: "", changelog = getString("changelog") ?: "",
    )

    private fun JsonObject.getString(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.getLong(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}
