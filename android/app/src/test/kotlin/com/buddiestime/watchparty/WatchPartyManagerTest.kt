package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `join` frame's wire shape.
 *
 * The server now refuses a join it cannot tie to a room member (P0-3). Whether the
 * deviceId actually reaches the wire is therefore the difference between a working
 * app and a phone that cannot get into its own room — worth an assertion here rather
 * than a discovery on a locked-out device.
 *
 * Only [WatchPartyManager.buildJoinPayload] is exercised: it is pure and lives on the
 * companion, so no instance is constructed — the constructor calls
 * `Looper.getMainLooper()`, which throws "not mocked" outside an Android runtime.
 */
class WatchPartyManagerTest {

    /**
     * The server stores `videoUrl: null` for a room with no content yet, and **Android's**
     * `JSONObject.optString(k, "")` returns the four-character string "null" for a JSON
     * null rather than the fallback. That was harmless while the value only reached a log
     * line, and actively wrong once the service was derived from it: "null" matches no
     * known host, so it resolved to Browse and overrode the service the user had picked.
     *
     * The quirk is deliberately NOT asserted here. This suite runs against the reference
     * `org.json` (a testImplementation dependency), whose `optString` *does* honour the
     * fallback — so an assertion about Android's behaviour would pass or fail for reasons
     * unrelated to this code. What is asserted is that [WatchPartyManager.optStringOrEmpty]
     * gives the same answer on either implementation, which is the point of it existing:
     * it branches on `isNull` explicitly instead of trusting `optString`.
     */
    @Test
    fun `json null reads as empty`() {
        val msg = org.json.JSONObject("""{"videoUrl":null,"platform":"browse"}""")
        assertEquals("", WatchPartyManager.optStringOrEmpty(msg, "videoUrl"))
        assertEquals("browse", WatchPartyManager.optStringOrEmpty(msg, "platform"))
    }

    @Test
    fun `a missing key still reads as empty`() {
        val msg = org.json.JSONObject("""{"time":1.0}""")
        assertEquals("", WatchPartyManager.optStringOrEmpty(msg, "videoUrl"))
    }

    @Test
    fun `a real url is passed through untouched`() {
        val msg = org.json.JSONObject("""{"videoUrl":"https://example.com/a"}""")
        assertEquals("https://example.com/a", WatchPartyManager.optStringOrEmpty(msg, "videoUrl"))
    }

    private fun params(deviceId: String?) = WatchPartyManager.JoinParams(
        serverUrl = "wss://example.test",
        roomId = "3c2506f61980e9b289948276c40c3496",
        platform = "hotstar",
        videoUrl = "https://www.hotstar.com/in/shows/x/1260/watch",
        displayName = "Ann",
        clientId = "android-1234",
        deviceId = deviceId,
    )

    @Test fun join_frame_carries_the_device_id() {
        val json = WatchPartyManager.buildJoinPayload(params("6d114dc73e0a08707043e33a4206ee1f"))
        assertEquals("join", json.optString("type"))
        assertEquals("6d114dc73e0a08707043e33a4206ee1f", json.optString("deviceId"))
    }

    @Test fun join_frame_omits_the_key_entirely_when_there_is_no_device_id() {
        // Not the string "null": the server distinguishes an absent identity from an
        // unparseable one, and a literal "null" would be the latter.
        for (missing in listOf(null, "", "   ")) {
            val json = WatchPartyManager.buildJoinPayload(params(missing))
            assertFalse(
                "deviceId must be absent, not empty or \"null\", for input <$missing>",
                json.has("deviceId"),
            )
        }
    }

    @Test fun join_frame_still_carries_everything_the_server_already_relied_on() {
        // Guard against the deviceId addition quietly dropping a field: displayName is
        // required (the server closes the socket without it) and roomId/clientId drive
        // room membership and reconnect identity.
        val json = WatchPartyManager.buildJoinPayload(params("dev-1"))
        assertEquals("3c2506f61980e9b289948276c40c3496", json.optString("roomId"))
        assertEquals("android-1234", json.optString("clientId"))
        assertEquals("Ann", json.optString("displayName"))
        assertEquals("hotstar", json.optString("platform"))
        assertEquals(
            "https://www.hotstar.com/in/shows/x/1260/watch",
            json.optString("videoUrl"),
        )
    }

    @Test fun device_id_is_not_the_client_id() {
        // clientId is generated locally and never verified; deviceId is the credential.
        // Conflating them would look like it works and authenticate nothing.
        val json = WatchPartyManager.buildJoinPayload(params("6d114dc73e0a08707043e33a4206ee1f"))
        assertTrue(json.optString("clientId").startsWith("android-"))
        assertFalse(json.optString("deviceId") == json.optString("clientId"))
    }
}
