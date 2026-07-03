package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffPolicyTest {
    private val p = BackoffPolicy(baseMs = 1000, maxMs = 15000)
    @Test fun doubles_from_base() {
        assertEquals(1000, p.delayFor(1))
        assertEquals(2000, p.delayFor(2))
        assertEquals(4000, p.delayFor(3))
        assertEquals(8000, p.delayFor(4))
    }
    @Test fun caps_at_max() {
        assertEquals(15000, p.delayFor(5))
        assertEquals(15000, p.delayFor(50))
    }
    @Test fun guards_low_attempts() {
        assertEquals(1000, p.delayFor(0))
        assertEquals(1000, p.delayFor(-3))
    }
}
