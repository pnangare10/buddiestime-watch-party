package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingModelsTest {
    @Test fun parses_room_view() {
        val json = """{
            "roomId":"r1","roomName":"SonuKomal","ownerDeviceId":"d1","partnerDeviceId":"d2",
            "theme":{"mode":"auto","value":null,"setByDeviceId":null,"setAt":null},
            "nudgeMessages":[{"id":"m1","text":"come watch 😏","authorDeviceId":"d1","createdAt":100}],
            "welcomeMessages":[]
        }"""
        val room = parseRoomView(json)
        assertEquals("SonuKomal", room.roomName)
        assertEquals(1, room.nudgeMessages.size)
        assertEquals("come watch 😏", room.nudgeMessages[0].text)
        assertEquals("auto", room.theme.mode)
    }
}
