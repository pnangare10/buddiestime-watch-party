package com.buddiestime.watchparty

import java.net.URI

/**
 * Pure sync decisions, kept out of [MainActivity] so they can be unit-tested without
 * a WebView or a real DRM player.
 *
 * The fast correction loop itself lives in `MainActivity.SYNC_SCRIPT` (it has to —
 * it runs inside the page, next to the <video> element). The projection formula there
 * mirrors [projectTarget]; this object is the tested reference for it.
 */
object SyncPolicy {

    /** Correct the guest once it is more than this far from the projected host position. */
    const val DRIFT_SEC = 1.0

    /**
     * Cap on how far a stale host sample may be extrapolated. The host heartbeats every
     * second, so a gap much larger than this means the host is gone — not that the video
     * genuinely advanced that far. Without the cap, a dropped connection would fling the
     * guest minutes into the future.
     */
    const val MAX_PROJECTION_SEC = 30.0

    /** After a reload, ignore further reload requests this long so we cannot loop. */
    const val RELOAD_COOLDOWN_MS = 15_000L

    /**
     * Query params that change while the same video plays, or that carry no content
     * identity. Comparing raw URLs across these is what made the guest reload the page
     * instead of syncing — `?t=` alone ticks constantly on Hotstar and Prime.
     */
    private val VOLATILE_PARAMS = setOf(
        "t", "start", "time_continue", "autoplay", "auto_play",
        "feature", "si", "pp", "ref", "ref_", "referrer", "src",
    )

    /**
     * Reduces a URL to the part that identifies *which video* it is: host (minus `www.`),
     * path, and any query params that are not known to be volatile, ordered so that
     * param order cannot make identical content look different.
     *
     * Falls back to the trimmed input when the string will not parse, so a garbage URL
     * still compares equal to itself rather than silently matching everything.
     */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val uri = URI(trimmed)
            val host = (uri.host ?: return trimmed).removePrefix("www.").lowercase()
            val path = (uri.path ?: "").trimEnd('/')
            val kept = (uri.rawQuery ?: "")
                .split('&')
                .filter { it.isNotBlank() }
                .filterNot { pair ->
                    val key = pair.substringBefore('=').lowercase()
                    key in VOLATILE_PARAMS || key.startsWith("utm_")
                }
                .sorted()
                .joinToString("&")
            if (kept.isEmpty()) "$host$path" else "$host$path?$kept"
        } catch (e: Exception) {
            trimmed
        }
    }

    /** True when both URLs point at the same video, ignoring playback position and tracking. */
    fun isSameContent(a: String, b: String): Boolean =
        normalizeUrl(a) == normalizeUrl(b)

    /** The only schemes we will hand to WebView.loadUrl. */
    private val NAVIGABLE_SCHEMES = setOf("http", "https")

    /**
     * Whether [raw] is safe to pass to `WebView.loadUrl`.
     *
     * `videoUrl` arrives over the WebSocket from another party member, and the socket
     * is not authenticated — anyone who knows the room id can send one. A `javascript:`
     * URL handed to loadUrl does not navigate: it executes in whatever page is currently
     * loaded, which is the user's signed-in Hotstar/Netflix session, with the HwpBridge
     * interface attached. `file:`/`content:` reach local app storage and `intent:` reaches
     * other apps, so only http(s) is allowed through.
     *
     * Uses [java.net.URI] rather than `android.net.Uri` so this stays a plain JVM unit
     * test with no Robolectric — `android.net.Uri.parse` is stubbed in unit tests and
     * would silently return null here.
     */
    fun isNavigable(raw: String): Boolean = try {
        URI(raw.trim()).scheme?.lowercase() in NAVIGABLE_SCHEMES
    } catch (e: Exception) {
        false
    }

    /**
     * Where the host's video is *now*, given a sample that is [ageMs] old.
     *
     * Applying the host's raw timestamp is the reason guests land behind and stay there:
     * every hop (network, JS bridge, seek buffering) makes the sample older, and a seek
     * to an old timestamp lands old. A paused host is frozen, so its sample never ages.
     */
    fun projectTarget(hostTime: Double, hostPaused: Boolean, ageMs: Long): Double {
        if (hostPaused) return hostTime
        val ageSec = (ageMs.coerceAtLeast(0L)) / 1000.0
        return hostTime + minOf(ageSec, MAX_PROJECTION_SEC)
    }

    /**
     * Whether the guest must navigate to reach the host's content.
     *
     * Previously any string difference triggered a reload, which threw away the sync
     * entirely and re-applied a stale position after the page finished loading. A reload
     * is only warranted for a genuine content change — a different episode or video.
     */
    fun shouldReload(
        hostUrl: String,
        guestUrl: String,
        lastReloadAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (hostUrl.isBlank()) return false
        // Checked before anything else: normalizeUrl() returns a non-http string
        // unchanged (uri.host is null for `javascript:`), so it never matches the
        // guest's URL and every other guard below would wave it through to loadUrl.
        if (!isNavigable(hostUrl)) return false
        if (guestUrl.isNotBlank() && isSameContent(hostUrl, guestUrl)) return false
        if (lastReloadAtMs > 0L && nowMs - lastReloadAtMs < RELOAD_COOLDOWN_MS) return false
        return true
    }
}
