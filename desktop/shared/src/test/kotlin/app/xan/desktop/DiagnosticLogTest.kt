package app.xan.desktop

import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun logsAreStructuredRedactedAndExportableThenCanBeCleared() {
        val directory = Files.createTempDirectory("xan-diagnostics-")
        val archive = directory.resolveSibling("${directory.fileName}.zip")
        try {
            DiagnosticLog.error(
                directory,
                "request_failed",
                "Playback failed at https://example.test/watch?v=abc&token=secret123",
                IllegalStateException("Authorization: Bearer top-secret"),
                mapOf("service" to "YouTube", "cookie" to "SID=private-cookie"),
            )
            val text = Files.readString(directory.resolve("xan.jsonl"))
            assertTrue(text.startsWith("{\"time\":"))
            assertTrue(text.contains("\"level\":\"error\""))
            assertTrue(text.contains("\"event\":\"request_failed\""))
            assertFalse(text.contains("v=abc"))
            assertFalse(text.contains("secret123"))
            assertFalse(text.contains("top-secret"))
            assertFalse(text.contains("private-cookie"))
            assertTrue(text.contains("[REDACTED]"))

            DiagnosticLog.export(directory, archive)
            ZipFile(archive.toFile()).use { zip ->
                val entry = zip.getEntry("xan.jsonl")
                assertTrue(entry != null)
                val exported = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                assertFalse(exported.contains("secret123"))
                assertTrue(exported.contains("request_failed"))
            }
            assertEquals(1, DiagnosticLog.clear(directory))
            assertFalse(Files.exists(directory.resolve("xan.jsonl")))
        } finally {
            Files.deleteIfExists(archive)
            Files.deleteIfExists(directory.resolve("xan.jsonl"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rotatesLogsAndKeepsOnlyTheConfiguredHistory() {
        val directory = Files.createTempDirectory("xan-log-rotation-")
        try {
            val largeMessage = "x".repeat(4_000)
            repeat(800) { index -> DiagnosticLog.info(directory, "rotation_$index", largeMessage) }
            val names = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
            assertEquals(setOf("xan.jsonl", "xan-1.jsonl", "xan-2.jsonl"), names)
        } finally {
            Files.list(directory).use { stream -> stream.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }
}
