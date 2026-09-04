package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The address bar is the app's only *untrusted* input into `WebView.loadUrl`, in a
 * WebView carrying the user's logged-in cookies and the HwpBridge JS interface. The
 * scheme cases below are the security surface; the rest are ordinary UX.
 */
class BrowseUrlTest {

    // ── blank ────────────────────────────────────────────────────────────────

    @Test
    fun `blank input resolves to null`() {
        assertNull(BrowseUrl.resolve(""))
        assertNull(BrowseUrl.resolve("   "))
        assertNull(BrowseUrl.resolve("\t\n "))
    }

    // ── http(s) passes through ───────────────────────────────────────────────

    @Test
    fun `https url is returned unchanged`() {
        assertEquals("https://example.com/watch", BrowseUrl.resolve("https://example.com/watch"))
    }

    @Test
    fun `http url is returned unchanged`() {
        assertEquals("http://10.0.2.2:8080/clip.html", BrowseUrl.resolve("http://10.0.2.2:8080/clip.html"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertEquals("HTTPS://example.com", BrowseUrl.resolve("HTTPS://example.com"))
        assertEquals("HtTp://example.com", BrowseUrl.resolve("HtTp://example.com"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.com", BrowseUrl.resolve("  https://example.com  "))
    }

    // ── dangerous schemes become searches, never navigations ─────────────────

    @Test
    fun `javascript url never survives as a url`() {
        val out = BrowseUrl.resolve("javascript:alert(document.cookie)")!!
        assertTrue("expected a search, got $out", out.startsWith(BrowseUrl.HOME + "?q="))
        assertFalse(out.startsWith("javascript:"))
    }

    @Test
    fun `javascript url is caught regardless of case`() {
        val out = BrowseUrl.resolve("JaVaScRiPt:alert(1)")!!
        assertTrue(out.startsWith(BrowseUrl.HOME + "?q="))
    }

    @Test
    fun `embedded control characters cannot smuggle a javascript scheme`() {
        // WebView tolerates "java\tscript:" as javascript:; a trim()-only guard would
        // fail to recognise the scheme and wave the payload through.
        for (raw in listOf("java\tscript:alert(1)", "java\nscript:alert(1)", "java script:alert(1)")) {
            val out = BrowseUrl.resolve(raw)!!
            assertTrue("leaked for $raw → $out", out.startsWith(BrowseUrl.HOME + "?q="))
        }
    }

    @Test
    fun `file content intent and data schemes become searches`() {
        for (raw in listOf(
            "file:///etc/passwd",
            "content://com.android.providers/x",
            "intent://scan#Intent;scheme=zxing;end",
            "data:text/html,<script>alert(1)</script>",
            "about:blank",
        )) {
            val out = BrowseUrl.resolve(raw)!!
            assertTrue("$raw should have become a search but was $out", out.startsWith(BrowseUrl.HOME + "?q="))
        }
    }

    // ── bare hosts get a scheme ──────────────────────────────────────────────

    @Test
    fun `bare host gets https`() {
        assertEquals("https://example.com", BrowseUrl.resolve("example.com"))
    }

    @Test
    fun `bare host with path gets https`() {
        assertEquals("https://example.com/a/b?c=d", BrowseUrl.resolve("example.com/a/b?c=d"))
    }

    @Test
    fun `host with port gets https`() {
        assertEquals("https://10.0.2.2:8080", BrowseUrl.resolve("10.0.2.2:8080"))
    }

    @Test
    fun `a colon inside a path is not mistaken for a scheme`() {
        assertEquals("https://site.com/a?u=http://x", BrowseUrl.resolve("site.com/a?u=http://x"))
    }

    // ── everything else searches ─────────────────────────────────────────────

    @Test
    fun `phrase with spaces becomes a search`() {
        val out = BrowseUrl.resolve("best movies 2026")!!
        assertEquals(BrowseUrl.HOME + "?q=best+movies+2026", out)
    }

    @Test
    fun `single bare word becomes a search not a hostname`() {
        val out = BrowseUrl.resolve("cinema")!!
        assertTrue(out.startsWith(BrowseUrl.HOME + "?q="))
    }

    @Test
    fun `search query is url encoded`() {
        val out = BrowseUrl.resolve("a&b=c d")!!
        assertFalse("raw & would split the query string: $out", out.substringAfter("?q=").contains("&"))
    }

    // ── isWorthRemembering ───────────────────────────────────────────────────

    @Test
    fun `remembers a real page`() {
        assertTrue(BrowseUrl.isWorthRemembering("https://example.com/watch/1"))
    }

    @Test
    fun `does not remember the search page or junk`() {
        assertFalse(BrowseUrl.isWorthRemembering(BrowseUrl.HOME))
        assertFalse(BrowseUrl.isWorthRemembering(BrowseUrl.HOME + "?q=hello"))
        assertFalse(BrowseUrl.isWorthRemembering(""))
        assertFalse(BrowseUrl.isWorthRemembering("about:blank"))
    }
}
