package app.xan.shared

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosMusicAccountTest {
    @Test
    fun sapisidHashMatchesSha1AndRequiresAUsableCookie() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", iosSha1Hex("abc"))
        val value = "1700000000 secret https://music.youtube.com"
        assertEquals(
            "SAPISIDHASH 1700000000_${iosSha1Hex(value)}",
            iosSapisidAuthorization("HSID=other; SAPISID=secret", "https://music.youtube.com", 1_700_000_000L),
        )
        assertNull(iosSapisidAuthorization("HSID=other", "https://music.youtube.com", 1_700_000_000L))
        assertNull(iosSapisidAuthorization("SAPISID=", "https://music.youtube.com", 1_700_000_000L))
    }

    @Test
    fun accountContextNormalizesChannelIdentity() {
        val context = iosYouTubeContext(dataSyncId = "channel123||account456")
        assertEquals("channel123", context["user"]!!.jsonObject["onBehalfOfUser"]!!.toString().trim('"'))
        assertEquals("channel123", normalizeIosDataSyncId("channel123||account456"))
        assertNull(normalizeIosDataSyncId("null"))
    }

    @Test
    fun parsesActiveProfileAndChannelSwitchIdentities() {
        val profileJson = """
            {
              "actions": [{
                "openPopupAction": {"popup": {"multiPageMenuRenderer": {"header": {
                  "activeAccountHeaderRenderer": {
                    "accountName": {"runs": [{"text": "Xan"}]},
                    "email": {"runs": [{"text": "xan@example.test"}]},
                    "channelHandle": {"runs": [{"text": "@xan"}]},
                    "accountPhoto": {"thumbnails": [{"url": "https://yt3.ggpht.com/avatar"}]}
                  }
                }}}}
              }]
            }
        """.trimIndent()
        val profile = parseIosMusicAccountProfile(profileJson)
        assertEquals("Xan", profile.name)
        assertEquals("xan@example.test", profile.email)
        assertEquals("@xan", profile.channelHandle)
        assertEquals("https://yt3.ggpht.com/avatar", profile.artworkUrl)

        val channelsJson = """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [{
                  "accountSectionListRenderer": {
                    "contents": [{
                      "accountItemSectionRenderer": {
                        "contents": [
                          {"accountItem": {
                            "accountName": {"simpleText": "Personal"},
                            "channelHandle": {"simpleText": "@personal"},
                            "accountPhoto": {"thumbnails": [{"url": "https://yt3.ggpht.com/personal"}]},
                            "serviceEndpoint": {"selectActiveIdentityEndpoint": {"supportedTokens": [
                              {"datasyncIdToken": {"datasyncIdToken": "personal123||account"}}
                            ]}}
                          }},
                          {"accountItem": {
                            "accountName": {"simpleText": "Brand channel"},
                            "serviceEndpoint": {"selectActiveIdentityEndpoint": {"supportedTokens": [
                              {"datasyncIdToken": {"datasyncIdToken": "brand456||account"}}
                            ]}}
                          }}
                        ]
                      }
                    }]
                  }
                }]
              }
            }
        """.trimIndent()
        val channels = parseIosMusicAccountChannels(")]}'\n" + channelsJson)
        assertEquals(listOf("personal123", "brand456"), channels.map { it.dataSyncId })
        assertEquals("@personal", channels.first().channelHandle)
    }
}
