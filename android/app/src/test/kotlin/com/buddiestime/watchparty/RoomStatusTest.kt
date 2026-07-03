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
}
