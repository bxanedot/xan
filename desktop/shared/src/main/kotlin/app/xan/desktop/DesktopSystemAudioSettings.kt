package app.xan.desktop

import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal object DesktopSystemAudioSettings {
    fun open(): String {
        val os = System.getProperty("os.name").lowercase()
        return runCatching {
            when {
                os.contains("win") -> {
                    val settingsOpened = runCatching {
                        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                        Desktop.getDesktop().browse(URI("ms-settings:sound"))
                    }.isSuccess
                    if (!settingsOpened) ProcessBuilder("control.exe", "/name", "Microsoft.Sound").start()
                }
                os.contains("mac") -> ProcessBuilder("open", "x-apple.systempreferences:com.apple.Sound-Settings.extension").start()
                else -> {
                    val choices = listOf(
                        listOf("pavucontrol"),
                        listOf("gnome-control-center", "sound"),
                        listOf("systemsettings", "kcm_pulseaudio"),
                    )
                    val selected = choices.firstOrNull { commandAvailable(it.first()) }
                        ?: error("No supported system sound settings app was found.")
                    ProcessBuilder(selected).start()
                }
            }
            "Opened system sound settings. XAN follows the selected system output."
        }.getOrElse { failure -> failure.message ?: "Could not open the system sound settings." }
    }

    private fun commandAvailable(command: String): Boolean = System.getenv("PATH").orEmpty()
        .split(java.io.File.pathSeparator)
        .asSequence()
        .map { Path.of(it.ifBlank { "." }).resolve(command) }
        .any { Files.isRegularFile(it) && Files.isExecutable(it) }
}
