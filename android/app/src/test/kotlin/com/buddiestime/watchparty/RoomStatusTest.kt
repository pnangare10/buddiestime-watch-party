package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStatusTest {
    @Test fun parses_rooms_array() {
        val json = """{"rooms":[
            {"roomId":"S1","active":true,"count":2,"platform":"hotstar","videoUrl":"hotstar.com/x","title":"Match"},
            {"roomId":"S2","active":false,"count":0}
        ]}"""
        val list = parseRoomStatusList(json)
        assertEquals(2, list.size)
        assertEquals("S1", list[0].roomId)
        assertTrue(list[0].active)
        assertEquals(2, list[0].count)
        assertEquals("hotstar", list[0].platform)
        assertFalse(list[1].active)
        assertEquals(null, list[1].platform)
    }
    @Test fun tolerates_empty() {
        assertEquals(0, parseRoomStatusList("""{"rooms":[]}""").size)
        assertEquals(0, parseRoomStatusList("{}").size)
    }

    // GET /api/room/<id> returns the bare object, not a {"rooms":[…]} envelope.
    @Test fun parses_single_room_object() {
        val s = parseRoomStatus(
            """{"roomId":"S1","active":true,"count":2,"platform":"youtube","videoUrl":"youtube.com/watch?v=x","title":null}"""
        )
        assertEquals("S1", s.roomId)
        assertTrue(s.active)
        assertEquals(2, s.count)
        assertEquals("youtube", s.platform)
        assertEquals("youtube.com/watch?v=x", s.videoUrl)
        assertEquals(null, s.title)   // server never populates title
    }

    @Test fun single_room_tolerates_missing_fields() {
        val s = parseRoomStatus("""{"roomId":"S2","active":false,"count":0}""")
        assertFalse(s.active)
        assertEquals(null, s.platform)
        assertEquals(null, s.videoUrl)
    }

    @Test fun live_party_needs_someone_present_and_a_video() {
        // The real thing: someone is connected and watching.
        assertTrue(
            parseRoomStatus("""{"roomId":"S","active":true,"count":1,"videoUrl":"a/b"}""").isLiveParty()
        )
        // Room lingers for ROOM_GRACE_MS after everyone leaves — active but deserted.
        assertFalse(
            parseRoomStatus("""{"roomId":"S","active":true,"count":0,"videoUrl":"a/b"}""").isLiveParty()
        )
        // Connected, but nothing playing yet.
        assertFalse(
            parseRoomStatus("""{"roomId":"S","active":true,"count":2}""").isLiveParty()
        )
        assertFalse(
            parseRoomStatus("""{"roomId":"S","active":false,"count":0}""").isLiveParty()
        )
    }
}
