package com.buddiestime.watchparty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun simple_range() {
        val q = QuietHours(23, 7)
        assertTrue(q.isQuietAt(0))
        assertTrue(q.isQuietAt(23))
        assertTrue(q.isQuietAt(6))
        assertFalse(q.isQuietAt(7))
        assertFalse(q.isQuietAt(12))
    }
    @Test fun non_wrapping_range() {
        val q = QuietHours(1, 5)
        assertTrue(q.isQuietAt(2))
        assertFalse(q.isQuietAt(0))
        assertFalse(q.isQuietAt(5))
    }
}
