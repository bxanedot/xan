package app.xan.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Versioned, integrity-checked backup container. It contains no account credentials. */
internal object BackupArchive {
    private const val FORMAT_VERSION = 1
    private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 1024L * 1024
    private const val DATABASE_ENTRY = "catalogue.sqlite3"
    private const val SETTINGS_ENTRY = "settings.properties"
    private const val MANIFEST_ENTRY = "manifest.properties"

    fun create(database: Path, settings: Properties, destination: Path) {
        require(Files.isRegularFile(database)) { "The XAN catalogue database does not exist yet." }
        val target = destination.toAbsolutePath().normalize()
        require(target != database.toAbsolutePath().normalize()) { "Choose a separate file for the backup." }
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, ".xan-backup-", ".tmp")
        try {
            val settingsBytes = settings.toBytes()
            val manifest = Properties().apply {
                setProperty("formatVersion", FORMAT_VERSION.toString())
                setProperty("createdAt", System.currentTimeMillis().toString())
                setProperty("sha256.$DATABASE_ENTRY", sha256(database))
                setProperty("sha256.$SETTINGS_ENTRY", sha256(settingsBytes))
            }.toBytes()
            ZipOutputStream(Files.newOutputStream(staged)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY)); zip.write(manifest); zip.closeEntry()
                zip.putNextEntry(ZipEntry(SETTINGS_ENTRY)); zip.write(settingsBytes); zip.closeEntry()
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                Files.newInputStream(database).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            Files.deleteIfExists(staged)
            throw failure
        }
    }

    /** Validates the archive and writes its database to a caller-owned temporary path. */
    fun read(source: Path, stagedDatabase: Path): Properties {
        require(Files.isRegularFile(source)) { "Choose an existing XAN backup file." }
        ZipFile(source.toFile()).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: error("This backup has no manifest.")
            val settingsEntry = zip.getEntry(SETTINGS_ENTRY) ?: error("This backup has no settings file.")
            val databaseEntry = zip.getEntry(DATABASE_ENTRY) ?: error("This backup has no catalogue database.")
            require(manifestEntry.size in 0..MAX_METADATA_BYTES) { "The backup manifest is too large." }
            require(settingsEntry.size in 0..MAX_METADATA_BYTES) { "The backup settings are too large." }
            require(databaseEntry.size in 1..MAX_DATABASE_BYTES) { "The backup database size is invalid." }
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBounded(MAX_METADATA_BYTES) }
            val manifest = Properties().apply { manifestBytes.inputStream().use { load(it) } }
            val version = manifest.getProperty("formatVersion")?.toIntOrNull() ?: error("The backup format version is missing.")
            require(version in 1..FORMAT_VERSION) { "This XAN version cannot restore backup format $version." }
            val settingsBytes = zip.getInputStream(settingsEntry).use { it.readBounded(MAX_METADATA_BYTES) }
            require(sha256(settingsBytes) == manifest.getProperty("sha256.$SETTINGS_ENTRY")) { "Backup settings failed their integrity check." }
            Files.createDirectories(stagedDatabase.toAbsolutePath().parent)
            zip.getInputStream(databaseEntry).use { input -> Files.newOutputStream(stagedDatabase).use { output -> input.copyBoundedTo(output, MAX_DATABASE_BYTES) } }
            require(sha256(stagedDatabase) == manifest.getProperty("sha256.$DATABASE_ENTRY")) { "Backup catalogue failed its integrity check." }
            return Properties().apply { settingsBytes.inputStream().use { load(it) } }
        }
    }

    private fun Properties.toBytes(): ByteArray = java.io.ByteArrayOutputStream().use { output -> store(output, null); output.toByteArray() }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun java.io.InputStream.readBounded(limit: Long): ByteArray = use { input ->
        val output = java.io.ByteArrayOutputStream()
        input.copyBoundedTo(output, limit)
        output.toByteArray()
    }

    private fun java.io.InputStream.copyBoundedTo(output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "A backup entry exceeds the supported size limit." }
            output.write(buffer, 0, count)
        }
    }
}
