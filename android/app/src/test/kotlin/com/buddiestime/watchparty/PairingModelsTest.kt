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

    // parseRoomView takes a bare room object, but GET /api/rooms/{id} answers with
    // an envelope. Feeding the envelope straight in threw "No value for theme" on
    // every call, so getRoom always reported parse-error and the home screen never
    // learned whether a partner had joined. Byte-for-byte a real server response.
    @Test fun parses_get_room_envelope() {
        val json = """{"ok":true,"room":{
            "roomId":"ac2db20d0e2bb800bd110862b825e631","roomName":"iiii",
            "ownerDeviceId":"c05c6b9766d2b97b9860afd1e312c6a4",
            "partnerDeviceId":"175e0c0ade322c7e3b83d604d7ba38f2",
            "anniversaryDate":null,
            "theme":{"mode":"auto","value":null,"setByDeviceId":null,"setAt":null},
            "nudgeMessages":[],"welcomeMessages":[],"pendingInvite":null,
            "partnerProfileDraft":{"displayName":"Uuu"},
            "createdAt":1785604893204,"updatedAt":1785611061128
        }}"""
        val room = parseRoomEnvelope(json)
        assertEquals("iiii", room.roomName)
        assertEquals("175e0c0ade322c7e3b83d604d7ba38f2", room.partnerDeviceId)
        assertEquals("auto", room.theme.mode)
    }
}
