package com.buddiestime.watchparty

object HotstarService : StreamingService {
    override val name = "hotstar"
    override val displayName = "Hotstar"
    override val url = "https://www.hotstar.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf(
        "X-Requested-With" to "",
        // Ensure Hotstar knows we're in India for regional content
        "Accept-Language" to "en-IN,en;q=0.9"
    )
}

object NetflixService : StreamingService {
    override val name = "netflix"
    override val displayName = "Netflix"
    override val url = "https://www.netflix.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf(
        "X-Requested-With" to "",
        // Netflix uses Accept-Language to determine region availability
        // Set to en-IN to match Indian region (same as your browser)
        "Accept-Language" to "en-IN,en;q=0.9"
    )
}

object PrimeVideoService : StreamingService {
    override val name = "primevideo"
    override val displayName = "Prime Video"
    override val url = "https://www.primevideo.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf(
        "X-Requested-With" to "",
        "Accept-Language" to "en-IN,en;q=0.9"
    )
}

object YouTubeService : StreamingService {
    override val name = "youtube"
    override val displayName = "YouTube"
    override val url = "https://www.youtube.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf(
        "X-Requested-With" to "",
        "Accept-Language" to "en-IN,en;q=0.9"
    )
}

/**
 * The open-web option: no fixed destination, whatever the user types in the Browse
 * address bar. Everything about the sync protocol is URL-driven rather than keyed to a
 * known service (see [SyncPolicy.shouldReload]), so a party works here exactly as it
 * does on the four named services.
 *
 * Unlike them it sends a **mobile** user-agent. Those four need a desktop UA to serve
 * their full web players; an arbitrary site is the opposite case — a desktop layout on
 * a phone is unusable, and mobile pages tend to ship a plain HTML5 <video> rather than
 * a heavyweight DRM player, which is exactly what the sync script can drive.
 */
object BrowseService : StreamingService {
    override val name = "browse"
    override val displayName = "Browse"
    override val url = BrowseUrl.HOME
    override val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    override val headersOverride = mapOf(
        "X-Requested-With" to "",
        "Accept-Language" to "en-IN,en;q=0.9"
    )
}

fun getStreamingService(name: String): StreamingService = when (name.lowercase()) {
    "netflix"    -> NetflixService
    "primevideo" -> PrimeVideoService
    "youtube"    -> YouTubeService
    "browse"     -> BrowseService
    else         -> HotstarService
}

/** Every host the four fixed services are ever served from. */
private val FIXED_SERVICE_HOSTS = setOf(
    "hotstar.com", "jiohotstar.com",
    "netflix.com",
    "primevideo.com", "amazon.com", "amazon.in",
    "youtube.com", "youtu.be", "youtube-nocookie.com",
)

/**
 * Whether [url] is out on the open web rather than on one of the four fixed services.
 *
 * This exists because a room's `platform` is decided **once, forever**: `server.js`
 * writes `roomState.platform` only on the branch that creates the room, and the
 * `state-update` handler never touches it again (it assigns only time/paused/videoUrl).
 * Since a paired couple keeps one long-lived room, a room first created on "hotstar"
 * reports `platform="hotstar"` for the rest of its life — even while the host is
 * browsing an arbitrary site.
 *
 * A guest that decided "am I browsing?" from that string alone therefore stayed in
 * Hotstar mode while being navigated onto an unknown domain: desktop user-agent, no
 * address bar to get back with, and [MainActivity.isPopUnder] disabled — the one guard
 * written for exactly that situation. Asking the *page* instead of the room's stale
 * label is what makes the browse chrome and the pop-under guard follow the user onto
 * the open web regardless of how the room was originally created.
 *
 * Blank or unparseable input returns false so the four fixed services behave exactly
 * as they did before this existed, including at startup when no page has loaded yet.
 */
fun isOpenWebUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val host = java.net.URI(url.trim()).host?.removePrefix("www.")?.lowercase()
            ?: return false
        FIXED_SERVICE_HOSTS.none { host == it || host.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }
}
