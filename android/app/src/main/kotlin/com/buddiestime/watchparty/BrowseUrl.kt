package com.buddiestime.watchparty

import java.net.URI
import java.net.URLEncoder

/**
 * Turns whatever the user typed in the Browse address bar into something safe to hand
 * to `WebView.loadUrl`.
 *
 * This is security-relevant, not just convenience. The address bar is the first
 * *untrusted* input this app ever gives to `loadUrl`, and the WebView it navigates has
 * `addJavascriptInterface(HwpBridge)` attached plus the user's logged-in cookies for
 * every service they have used. A `javascript:` URL passed to `loadUrl` does not
 * navigate — it executes inside the page that is currently loaded. So a typed (or
 * pasted, or shared-in) `javascript:` string would run against whatever session is
 * open. Same hazard [SyncPolicy.isNavigable] guards on the wire; this is the same
 * guard on the keyboard.
 *
 * The response to a non-http(s) scheme is deliberately to *search* for it rather than
 * to reject it. Silently doing nothing looks like a broken button, and there is no
 * legitimate reason to want `file:///` in a watch-party browser anyway.
 *
 * Uses [java.net.URI] rather than `android.net.Uri` so this stays a plain JVM unit
 * test with no Robolectric — `android.net.Uri.parse` is stubbed ("not mocked") in
 * local unit tests and would silently return null. Same reason [SyncPolicy] does.
 */
object BrowseUrl {

    /** Where Browse starts when there is no remembered page, and the search backend. */
    const val HOME = "https://duckduckgo.com/"

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Host-shaped input: letters/digits/dots/dashes, an optional :port, an optional
     * path. Deliberately strict — anything with a space or a quote falls through to
     * search rather than being guessed at as a hostname.
     */
    private val HOST_LIKE = Regex("^[A-Za-z0-9._~-]+(:[0-9]{1,5})?(/.*)?$")

    /**
     * @return an http(s) URL safe for `loadUrl`, or null when [input] is blank.
     */
    fun resolve(input: String): String? {
        // Strip control characters throughout, not merely at the ends. WebView tolerates
        // "java\tscript:alert(1)" and "java\nscript:..." as `javascript:`, whereas a plain
        // trim() leaves the embedded character in place, schemeOf() then fails to spot the
        // scheme, and the payload is waved straight through to loadUrl as a "search".
        val trimmed = input.filterNot { it.code < 0x20 || it.code == 0x7F }.trim()
        if (trimmed.isEmpty()) return null

        val scheme = schemeOf(trimmed)
        if (scheme != null) {
            // An explicit scheme is honoured only when we allow it. Everything else —
            // javascript:, file:, content:, intent:, data:, about: — becomes a search.
            return if (scheme in ALLOWED_SCHEMES) trimmed else search(trimmed)
        }

        // No scheme. "example.com/watch?v=1" is a URL; "best movies 2026" is a query.
        // Requiring a dot is what separates them: a single bare word is a search.
        if (!trimmed.contains(' ') && trimmed.contains('.') && HOST_LIKE.matches(trimmed)) {
            return "https://$trimmed"
        }
        return search(trimmed)
    }

    /**
     * The scheme of [raw], lowercased, or null when it carries none.
     *
     * Hand-parsed rather than delegating to `URI(raw).scheme`, because URI accepts
     * strings WebView rejects and vice versa; the only question that matters here is
     * "is there a `scheme:` prefix, and what is it". A colon inside a path, or one that
     * separates a host from a port, is not a scheme.
     */
    private fun schemeOf(raw: String): String? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null

        // A colon that appears after a slash belongs to a path, not a scheme.
        val slash = raw.indexOf('/')
        if (slash in 0 until colon) return null

        // A scheme is ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) per RFC 3986.
        val candidate = raw.substring(0, colon)
        if (!candidate[0].isLetter()) return null
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null

        // "localhost:8080" is host:port, not a scheme. An all-digit run after the colon
        // means a port, so this is a bare authority we should still prefix with https://.
        val afterColon = raw.substring(colon + 1).takeWhile { it != '/' }
        if (afterColon.isNotEmpty() && afterColon.all { it.isDigit() }) return null

        return candidate.lowercase()
    }

    private fun search(query: String): String =
        HOME + "?q=" + URLEncoder.encode(query, "UTF-8")

    /**
     * Whether [url] is worth storing as "where Browse was", so re-entering Browse
     * resumes on real content rather than on a stale search-results page.
     */
    fun isWorthRemembering(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.startsWith(HOME)) return false
        return try {
            !URI(url).host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
