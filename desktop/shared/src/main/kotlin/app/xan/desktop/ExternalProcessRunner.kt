package app.xan.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

internal data class ExternalProcessResult(
    val exitCode: Int,
    val outputTail: List<String>,
)

/** Runs a helper process with bounded output, timeout handling, and coroutine cancellation cleanup. */
internal object ExternalProcessRunner {
    suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
        timeoutMessage: String,
    ): ExternalProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "A process command is required." }
        require(timeoutMillis > 0) { "The process timeout must be positive." }
        var process: Process? = null
        var readerThread: Thread? = null
        val output = OutputTail(MAX_OUTPUT_LINES)
        try {
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val outputReader = process.inputStream
            readerThread = Thread({
                runCatching {
                    BufferedReader(InputStreamReader(outputReader)).useLines { lines -> lines.forEach(output::add) }
                }
            }, "xan-helper-output-reader").apply {
                isDaemon = true
                start()
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                val exited = try {
                    process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    currentCoroutineContext().ensureActive()
                    throw interrupted
                }
                if (exited) break
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw IllegalStateException(timeoutMessage)
            }

            readerThread.join(READER_JOIN_MILLIS)
            ExternalProcessResult(process.exitValue(), output.snapshot())
        } finally {
            process?.let(::stop)
            runCatching { process?.inputStream?.close() }
            readerThread?.let { thread ->
                try {
                    thread.join(READER_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun stop(process: Process) {
        if (!process.isAlive) return
        val descendants = process.descendants().toList()
        descendants.forEach { child -> runCatching { child.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS)
        while (process.isAlive && System.nanoTime() < deadline) {
            try {
                process.waitFor(STOP_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        descendants.forEach { child ->
            while (child.isAlive && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(STOP_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private class OutputTail(private val capacity: Int) {
        private val lines = ArrayDeque<String>(capacity)

        @Synchronized
        fun add(line: String) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(line)
        }

        @Synchronized
        fun snapshot(): List<String> = lines.toList()
    }

    private const val MAX_OUTPUT_LINES = 80
    private const val PROCESS_POLL_MILLIS = 200L
    private const val STOP_GRACE_MILLIS = 1_000L
    private const val STOP_POLL_MILLIS = 20L
    private const val READER_JOIN_MILLIS = 1_000L
}
