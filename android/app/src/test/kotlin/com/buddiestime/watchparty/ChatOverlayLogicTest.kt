package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOverlayLogicTest {

    @Test fun firstName_multiWord_returnsFirstToken() {
        assertEquals("Aman", firstNameOf("Aman Kumar"))
    }

    @Test fun firstName_singleWord_returnsItself() {
        assertEquals("Priya", firstNameOf("Priya"))
    }

    @Test fun firstName_extraWhitespace_trimsAndSplits() {
        assertEquals("Sam", firstNameOf("   Sam   Lee  "))
    }

    @Test fun firstName_blank_returnsGuest() {
        assertEquals("Guest", firstNameOf("   "))
        assertEquals("Guest", firstNameOf(""))
    }

    @Test fun firstName_emojiNoSpace_returnsEmoji() {
        assertEquals("🙂", firstNameOf("🙂"))
    }

    @Test fun trimHistory_overCap_dropsOldest() {
        val list = (1..5).map { ChatMessage("u$it", "U$it", "m$it", it.toLong()) }.toMutableList()
        trimHistory(list, 3)
        assertEquals(3, list.size)
        assertEquals("m3", list.first().text)
        assertEquals("m5", list.last().text)
    }

    @Test fun trimHistory_underCap_noChange() {
        val list = (1..2).map { ChatMessage("u$it", "U$it", "m$it", it.toLong()) }.toMutableList()
        trimHistory(list, 3)
        assertEquals(2, list.size)
    }

    @Test fun echo_registeredThenMatched_consumedOnce() {
        val t = OutgoingEchoTracker()
        t.registerLocal("hello", 1_000L)
        assertTrue(t.consumeEcho("hello", 1_200L))
        assertFalse(t.consumeEcho("hello", 1_300L))  // only once
    }

    @Test fun echo_neverRegistered_notConsumed() {
        val t = OutgoingEchoTracker()
        assertFalse(t.consumeEcho("hi", 1_000L))
    }

    @Test fun echo_outsideWindow_expires() {
        val t = OutgoingEchoTracker(windowMs = 5_000L)
        t.registerLocal("late", 1_000L)
        assertFalse(t.consumeEcho("late", 7_000L))  // 6s > 5s window
    }
}
