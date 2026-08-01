package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    // ── normalizeUrl / isSameContent ──────────────────────────────────────────
    // These are the RC-3 cases: the host sends its live location.href while the
    // guest sits on the same content under a slightly different URL string.

    @Test fun normalize_strips_scheme_www_and_trailing_slash() {
        assertEquals(
            SyncPolicy.normalizeUrl("https://www.hotstar.com/in/shows/x/1260/watch/"),
            SyncPolicy.normalizeUrl("http://hotstar.com/in/shows/x/1260/watch"),
        )
    }

    @Test fun normalize_drops_playback_position_param() {
        // Hotstar/Prime append a resume offset that ticks as the video plays.
        assertTrue(
            SyncPolicy.isSameContent(
                "https://www.hotstar.com/in/shows/x/1260/watch?t=920",
                "https://www.hotstar.com/in/shows/x/1260/watch?t=43",
            )
        )
    }

    @Test fun normalize_drops_autoplay_and_tracking_params() {
        assertTrue(
            SyncPolicy.isSameContent(
                "https://www.primevideo.com/detail/0RQH24E6H57QDQTSR31T4IUC20/ref=atv_unknown?autoplay=1&t=0",
                "https://www.primevideo.com/detail/0RQH24E6H57QDQTSR31T4IUC20/ref=atv_unknown",
            )
        )
        assertTrue(
            SyncPolicy.isSameContent(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw&utm_source=share&si=abc",
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            )
        )
    }

    @Test fun normalize_keeps_content_identifying_params() {
        // YouTube's v= IS the content id — stripping it would fuse different videos.
        assertFalse(
            SyncPolicy.isSameContent(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            )
        )
    }

    @Test fun normalize_is_order_insensitive_for_kept_params() {
        assertTrue(
            SyncPolicy.isSameContent(
                "https://www.youtube.com/watch?v=abc&list=PL1",
                "https://www.youtube.com/watch?list=PL1&v=abc",
            )
        )
    }

    @Test fun different_episodes_are_not_same_content() {
        assertFalse(
            SyncPolicy.isSameContent(
                "https://www.hotstar.com/in/shows/x/1260/watch",
                "https://www.hotstar.com/in/shows/x/1261/watch",
            )
        )
    }

    @Test fun malformed_urls_fall_back_to_literal_comparison() {
        assertTrue(SyncPolicy.isSameContent("not a url", "not a url"))
        assertFalse(SyncPolicy.isSameContent("not a url", "other junk"))
    }

    // ── projectTarget ─────────────────────────────────────────────────────────
    // The host's timestamp is always stale by the time we apply it. RC-1.

    @Test fun projects_forward_while_host_is_playing() {
        assertEquals(102.5, SyncPolicy.projectTarget(100.0, hostPaused = false, ageMs = 2500), 1e-6)
    }

    @Test fun does_not_project_while_host_is_paused() {
        assertEquals(100.0, SyncPolicy.projectTarget(100.0, hostPaused = true, ageMs = 8000), 1e-6)
    }

    @Test fun negative_age_never_rewinds_the_target() {
        assertEquals(100.0, SyncPolicy.projectTarget(100.0, hostPaused = false, ageMs = -500), 1e-6)
    }

    @Test fun projection_is_capped_so_a_dead_link_cannot_run_away() {
        // Heartbeat is 1s; a 10-minute gap means the host is gone, not 600s ahead.
        val t = SyncPolicy.projectTarget(100.0, hostPaused = false, ageMs = 600_000)
        assertEquals(100.0 + SyncPolicy.MAX_PROJECTION_SEC, t, 1e-6)
    }

    // ── shouldReload ──────────────────────────────────────────────────────────
    // The bug: any URL mismatch reloaded the page instead of syncing.

    @Test fun does_not_reload_when_already_on_the_same_content() {
        assertFalse(
            SyncPolicy.shouldReload(
                hostUrl = "https://www.hotstar.com/in/shows/x/1260/watch?t=920",
                guestUrl = "https://www.hotstar.com/in/shows/x/1260/watch",
                lastReloadAtMs = 0L,
                nowMs = 1_000_000L,
            )
        )
    }

    @Test fun reloads_when_host_switched_to_different_content() {
        assertTrue(
            SyncPolicy.shouldReload(
                hostUrl = "https://www.hotstar.com/in/shows/x/1261/watch",
                guestUrl = "https://www.hotstar.com/in/shows/x/1260/watch",
                lastReloadAtMs = 0L,
                nowMs = 1_000_000L,
            )
        )
    }

    @Test fun cooldown_prevents_a_reload_loop() {
        val now = 1_000_000L
        assertFalse(
            SyncPolicy.shouldReload(
                hostUrl = "https://www.hotstar.com/in/shows/x/1261/watch",
                guestUrl = "https://www.hotstar.com/in/shows/x/1260/watch",
                lastReloadAtMs = now - 1_000L,
                nowMs = now,
            )
        )
        assertTrue(
            SyncPolicy.shouldReload(
                hostUrl = "https://www.hotstar.com/in/shows/x/1261/watch",
                guestUrl = "https://www.hotstar.com/in/shows/x/1260/watch",
                lastReloadAtMs = now - SyncPolicy.RELOAD_COOLDOWN_MS - 1L,
                nowMs = now,
            )
        )
    }

    @Test fun blank_host_url_never_triggers_a_reload() {
        assertFalse(SyncPolicy.shouldReload("", "https://www.hotstar.com/x", 0L, 1_000_000L))
        assertFalse(SyncPolicy.shouldReload("   ", "https://www.hotstar.com/x", 0L, 1_000_000L))
    }

    @Test fun unknown_guest_url_reloads_so_a_fresh_guest_still_lands_on_the_video() {
        assertTrue(SyncPolicy.shouldReload("https://www.hotstar.com/x", "", 0L, 1_000_000L))
    }
}
