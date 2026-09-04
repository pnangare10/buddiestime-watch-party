# Plan — "Browse": sync a video from any website

Status: DRAFT (pre-critique)
Date: 2026-09-04

## Goal

Today the app can only reach four hard-coded services (Hotstar, Netflix, Prime Video,
YouTube), each a fixed start URL in `Services.kt`. Add a fifth option, **Browse**, that
opens a real in-app browser: the user types any URL or search phrase, navigates freely,
and any HTML5 `<video>` on the page syncs with their partner exactly like the four
built-in services do.

Acceptance: host picks Browse → types a site → plays a video → the guest lands on the
same page and stays within the existing ±1s drift budget through play / pause / seek.

## What already works (verified by reading, not assumed)

These are the load-bearing facts the design leans on. Each was checked in the source:

1. **The server needs no change.** `server/validate.js::parseJoinContent` accepts *any*
   `platform` string of 1–32 chars — there is no whitelist of service names. `isNavigableUrl`
   accepts any `http:`/`https:` URL. So `platform: "browse"` and an arbitrary `videoUrl`
   already pass the wire contract untouched.
2. **No new protocol fields.** `platform` and `videoUrl` are already on `join` and
   `state-update`. This change adds no message type and no field, so `extension/` and
   `bookmarklet/` need no parity change. (CLAUDE.md flags protocol drift as the main risk
   in this repo — worth stating that it does not apply here.)
3. **Guest navigation is already URL-driven, not service-driven.**
   `SyncPolicy.shouldReload(hostUrl, guestUrl, …)` compares normalized URLs and calls
   `isNavigable()`; nothing in it is keyed to a known service. A guest will therefore
   follow a host onto an arbitrary domain with no change to the sync core.
4. **`MainActivity` already accepts a start URL** via the `hwp_url` intent extra
   (`intent.getStringExtra("hwp_url") ?: currentService?.url`), used today by the debug
   path. Browse can reuse it rather than inventing a new channel.
5. **`webView.canGoBack()` is already wired into `onBackPressed()`**, so browser-style
   back works for free.

## Design

### 1. `BrowseService` — `Services.kt`

```kotlin
object BrowseService : StreamingService {
    override val name = "browse"
    override val displayName = "Browse"
    override val url = BrowseUrl.HOME          // search home page
    override val userAgent = <same desktop Chrome UA string as the other four>
    override val headersOverride = mapOf("X-Requested-With" to "", "Accept-Language" to "en-IN,en;q=0.9")
}
```

Add `"browse" -> BrowseService` to `getStreamingService`. The existing
`else -> HotstarService` fallback stays.

*Known, accepted limitation:* a guest running an **older build** that receives
`platform="browse"` falls through to `HotstarService` and applies the Hotstar UA. It still
navigates correctly (navigation is URL-driven, per fact 3), so the failure mode is a wrong
user-agent string, not a broken sync. Not worth a protocol version bump.

### 2. `BrowseUrl.kt` — new pure object, unit-testable

The address bar is an untrusted input surface that feeds `WebView.loadUrl`. This is exactly
the hazard `SyncPolicy.isNavigable` guards against on the wire: a `javascript:` URL handed
to `loadUrl` does not navigate — it **executes inside the currently loaded page**, which may
be the user's signed-in session, with `HwpBridge` attached. The address bar needs the same
guard, so it gets its own tested object rather than an inline `if` in the Activity.

```kotlin
object BrowseUrl {
    const val HOME = "https://duckduckgo.com/"
    fun resolve(input: String): String?    // null == nothing to do
}
```

Rules, in order:
- blank → `null`
- parses with scheme `http`/`https` (case-insensitive) → return trimmed as-is
- parses with **any other scheme** (`javascript:`, `file:`, `content:`, `intent:`, `data:`)
  → **treat as a search phrase**, never navigate. Fail-safe, not fail-closed: the user gets
  a search result instead of a silent no-op.
- no scheme, no whitespace, contains a dot, matches a host-shaped pattern
  (`^[A-Za-z0-9._~-]+(:\d+)?(/.*)?$`) → prefix `https://`
- everything else → `HOME + "?q=" + URLEncoder.encode(input)`

Uses `java.net.URI` (not `android.net.Uri`) so it stays a plain JVM unit test with no
Robolectric — same reason `SyncPolicy` does.

### 3. Selector entry point

