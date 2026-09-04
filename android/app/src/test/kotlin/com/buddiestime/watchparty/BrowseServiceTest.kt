package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Service resolution and the browse-specific reload cooldown.
 *
 * `getStreamingService` ends in `else -> HotstarService`, so a missing branch is not a
 * compile error and not a crash — it silently resolves the open-web service to Hotstar,
 * which is exactly the kind of thing that only shows up on a phone.
 */
class BrowseServiceTest {

    @Test
    fun `browse resolves to BrowseService`() {
        assertSame(BrowseService, getStreamingService("browse"))
    }

    @Test
    fun `service lookup is case-insensitive`() {
        assertSame(BrowseService, getStreamingService("BROWSE"))
        assertSame(BrowseService, getStreamingService("Browse"))
    }

    @Test
    fun `unknown platform still falls back to hotstar`() {
        assertSame(HotstarService, getStreamingService("nonesuch"))
        assertSame(HotstarService, getStreamingService(""))
    }

    @Test
    fun `the four existing services are untouched`() {
        assertSame(NetflixService, getStreamingService("netflix"))
        assertSame(PrimeVideoService, getStreamingService("primevideo"))
        assertSame(YouTubeService, getStreamingService("youtube"))
        assertSame(HotstarService, getStreamingService("hotstar"))
    }

    @Test
    fun `browse name matches what the wire and prefs use`() {
        // MainActivity.isBrowseMode, SyncPolicy.reloadCooldownFor and the join frame's
        // `platform` field all key off this string.
        assertEquals("browse", BrowseService.name)
    }

    @Test
    fun `browse sends a mobile user agent unlike the fixed services`() {
        assertTrue(BrowseService.userAgent.contains("Mobile"))
        assertNotEquals(HotstarService.userAgent, BrowseService.userAgent)
    }

    @Test
    fun `browse home is an https url`() {
        assertTrue(BrowseService.url.startsWith("https://"))
        assertTrue(SyncPolicy.isNavigable(BrowseService.url))
    }

    // ── reload cooldown ──────────────────────────────────────────────────────

    @Test
    fun `browse gets the short reload cooldown`() {
        assertEquals(SyncPolicy.BROWSE_RELOAD_COOLDOWN_MS, SyncPolicy.reloadCooldownFor("browse"))
    }

    @Test
    fun `fixed services keep the long reload cooldown`() {
        for (p in listOf("hotstar", "netflix", "primevideo", "youtube", null)) {
            assertEquals("cooldown changed for $p", SyncPolicy.RELOAD_COOLDOWN_MS, SyncPolicy.reloadCooldownFor(p))
        }
    }

    @Test
    fun `a second hop within 3s is still suppressed in browse mode`() {
        val now = 100_000L
        assertTrue(
            "no prior reload → must navigate",
            SyncPolicy.shouldReload("https://a.com/1", "https://b.com/1", 0L, now, SyncPolicy.BROWSE_RELOAD_COOLDOWN_MS),
        )
        assertTrue(
            "1s after a reload → still inside the loop guard",
            !SyncPolicy.shouldReload("https://a.com/1", "https://b.com/1", now - 1_000L, now, SyncPolicy.BROWSE_RELOAD_COOLDOWN_MS),
        )
    }

    @Test
    fun `browse follows a host hop that the 15s cooldown would have dropped`() {
        // The regression this cooldown exists for: host moves page A → page B four
        // seconds later. Under the fixed-service cooldown the guest ignores it and sits
        // on A; under the browse cooldown it follows.
        val now = 100_000L
        val lastReload = now - 4_000L
        assertTrue(
            !SyncPolicy.shouldReload("https://a.com/2", "https://a.com/1", lastReload, now, SyncPolicy.RELOAD_COOLDOWN_MS),
        )
        assertTrue(
            SyncPolicy.shouldReload("https://a.com/2", "https://a.com/1", lastReload, now, SyncPolicy.BROWSE_RELOAD_COOLDOWN_MS),
        )
    }

    @Test
    fun `the short cooldown does not weaken the javascript url guard`() {
        assertTrue(
            !SyncPolicy.shouldReload("javascript:alert(1)", "https://a.com", 0L, 100_000L, SyncPolicy.BROWSE_RELOAD_COOLDOWN_MS),
        )
    }

    // ── isOpenWebUrl ─────────────────────────────────────────────────────────
    //
    // The room's `platform` is frozen when the room is created (server.js writes
    // roomState.platform only on the create branch; state-update never updates it), so
    // an established "hotstar" room reports platform="hotstar" forever — even while the
    // host browses the open web. These cases are what lets a guest notice that anyway.

    @Test
    fun `the four fixed services are not open web`() {
        for (u in listOf(
            "https://www.hotstar.com/in/movies/x",
            "https://hotstar.com/",
            "https://www.jiohotstar.com/in/x",
            "https://www.netflix.com/watch/123",
            "https://www.primevideo.com/detail/x",
            "https://www.amazon.in/gp/video/detail/x",
            "https://www.amazon.com/gp/video/detail/x",
            "https://www.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://m.youtube.com/watch?v=abc",
        )) {
            assertFalse("$u should not count as open web", isOpenWebUrl(u))
        }
    }

    @Test
    fun `an arbitrary site is open web`() {
        assertTrue(isOpenWebUrl("https://example.com/watch/1"))
        assertTrue(isOpenWebUrl("http://10.0.2.2:8099/"))
        assertTrue(isOpenWebUrl("https://duckduckgo.com/?q=x"))
    }

    @Test
    fun `blank or unparseable input is not open web`() {
        // Must be false, not true: this is read at startup before any page has loaded,
        // and a true here would put every fixed service into browse mode on launch.
        assertFalse(isOpenWebUrl(""))
        assertFalse(isOpenWebUrl("   "))
        assertFalse(isOpenWebUrl("not a url"))
        assertFalse(isOpenWebUrl("javascript:alert(1)"))
    }

    @Test
    fun `a lookalike domain does not pass as a fixed service`() {
        // endsWith(".hotstar.com") must not be satisfied by a suffix match on the bare
        // string — "nothotstar.com" and "hotstar.com.evil.tld" are not Hotstar.
        assertTrue(isOpenWebUrl("https://nothotstar.com/x"))
        assertTrue(isOpenWebUrl("https://hotstar.com.evil.tld/x"))
        assertTrue(isOpenWebUrl("https://fakenetflix.com/"))
    }
}
