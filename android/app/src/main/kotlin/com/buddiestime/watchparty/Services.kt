package com.buddiestime.watchparty

object HotstarService : StreamingService {
    override val name = "hotstar"
    override val displayName = "Hotstar"
    override val url = "https://www.hotstar.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}

object NetflixService : StreamingService {
    override val name = "netflix"
    override val displayName = "Netflix"
    override val url = "https://www.netflix.com"
    override val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
    override val headersOverride = mapOf("X-Requested-With" to "")
}

fun getStreamingService(name: String): StreamingService = when (name.lowercase()) {
    "netflix" -> NetflixService
    "hotstar" -> HotstarService
    else -> HotstarService  // default fallback
}
