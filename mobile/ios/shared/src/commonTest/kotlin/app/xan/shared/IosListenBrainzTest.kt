package app.xan.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosListenBrainzTest {
    @Test
    fun tokenValidationParsesUserAndRejectsInvalidTokens() {
        assertEquals("bxane", parseIosListenBrainzUsername(Json.parseToJsonElement("""{"valid":true,"user_name":"bxane"}""").jsonObject))
        assertEquals("ListenBrainz user", parseIosListenBrainzUsername(Json.parseToJsonElement("""{"valid":true}""").jsonObject))
        assertFailsWith<IllegalStateException> {
            parseIosListenBrainzUsername(Json.parseToJsonElement("""{"valid":false,"user_name":"bxane"}""").jsonObject)
        }
    }

    @Test
    fun listenThresholdUsesHalfTrackOrFourMinutes() {
        assertNull(iosListenBrainzThresholdMillis(0))
        assertEquals(15_000L, iosListenBrainzThresholdMillis(30_000))
        assertEquals(100_000L, iosListenBrainzThresholdMillis(200_000))
        assertEquals(240_000L, iosListenBrainzThresholdMillis(900_000))
    }

    @Test
    fun payloadEscapesMetadataAndOmitsTimestampForPlayingNow() {
        val track = IosListenBrainzTrack("video-id", "A \"Song\"", "Artist\\Name", "Album", 240_000)
        val playing = Json.parseToJsonElement(iosListenBrainzPayload("playing_now", track, null, 42_000)).jsonObject
        assertEquals("playing_now", playing["listen_type"]?.jsonPrimitive?.content)
        val playingEntry = playing["payload"]?.jsonArray?.single()?.jsonObject
        assertFalse("listened_at" in playingEntry.orEmpty())
        assertEquals("A \"Song\"", playingEntry?.get("track_metadata")?.jsonObject?.get("track_name")?.jsonPrimitive?.content)
        assertEquals(42_000, playingEntry?.get("track_metadata")?.jsonObject?.get("additional_info")?.jsonObject?.get("position_ms")?.jsonPrimitive?.content?.toLong())

        val single = Json.parseToJsonElement(iosListenBrainzPayload("single", track, 1_800_000_000, 125_000)).jsonObject
        val singleEntry = single["payload"]?.jsonArray?.single()?.jsonObject
        assertEquals(1_800_000_000, singleEntry?.get("listened_at")?.jsonPrimitive?.content?.toLong())
        assertEquals("Album", singleEntry?.get("track_metadata")?.jsonObject?.get("release_name")?.jsonPrimitive?.content)
        assertFalse("position_ms" in singleEntry?.get("track_metadata")?.jsonObject?.get("additional_info")?.jsonObject.orEmpty())
    }
}
