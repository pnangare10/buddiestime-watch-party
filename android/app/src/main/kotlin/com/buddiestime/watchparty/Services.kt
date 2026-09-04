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

/** Which hosts belong to which of the four fixed services. */
private val SERVICE_HOSTS: List<Pair<StreamingService, Set<String>>> = listOf(
    HotstarService    to setOf("hotstar.com", "jiohotstar.com"),
    NetflixService    to setOf("netflix.com"),
    PrimeVideoService to setOf("primevideo.com", "amazon.com", "amazon.in"),
    YouTubeService    to setOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
)

/**
 * Sign-in and consent hosts a fixed service legitimately sends the main frame to.
 *
 * These are neither a service nor "the open web". YouTube is the case that matters:
 * signing in navigates the main frame to accounts.google.com, and without this the
 * address bar would appear over what the user experiences as signing into YouTube, the
 * pop-under guard would arm on an identity flow it might then swallow a hop of, and
 * that login URL would be written to KEY_LAST_BROWSE_URL as "where Browse was".
 *
 * Deliberately specific hosts rather than all of google.com: google.com is also a place
 * someone might genuinely browse to, and that should still count as the open web.
 */
private val NEUTRAL_HOSTS = setOf(
    "accounts.google.com",
    "consent.google.com",
    "accounts.googleapis.com",
    "signin.aws.amazon.com",
)

/** Registrable host of [url], `www.` stripped, or null when it will not parse. */
private fun hostOf(url: String): String? = try {
    java.net.URI(url.trim()).host?.removePrefix("www.")?.lowercase()
} catch (e: Exception) {
    null
}

/**
 * Which fixed service serves [url], or null when it is out on the open web.
 *
 * Matching is `host == d || host.endsWith(".$d")` so a subdomain (m.youtube.com,
 * consent.youtube.com) counts while a lookalike does not: "nothotstar.com" fails the
 * equality test and does not end in ".hotstar.com", and "hotstar.com.evil.tld" ends in
 * ".evil.tld" rather than ".hotstar.com".
 */
fun serviceForUrl(url: String): StreamingService? {
    val host = hostOf(url) ?: return null
    return SERVICE_HOSTS.firstOrNull { (_, hosts) ->
        hosts.any { host == it || host.endsWith(".$it") }
    }?.first
}

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
    val host = hostOf(url) ?: return false
    if (NEUTRAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
    return serviceForUrl(url) == null
}
