package app.xan.music.listentogether

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCodeTest {
    @Test fun `a copied code joins unchanged despite spaces or lowercase`() {
        assertEquals("QPNB4JUA", normalizeRoomCode(" qpnB-4jua\n"))
        assertTrue(isValidRoomCode(normalizeRoomCode(" qpnB-4jua\n")))
    }

    @Test fun `normalization is independent of the device language`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("IABC1234", normalizeRoomCode("iabc1234"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `room codes reject incomplete oversized and non ascii inputs`() {
        listOf("", "ABC", "A".repeat(13), "ABÇ12345", "AB C1234").forEach { assertFalse(isValidRoomCode(it)) }
        assertEquals("ABC1234", normalizeRoomCode("ABC🔊ß1234"))
        assertTrue(isValidRoomCode("ABCD"))
        assertTrue(isValidRoomCode("A".repeat(12)))
    }
}
