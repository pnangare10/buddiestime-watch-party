package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigTest {
    @Test fun baseHttpUrl_swaps_scheme_and_strips_trailing_slash() {
        assertEquals("https://buddiestime-watch-party.onrender.com", Config.baseHttpUrl("wss://buddiestime-watch-party.onrender.com"))
        assertEquals("http://localhost:8080", Config.baseHttpUrl("ws://localhost:8080/"))
    }
    @Test fun healthUrl_swaps_scheme_and_appends_health() {
        assertEquals("https://buddiestime-watch-party.onrender.com/health", Config.healthUrl("wss://buddiestime-watch-party.onrender.com"))
        assertEquals("http://localhost:8080/health", Config.healthUrl("ws://localhost:8080"))
    }
    @Test fun effectiveServerUrl_prefers_nonblank_override() {
        assertEquals("ws://10.0.2.2:8080", Config.effectiveServerUrl("ws://10.0.2.2:8080"))
        assertEquals(Config.SERVER_URL, Config.effectiveServerUrl(null))
        assertEquals(Config.SERVER_URL, Config.effectiveServerUrl("   "))
    }
}
