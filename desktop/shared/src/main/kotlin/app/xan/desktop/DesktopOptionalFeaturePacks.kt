package app.xan.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile

internal class JapaneseRomanizationPack(private val directory: Path) {
    private val lock = Any()
    private var loader: URLClassLoader? = null
    private var romanizer: Any? = null
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()

    fun installed(): Boolean = synchronized(lock) { Files.isRegularFile(path()) }

    fun romanize(text: String): String? = synchronized(lock) {
        if (!Files.isRegularFile(path())) { unload(); return@synchronized null }
        val instance = romanizer ?: runCatching {
            val next = open(path())
            loader = next.first
            romanizer = next.second
            next.second
        }.getOrNull() ?: return@synchronized null
        runCatching { instance.javaClass.getMethod("romanize", String::class.java).invoke(instance, text) as? String }.getOrNull()
    }

    /** Validate the complete candidate before replacing a working dictionary. */
    fun install(source: Path, expectedChecksum: String): Path {
        require(expectedChecksum.matches(Regex("[0-9a-fA-F]{64}"))) { "The Japanese pack checksum is invalid." }
        Files.createDirectories(directory)
        val candidate = Files.createTempFile(directory, ".japanese-", ".jar")
        try {
            require(Files.size(source) in 1..MAX_PACK_BYTES) { "The Japanese pack is empty or unexpectedly large." }
            Files.copy(source, candidate, StandardCopyOption.REPLACE_EXISTING)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(candidate).use { input ->
                val bytes = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedChecksum, ignoreCase = true)) { "The Japanese pack failed its checksum check." }
            val probe = open(candidate)
            try {
                val reading = probe.second.javaClass.getMethod("romanize", String::class.java)
                    .invoke(probe.second, "こんにちは") as? String
                require(!reading.isNullOrBlank() && reading != "こんにちは") { "The Japanese dictionary could not be initialized." }
            } finally { probe.first.close() }
            synchronized(lock) {
                unload()
                try { Files.move(candidate, path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(candidate, path(), StandardCopyOption.REPLACE_EXISTING)
                }
                changes.value++
            }
            return path()
        } finally { Files.deleteIfExists(candidate) }
    }

    fun uninstall(): Boolean = synchronized(lock) {
        unload()
        Files.deleteIfExists(path()).also { if (it) changes.value++ }
    }

    private fun unload() {
        romanizer = null
        loader?.close()
        loader = null
    }

    private fun path(): Path = directory.resolve(ASSET)

    private fun open(path: Path): Pair<URLClassLoader, Any> {
        JarFile(path.toFile()).use {
            require(it.manifest?.mainAttributes?.getValue("XAN-Feature-Id") == "japanese-romanization") {
                "This file is not a XAN Japanese romanization pack."
            }
        }
        val nextLoader = URLClassLoader(arrayOf(path.toUri().toURL()), JapaneseRomanizationPack::class.java.classLoader)
        return try {
            val plugin = Class.forName("app.xan.desktop.DesktopJapaneseRomanizer", true, nextLoader)
            nextLoader to plugin.getField("INSTANCE").get(null)
        } catch (failure: Throwable) { nextLoader.close(); throw failure }
    }

    companion object {
        const val ASSET = "xan-feature-japanese-romanization.jar"
        const val MAX_PACK_BYTES = 128L * 1024 * 1024
    }
}

/** Optional desktop capabilities live outside the application bundle and can be deleted. */
internal object DesktopOptionalFeaturePacks {
    private val japanese by lazy { JapaneseRomanizationPack(DesktopBackend.featurePackDirectory()) }
    private val installation = Mutex()
    val revision get() = japanese.revision
    fun japaneseInstalled() = japanese.installed()
    fun containsJapanese(text: String) = text.any { it in '\u3040'..'\u30FF' }
    fun romanizeJapanese(text: String) = japanese.romanize(text)
    fun uninstallJapanesePack() = japanese.uninstall()

    suspend fun installJapanesePack(source: Path? = null): Path = withContext(Dispatchers.IO) {
        installation.withLock {
            if (source != null) {
                val checksum = source.resolveSibling("${source.fileName}.sha256")
                require(Files.isRegularFile(checksum)) { "Choose the pack JAR with its matching .sha256 file in the same folder." }
                return@withLock japanese.install(source, Files.readString(checksum).trim().split(Regex("\\s+")).first())
            }
            val directory = DesktopBackend.featurePackDirectory()
            Files.createDirectories(directory)
            val candidate = Files.createTempFile(directory, ".download-japanese-", ".jar")
            val checksum = Files.createTempFile(directory, ".download-japanese-", ".sha256")
            val root = "https://github.com/bxanedot/xan/releases/latest/download"
            try {
                download("$root/${JapaneseRomanizationPack.ASSET}", candidate, JapaneseRomanizationPack.MAX_PACK_BYTES)
                download("$root/${JapaneseRomanizationPack.ASSET}.sha256", checksum, 4_096)
                japanese.install(candidate, Files.readString(checksum).trim().split(Regex("\\s+")).first())
            } finally {
                Files.deleteIfExists(candidate)
                Files.deleteIfExists(checksum)
            }
        }
    }

    private suspend fun download(url: String, destination: Path, maximumBytes: Long) {
        val connection = java.net.URI(url).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "XAN-Desktop")
        }
        try {
            if (connection is java.net.HttpURLConnection && connection.responseCode == 404) {
                error("The latest release has no Japanese pack yet. Use Install from file with a XAN pack and its checksum.")
            }
            require(connection.contentLengthLong <= maximumBytes || connection.contentLengthLong < 0) { "The feature pack is unexpectedly large." }
            connection.getInputStream().use { input ->
                Files.newOutputStream(destination).use { output ->
                    val bytes = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= maximumBytes) { "The feature pack is unexpectedly large." }
                        output.write(bytes, 0, count)
                    }
                }
            }
            require(Files.size(destination) > 0) { "The feature pack download was empty." }
        } finally { (connection as? java.net.HttpURLConnection)?.disconnect() }
    }
}