- `activity_service_selector.xml`: third row, one full-width `cardBrowse` (the four existing
  cards are 2×2; a full-width fifth reads as "the other option" rather than a lopsided grid).
  New `drawable/ic_service_browse.xml` (vector globe) and `color/brand_browse`.
- `ServiceSelectorActivity`: `cardBrowse` → `ensureNameThenLaunch("browse")`.
- `launchParty` gains an optional `startUrl` and puts it in `hwp_url`. For browse it passes
  the last-visited browse URL from prefs (`KEY_LAST_BROWSE_URL`), falling back to
  `BrowseUrl.HOME`, so re-entering Browse resumes where the user left off.

No extra dialog before entering — the in-app address bar (below) is the single input surface.

### 4. In-app browser chrome — `activity_main.xml` + `MainActivity`

`activity_main.xml` today is a `FrameLayout` whose first child is a bare `<WebView>`; every
other child positions itself with `layout_gravity`. To add a bar that **takes layout space**
(rather than overlapping the page) without disturbing those siblings, wrap only the WebView:

```
FrameLayout (root, unchanged)
 └─ LinearLayout(vertical)          ← new
     ├─ LinearLayout @+id/browseBar  (48dp, visibility=gone)
     │    ├─ @+id/btnBrowseBack   ◀
     │    ├─ @+id/etBrowseUrl     (weight=1, inputType=textUri, imeOptions=actionGo)
     │    ├─ @+id/btnBrowseGo     →
     │    └─ @+id/btnBrowseReload ⟳
     └─ WebView @+id/webView       (weight=1)
 └─ … all existing siblings, untouched
```

`MainActivity`:
- `browseBar.visibility = VISIBLE` iff the effective service is `browse`.
- Go / IME-actionGo → `BrowseUrl.resolve(text)` → `webView.loadUrl(url, headersOverride)`;
  dismiss the keyboard.
- Back → `webView.goBack()` when `canGoBack()`. Reload → `webView.reload()`.
- Mirror the current URL into `etBrowseUrl` from `onPageFinished` / `doUpdateVisitedHistory`
  / `JsBridge.onUrlChange`, **but only when the field is not focused** — otherwise it stomps
  on what the user is mid-way through typing.
- Hide the bar in `onShowCustomView` and restore it in `onHideCustomView`, so it never sits
  over a fullscreen video.
- Persist the top-frame URL to `KEY_LAST_BROWSE_URL` on `onPageFinished` while in browse mode.
- **Re-evaluate bar visibility when `onSyncCommand` switches `currentService`** — a guest that
  started on Hotstar and follows a browse-mode host must grow an address bar.

### 5. Cross-origin iframe video — the real technical risk

Most sites that are not a first-party service embed their player in a **cross-origin
`<iframe>`**. `WebView.evaluateJavascript` runs only in the **main frame**, so
`document.querySelectorAll('video')` returns nothing and sync silently does nothing —
no error, no log, just a party that never syncs. On the sites this feature exists to
support, this is the *expected* case, not an edge case.

Fix, using `androidx.webkit` (new dependency, `1.11.0`, compileSdk 34 compatible):

- `WebViewCompat.addDocumentStartJavaScript(webView, SYNC_SCRIPT, setOf("*"))` injects the
  sync script into **every frame** at document start.
- `addJavascriptInterface` is main-frame-only too, so sub-frames cannot call `HwpBridge`.
  Sub-frames instead `window.top.postMessage({__hwp: …}, '*')`; the top frame relays to
  `HwpBridge`. (Cross-origin `postMessage` to `window.top` is allowed.)

Script changes, all gated on `var IS_TOP = (window.top === window)`:
- **Host reporting:** top frame calls `HwpBridge.onStateUpdate` as today; sub-frames post
  `{__hwp:'state', …}` upward and the top frame forwards it.
- **Guest commands:** native still calls `HWP_applyHostState` / `HWP_setRole` on the top
  frame only; the top frame re-broadcasts to `window.frames[i].postMessage({__hwp:'apply'|'role'})`.
- **URL reporting stays top-only.** A sub-frame reporting its own `location.href` would
  make the guest navigate to the *iframe's* src instead of the page — gate `onUrlChange`
  on `IS_TOP`.
- Frames with no `<video>` no-op, so the broadcast is harmless.

