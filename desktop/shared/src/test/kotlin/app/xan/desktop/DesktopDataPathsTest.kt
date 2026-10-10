package app.xan.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDataPathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun windowsDataDirectoryDoesNotOverlapTheJpackageInstallDirectory() {
        val localAppData = temporaryFolder.root.toPath().resolve("AppData/Local")
        val home = temporaryFolder.root.toPath()

        val dataDirectory = DesktopDataPaths.resolve(
            osName = "Windows 11",
            userHome = home,
            environment = mapOf("LOCALAPPDATA" to localAppData.toString()),
        )

        assertEquals(localAppData.resolve("bxane/XAN"), dataDirectory)
        assertFalse(dataDirectory.startsWith(localAppData.resolve("XAN")))
    }

    @Test
    fun migrationCopiesUserDataButLeavesInstalledFilesBehind() {
        val localAppData = temporaryFolder.root.toPath().resolve("AppData/Local")
        val legacy = localAppData.resolve("XAN")
        val destination = localAppData.resolve("bxane/XAN")
        Files.createDirectories(legacy.resolve("app"))
        Files.createDirectories(legacy.resolve("runtime"))
        Files.createDirectories(legacy.resolve("cache/audio"))
        Files.createDirectories(legacy.resolve("logs"))
        Files.createDirectories(legacy.resolve("tools"))
        Files.writeString(legacy.resolve("catalogue.sqlite3"), "catalogue")
        Files.writeString(legacy.resolve("cache/audio/track.aac"), "cached audio")
        Files.writeString(legacy.resolve("logs/xan.log"), "log")
        Files.writeString(legacy.resolve("tools/yt-dlp.exe"), "tool")
        Files.writeString(legacy.resolve("app/installed.jar"), "app")
        Files.writeString(legacy.resolve("runtime/java.exe"), "runtime")
        Files.writeString(legacy.resolve("XAN.exe"), "launcher")

        DesktopDataPaths.importLegacyWindowsData(legacy, destination)

        assertEquals("catalogue", Files.readString(destination.resolve("catalogue.sqlite3")))
        assertEquals("cached audio", Files.readString(destination.resolve("cache/audio/track.aac")))
        assertEquals("log", Files.readString(destination.resolve("logs/xan.log")))
        assertEquals("tool", Files.readString(destination.resolve("tools/yt-dlp.exe")))
        assertFalse(Files.exists(destination.resolve("app")))
        assertFalse(Files.exists(destination.resolve("runtime")))
        assertFalse(Files.exists(destination.resolve("XAN.exe")))
        assertTrue(Files.exists(legacy.resolve("app/installed.jar")))
    }

    @Test
    fun migrationDoesNotOverwriteExistingDestinationDataOrRepeat() {
        val localAppData = temporaryFolder.root.toPath().resolve("AppData/Local")
        val legacy = localAppData.resolve("XAN")
        val destination = localAppData.resolve("bxane/XAN")
        Files.createDirectories(legacy)
        Files.createDirectories(destination)
        Files.writeString(legacy.resolve("catalogue.sqlite3"), "legacy")
        Files.writeString(destination.resolve("catalogue.sqlite3"), "current")

        DesktopDataPaths.importLegacyWindowsData(legacy, destination)
        Files.writeString(legacy.resolve("logs-after-import.txt"), "late")
        DesktopDataPaths.importLegacyWindowsData(legacy, destination)

        assertEquals("current", Files.readString(destination.resolve("catalogue.sqlite3")))
        assertFalse(Files.exists(destination.resolve("logs-after-import.txt")))
    }
}
