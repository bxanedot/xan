package app.xan.desktop

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryWatcherTest {
    @Test
    fun directoryChangesAreCoalescedAndTriggerLibraryRefresh() {
        val root = Files.createTempDirectory("xan-library-watch-")
        val scans = AtomicInteger()
        val watcher = MusicLibraryWatcher(roots = { listOf(root) }, scan = { scans.incrementAndGet() }, quietMillis = 100)
        try {
            watcher.start()
            val first = root.resolve("first.mp3")
            Files.write(first, byteArrayOf(1, 2, 3))
            assertTrue("Creating an audio file should trigger a library scan", awaitScans(scans, 1))

            val afterAudio = scans.get()
            Files.write(root.resolve("notes.txt"), byteArrayOf(7, 8, 9))
            Thread.sleep(350)
            assertTrue("Unrelated text files should not trigger library scans", scans.get() == afterAudio)

            val albumFolder = Files.createDirectory(root.resolve("album"))
            Files.write(albumFolder.resolve("second.flac"), byteArrayOf(4, 5, 6))
            val before = scans.get()
            assertTrue("New nested folders and files should trigger a library scan", awaitScans(scans, before + 1))

            Files.delete(first)
            assertTrue("Deleting a local file should trigger a library scan", awaitScans(scans, scans.get() + 1))
        } finally {
            watcher.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun awaitScans(scans: AtomicInteger, target: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            if (scans.get() >= target) return true
            Thread.sleep(20)
        }
        return scans.get() >= target
    }
}
