package app.xan.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExternalProcessRunnerTest {
    @Test
    fun capturesOnlyRecentOutputAndReturnsExitCode() = runBlocking {
        val directory = Files.createTempDirectory("xan-process-result-")
        try {
            val expectedTail = (0 until 100).map(Int::toString).takeLast(80)
            val result = ExternalProcessRunner.run(
                command = printCommand(*(0 until 100).map(Int::toString).toTypedArray()),
                timeoutMillis = 10_000,
                timeoutMessage = "timed out",
            )
            assertEquals(0, result.exitCode)
            assertEquals(expectedTail, result.outputTail)
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally {
            deleteDirectory(directory)
        }
    }

    @Test
    fun killsTimedOutProcessAndRemovesItsOutputFile() = runBlocking {
        val directory = Files.createTempDirectory("xan-process-timeout-")
        try {
            val failure = runCatching {
                ExternalProcessRunner.run(
                    command = sleepCommand(),
                    timeoutMillis = 300,
                    timeoutMessage = "test process timed out",
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("test process timed out", failure?.message)
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally {
            deleteDirectory(directory)
        }
    }

    @Test
    fun coroutineCancellationTerminatesTheChildAndRemovesItsOutputFile() = runBlocking {
        val directory = Files.createTempDirectory("xan-process-cancel-")
        try {
            val failure = runCatching {
                withTimeout(300) {
                    ExternalProcessRunner.run(
                        command = sleepCommand(),
                        timeoutMillis = 10_000,
                        timeoutMessage = "test process timed out",
                    )
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally {
            deleteDirectory(directory)
        }
    }

    private fun printCommand(vararg lines: String): List<String> = if (isWindows()) {
        listOf("cmd.exe", "/c", "echo ${lines.joinToString("& echo ")}")
    } else {
        listOf("sh", "-c", "printf '%s\\n' '${lines.joinToString("' '")}'")
    }

    private fun sleepCommand(): List<String> = if (isWindows()) {
        val ping = java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "ping.exe").toString()
        listOf(ping, "-n", "10", "127.0.0.1")
    } else {
        listOf("sh", "-c", "sleep 10")
    }

    private fun isWindows() = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    private fun deleteDirectory(directory: java.nio.file.Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
