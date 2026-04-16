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

fun getStreamingService(name: String): StreamingService = when (name.lowercase()) {
    "netflix" -> NetflixService
    "hotstar" -> HotstarService
    else -> HotstarService  // default fallback
}