**Graceful degradation is mandatory:** guard on
`WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)`. When unsupported,
fall back to today's `onPageFinished` + `evaluateJavascript` main-frame injection — i.e. the
existing four services behave *exactly* as they do now, and browse still works on any site
whose video is in the top document.

The `if (window.__hwpNative) return;` guard already at the top of `SYNC_SCRIPT` makes the
double-injection (document-start *and* `onPageFinished`) idempotent.

**Build risk:** `androidx.webkit` is not in the local Gradle cache, so the first build needs
network. Verify dependency resolution with a throwaway build *before* writing the iframe layer,
so a resolution failure costs minutes, not the whole feature. If it cannot resolve, drop
§5 entirely — §1–4 still ship a working Browse for top-document video.

### 6. Popup suppression (browse mode only)

Arbitrary sites open pop-unders. In browse mode only, `shouldOverrideUrlLoading` blocks a
main-frame navigation that is **not a redirect**, has **no user gesture**
(`request.hasGesture() == false`), and targets a **different host** than the current page.
Logged, never silent.

Deliberately narrow: it catches automatic pop-unders and leaves every gesture-initiated
navigation alone. It does **not** catch a click-triggered interstitial — that is what the
new Back button is for. The other four services keep today's behaviour byte-for-byte
because the guard is gated on browse mode.

## Files touched

| File | Change |
|---|---|
| `android/.../Services.kt` | + `BrowseService`, + `"browse"` branch |
| `android/.../BrowseUrl.kt` | **new** — URL/search resolution + scheme guard |
| `android/.../ServiceSelectorActivity.kt` | + `cardBrowse` handler, `launchParty(startUrl)` |
| `android/.../MainActivity.kt` | browse bar wiring, all-frames injection, popup guard |
| `res/layout/activity_service_selector.xml` | + full-width Browse card |
| `res/layout/activity_main.xml` | wrap WebView in vertical LinearLayout + browse bar |
| `res/drawable/ic_service_browse.xml` | **new** vector globe |
| `res/values/colors.xml` | + `brand_browse` |
| `app/build.gradle` | + `androidx.webkit:1.11.0` |
| `test/.../BrowseUrlTest.kt` | **new** |
| `test/.../ServicesTest.kt` | **new** |
| `server/**` | **none** (see fact 1) |
| `extension/`, `bookmarklet/` | **none** (see fact 2) |

## Test plan

**Unit (JVM).** `BrowseUrl.resolve`: blank, full http/https, uppercase `HTTPS://`, bare host,
host+path, host+port, search phrase with spaces, `javascript:alert(1)`, `file:///etc/passwd`,
`intent://`, `data:text/html,…`, leading/trailing whitespace. `getStreamingService`: `"browse"`,
`"BROWSE"`, unknown → Hotstar. Run via `testDebugUnitTest` using the **Scheduled-Task Gradle
workaround** (`gradle-cannot-run-in-this-env` memory — Gradle dies on loopback when launched
directly from this session). Fast pre-flight: the standalone `kotlin-compiler-embeddable`
type-check from that same memory.

**Emulator (two devices, host 5554 / guest 5556).** Driven by `tools/emu.js` — accessibility
tree and logcat, **never screenshot-navigation** (`emulator-navigation-no-screenshots` memory).
Note `emu ui` cannot read the *guest* player screen (250ms self-check blocks uiautomator idle);
use `emu logcat` / `emu drift` there.

1. Install on both; confirm pairing survives (else re-run pairing).
2. Host: Browse → address bar → target site → page loads, bar shows the resolved URL.
3. Host plays a video → `HWP-MAIN` logs `onStateUpdate` with a non-zero time.
4. Guest joins → logs show it navigating to the host's URL, then `correcting`/`host state`.
5. `emu drift` through play → pause → seek → play; assert within the ±1s `DRIFT_SEC` budget.
6. **Deterministic control:** the same run against a local page with a plain top-document
   `<video>` served over `http://10.0.2.2:8080`. This isolates *our* sync code from any
   given site's player quirks — a failure on a third-party site with the control passing
   means the site, not the feature.

## Rollback

Every change is additive and gated. Removing the `cardBrowse` view and the `"browse"` branch
in `getStreamingService` disables the feature completely; the four existing services never
enter browse-mode code paths (all guards are `service.name == "browse"` or feature-detected).

## Open question for critique

Is §5 (all-frames injection) worth a new dependency in the first pass, or should it ship as a
follow-up once §1–4 are proven on the emulator?
