package app.xan.desktop

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

internal object DesktopDataPaths {
    private const val MIGRATION_MARKER = ".legacy-install-data-imported-v1"
    private val legacyWindowsData = listOf("catalogue.sqlite3", "cache", "logs", "tools", "updates")

    fun resolve(osName: String, userHome: Path, environment: Map<String, String>): Path {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> Path.of(
                environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
                    ?: userHome.resolve("AppData/Local").toString(),
            ).resolve("bxane").resolve("XAN")
            os.contains("mac") -> userHome.resolve("Library/Application Support/XAN")
            else -> Path.of(
                environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)
                    ?: userHome.resolve(".local/share").toString(),
            ).resolve("xan")
        }
    }

    /** Import user data left beside the old Windows app image, never the installed binaries. */
    fun importLegacyWindowsData(legacyDirectory: Path, destinationDirectory: Path) {
        if (legacyDirectory.toAbsolutePath().normalize() == destinationDirectory.toAbsolutePath().normalize()) return
        Files.createDirectories(destinationDirectory)
        val marker = destinationDirectory.resolve(MIGRATION_MARKER)
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return

        for (name in legacyWindowsData) {
            val source = legacyDirectory.resolve(name)
            val destination = destinationDirectory.resolve(name)
            when {
                Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) -> {
                    Files.walk(source).use { paths ->
                        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                            .forEach { file -> copyIfMissing(file, destination.resolve(source.relativize(file))) }
                    }
                }
                Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) -> copyIfMissing(source, destination)
            }
        }

        Files.writeString(marker, "Imported legacy XAN per-user data.\n")
    }

    private fun copyIfMissing(source: Path, destination: Path) {
        Files.createDirectories(destination.parent)
        try {
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (_: FileAlreadyExistsException) {
            // A previously imported or newer copy wins over the legacy file.
        }
    }
}
