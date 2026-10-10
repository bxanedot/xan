package app.xan.desktop

internal object ChecksumParser {
    private val sha256 = Regex("^[a-fA-F0-9]{64}$")

    fun sha256(checksumText: String, assetName: String): String {
        checksumText.lineSequence().firstNotNullOfOrNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val name = parts.lastOrNull()?.removePrefix("*")
            val digest = parts.firstOrNull()
            if (parts.size >= 2 && name == assetName && digest != null && sha256.matches(digest)) digest else null
        }?.let { return it }

        // Deno publishes a labeled digest and a separate Path line in its .sha256sum files.
        val digest = Regex("(?im)^\\s*Hash\\s*:\\s*([a-f0-9]{64})\\s*$")
            .find(checksumText)?.groupValues?.get(1)
        val path = Regex("(?im)^\\s*Path\\s*:\\s*(.+?)\\s*$")
            .find(checksumText)?.groupValues?.get(1)
        if (digest != null && path?.let { it.substringAfterLast('/').substringAfterLast('\\') } == assetName) return digest

        return checksumText.trim().takeIf(sha256::matches)
            ?: error("Could not find a SHA-256 checksum for $assetName.")
    }
}
