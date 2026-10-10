package app.xan.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChecksumParserTest {
    @Test
    fun parsesNamedSha256SumFiles() {
        val digest = "a".repeat(64)
        assertEquals(digest, ChecksumParser.sha256("$digest *yt-dlp.exe\n", "yt-dlp.exe"))
    }

    @Test
    fun parsesDenoLabeledSha256sumAndBindsItToTheRequestedAsset() {
        val digest = "b".repeat(64)
        val checksum = """
            Algorithm : SHA256
            Hash      : $digest
            Path      : C:\\a\\deno\\target\\release\\deno-x86_64-pc-windows-msvc.zip
        """.trimIndent()
        assertEquals(digest, ChecksumParser.sha256(checksum, "deno-x86_64-pc-windows-msvc.zip"))
        assertThrows(IllegalStateException::class.java) {
            ChecksumParser.sha256(checksum, "another-asset.zip")
        }
    }
}
