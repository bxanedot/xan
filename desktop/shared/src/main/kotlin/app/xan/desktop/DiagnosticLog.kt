package app.xan.desktop

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, structured diagnostics. Messages are redacted before they are written or exported. */
internal object DiagnosticLog {
    private const val MAX_FILE_BYTES = 1024L * 1024L
    private const val RETAINED_FILES = 3
    private val lock = Any()

    fun info(directory: Path, event: String, message: String, fields: Map<String, String> = emptyMap()) =
        write(directory, "info", event, message, null, fields)

    fun warn(directory: Path, event: String, message: String, error: Throwable? = null, fields: Map<String, String> = emptyMap()) =
        write(directory, "warn", event, message, error, fields)

    fun error(directory: Path, event: String, message: String, error: Throwable? = null, fields: Map<String, String> = emptyMap()) =
        write(directory, "error", event, message, error, fields)

    fun export(directory: Path, destination: Path): Path = synchronized(lock) {
        Files.createDirectories(directory)
        val logs = logFiles(directory)
        require(logs.isNotEmpty()) { "There are no diagnostic logs to export yet." }
        destination.parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
            logs.forEach { path ->
                zip.putNextEntry(ZipEntry(path.fileName.toString()))
                Files.newInputStream(path).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        destination
    }

    fun clear(directory: Path): Int = synchronized(lock) {
        if (!Files.isDirectory(directory)) return@synchronized 0
        logFiles(directory).count { Files.deleteIfExists(it) }
    }

    fun readForTest(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    private fun write(directory: Path, level: String, event: String, message: String, error: Throwable?, fields: Map<String, String>) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(directory)
                rotateIfNeeded(directory)
                val sanitizedFields = fields.toSortedMap().mapValues { (key, value) ->
                    if (key.matches(Regex("(?i).*(cookie|token|session|password|secret|api[_-]?key|authorization).*"))) "[REDACTED]" else redact(value)
                }
                val fieldJson = sanitizedFields.entries.joinToString(",") { (key, value) -> "${json(key)}:${json(value)}" }
                val exception = error?.let { "\"exception\":${json(it.javaClass.name)},\"exceptionMessage\":${json(redact(it.message.orEmpty()))}," }.orEmpty()
                val record = "{\"time\":${json(Instant.now().toString())},\"level\":${json(level)},\"event\":${json(redact(event))},\"message\":${json(redact(message))},$exception\"fields\":{$fieldJson}}"
                Files.newBufferedWriter(activeFile(directory), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).use { writer ->
                    writer.append(record).append('\n')
                }
            }
        }
    }

    private fun rotateIfNeeded(directory: Path) {
        val active = activeFile(directory)
        if (!Files.exists(active) || Files.size(active) < MAX_FILE_BYTES) return
        for (index in RETAINED_FILES - 2 downTo 1) {
            val from = directory.resolve("xan-$index.jsonl")
            val to = directory.resolve("xan-${index + 1}.jsonl")
            if (Files.exists(from)) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.deleteIfExists(directory.resolve("xan-$RETAINED_FILES.jsonl"))
        Files.move(active, directory.resolve("xan-1.jsonl"), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun logFiles(directory: Path): List<Path> = (1 until RETAINED_FILES)
        .map { index -> if (index == 1) activeFile(directory) else directory.resolve("xan-$index.jsonl") }
        .filter(Files::isRegularFile)

    private fun activeFile(directory: Path) = directory.resolve("xan.jsonl")

    internal fun redact(value: String): String {
        var result = value
        result = result.replace(Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+")) { "${it.groupValues[1]}[REDACTED]" }
        result = result.replace(Regex("(?i)((?:cookie|set-cookie|token|access_token|refresh_token|session|password|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s&;,]+")) { "${it.groupValues[1]}[REDACTED]" }
        result = result.replace(Regex("(?i)([?&](?:token|access_token|refresh_token|key|signature|sig|cookie|auth)=)[^&\\s]+")) { "${it.groupValues[1]}[REDACTED]" }
        result = result.replace(Regex("(?i)https?://[^\\s]+")) { match ->
            runCatching {
                val uri = java.net.URI(match.value.trimEnd('.', ',', ')', ']'))
                val safe = java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, if (uri.rawQuery == null) null else "[REDACTED]", null).toASCIIString()
                safe + match.value.substring(uri.toString().length)
            }.getOrDefault("[URL]")
        }
        return result.take(4_000)
    }

    private fun json(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
