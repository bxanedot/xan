package app.xan.desktop

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Recursively watches configured music folders and coalesces bursts of file events into one scan. */
internal class MusicLibraryWatcher(
    private val roots: () -> List<Path>,
    private val scan: () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val quietMillis: Long = 450,
) : AutoCloseable {
    private val audioExtensions = setOf("mp3", "flac", "ogg", "opus", "m4a", "mp4", "aif", "aiff", "wav", "wma", "dsf", "dsd")
    private val running = AtomicBoolean(false)
    @Volatile private var service: WatchService? = null
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val watcher = FileSystems.getDefault().newWatchService()
        service = watcher
        val directories = mutableMapOf<WatchKey, Path>()
        roots().forEach { registerTree(it, watcher, directories) }
        worker = Thread({ watchLoop(watcher, directories) }, "xan-music-library-watcher").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun refresh() {
        close()
        start()
    }

    @Synchronized
    override fun close() {
        running.set(false)
        runCatching { service?.close() }
        service = null
        worker = null
    }

    private fun watchLoop(watcher: WatchService, directories: MutableMap<WatchKey, Path>) {
        try {
            while (running.get()) {
                val first = watcher.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var changed = handleKey(first, watcher, directories)
                var lastEventAt = System.nanoTime()
                while (running.get()) {
                    val remainingNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis) - (System.nanoTime() - lastEventAt)
                    if (remainingNanos <= 0L) break
                    val next = watcher.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: break
                    changed = handleKey(next, watcher, directories) || changed
                    lastEventAt = System.nanoTime()
                }
                if (changed && running.get()) runCatching(scan).onFailure(onFailure)
            }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            // close() is the normal lifecycle path.
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        } finally {
            directories.keys.forEach(WatchKey::cancel)
        }
    }

    private fun handleKey(key: WatchKey, watcher: WatchService, directories: MutableMap<WatchKey, Path>): Boolean {
        val parent = directories[key]
        var changed = false
        if (parent != null) {
            key.pollEvents().forEach { event ->
                if (event.kind() == ENTRY_CREATE || event.kind() == ENTRY_DELETE || event.kind() == ENTRY_MODIFY) {
                    val changedPath = parent.resolve(event.context() as Path)
                    val isDirectory = Files.isDirectory(changedPath) || changedPath.fileName.toString().substringAfterLast('.', "").isBlank()
                    val isAudio = changedPath.fileName.toString().substringAfterLast('.', "").lowercase() in audioExtensions
                    if (isDirectory || isAudio) changed = true
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(changedPath)) registerTree(changedPath, watcher, directories)
                }
            }
        } else key.pollEvents()
        if (!key.reset()) directories.remove(key)
        return changed
    }

    private fun registerTree(root: Path, watcher: WatchService, directories: MutableMap<WatchKey, Path>) {
        if (!Files.isDirectory(root)) return
        runCatching {
            Files.walk(root).use { paths ->
                paths.filter(Files::isDirectory).forEach { directory ->
                    val key = runCatching {
                        directory.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    }.getOrNull()
                    if (key != null) directories[key] = directory
                }
            }
        }
    }
}
