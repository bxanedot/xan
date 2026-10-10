package app.xan.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import org.junit.Assert.*
import org.junit.Test

class JapaneseRomanizationPackTest {
    @Test
    fun realDictionaryCanBeInstalledLoadedRemovedAndReinstalled() {
        val folder = Files.createTempDirectory("xan-japanese-pack-")
        val store = JapaneseRomanizationPack(folder)
        try {
            val source = Path.of(System.getProperty("xan.japanesePackJar"))
            assertTrue("Build the standalone feature pack before this check", Files.isRegularFile(source))
            val checksum = sha256(source)
            assertFalse(store.installed())
            assertNull(store.romanize("こんにちは"))
            store.install(source, checksum)
            assertTrue(store.installed())
            assertEquals("ongaku", store.romanize("音楽")?.replace(" ", ""))
            assertTrue(store.uninstall())
            assertFalse(store.installed())
            assertNull(store.romanize("こんにちは"))
            assertEquals(0L, Files.list(folder).use { it.count() })
            store.install(source, checksum)
            assertNotNull(store.romanize("音楽"))
        } finally {
            store.uninstall()
            Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun corruptOrInvalidUpdatePreservesTheWorkingPack() {
        val folder = Files.createTempDirectory("xan-japanese-pack-update-")
        val store = JapaneseRomanizationPack(folder.resolve("installed"))
        try {
            val source = Path.of(System.getProperty("xan.japanesePackJar"))
            store.install(source, sha256(source))
            val before = store.romanize("こんにちは")
            assertTrue(runCatching { store.install(source, "0".repeat(64)) }.isFailure)
            assertEquals(before, store.romanize("こんにちは"))
            val invalid = folder.resolve("invalid.jar")
            Files.writeString(invalid, "not a feature pack")
            assertTrue(runCatching { store.install(invalid, sha256(invalid)) }.isFailure)
            assertTrue(store.installed())
            assertEquals(before, store.romanize("こんにちは"))
        } finally {
            store.uninstall()
            Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun sha256(path: Path) = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
