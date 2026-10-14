/*
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStorageMigrationTest {
    @Test
    fun `reads the media id token from XAN download names`() {
        val mediaId = "video:artist/東京"
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(mediaId.toByteArray())

        assertEquals(mediaId, DownloadStorageMigration.mediaIdFromFileName("XAN - Track [xan-id-$token].mp4"))
    }

    @Test
    fun `does not treat an ordinary file as an XAN download`() {
        assertNull(DownloadStorageMigration.mediaIdFromFileName("holiday.mp4"))
    }
}
