# P0-3 — WebSocket membership authentication (staged rollout)

Branch: `fix/p0-websocket-auth` (off `fix/p0-security-reliability`)
Status: **DRAFT — awaiting approval** · revised after critique (§8)
Related: [2026-08-02-p0-security-and-reliability.md](2026-08-02-p0-security-and-reliability.md) (Fixes 1–4 shipped; Fix 5 split out)

---

## 1. The gap, stated precisely

The HTTP layer authenticates. The WebSocket layer does not.

Every pairing endpoint proves membership before acting: `getRoomView` checks
`room.ownerDeviceId === deviceId || room.partnerDeviceId === deviceId`, and
`setTheme` / `addMessage` / `removeMessage` / `GET /api/rooms/:id` all route through it.

The WebSocket `join` frame carries `roomId` and a **client-chosen** `clientId`
(`android-<random>` — [WatchPartyManager.kt:60](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/WatchPartyManager.kt#L60)).
The server never asks who that is. `roomId` alone is the entire credential.

`roomId` is not a good credential. It is 128 bits of entropy, but it is a
**capability URL** — it travels in every invite link over WhatsApp/SMS, appears in
`GET /pair/:roomId/:token` server logs and browser history, is printed in Android
logcat on every join, and is accepted unauthenticated by `GET /api/room/:roomId`.
Anything that leaks a link leaks full room control.

### Verified repro

`scratchpad/repro-p03.js` — builds a genuine paired room over HTTP exactly as the
app does, connects the real partner, then joins as a stranger holding only the
`roomId`:

```
paired room created  roomId = 3c2506f61980e9b289948276c40c3496
owner deviceId       = 6d114dc73e0a08707043e33a4206ee1f

Ann joined           role = host
ATTACKER joined      role = guest  accepted = true
  → leaked to attacker: videoUrl = https://hotstar.com/real  time = 0

Ann received attacker chat      = true
Ann received close-app command  = true (claims to be from "Mallory")

HTTP layer's view of the attacker: POST /nudge → 409 {"ok":false,"reason":"no-push-token"}
```

What that buys an attacker holding only a leaked link:

| Capability                                                 | Mechanism                                               |
| ---------------------------------------------------------- | ------------------------------------------------------- |
| Read what the couple is watching, and where they are in it | `joined` payload carries `videoUrl` + projected `time`  |
| Read all chat                                              | `broadcastToAll`                                        |
| Inject chat under any display name                         | `sanitizeName` bounds length, not identity              |
| **Force-close the partner's app**                          | `close-app` relay — the peer obeys and exits            |
| **Control playback** if they connect to an idle room first | first joiner of an empty room becomes `host`            |
| Join the LiveKit voice call and listen                     | `voice-token-request` mints a token for any room member |

The last two are the sharp ones. Room state survives disconnect for
`ROOM_GRACE_MS` (5 min) but the in-memory `rooms` map is empty between sessions,
so **any attacker connecting before the couple does is host** — they push
`state-update` and the real partner's WebView obeys it.

### Second finding, same class

The repro's last line was supposed to read `not-a-room-member`. It doesn't, because
`triggerNudge` never checks membership ([pairing.js:177–181](../../../server/pairing.js#L177)):

```js
const recipientId =
  room.ownerDeviceId === triggeringDeviceId
    ? room.partnerDeviceId
    : room.ownerDeviceId; // ← any non-member lands here
```

A stranger with the `roomId` falls into the `else` branch and pushes an FCM
notification to the room **owner**, repeatedly. It returned `no-push-token` only
because no FCM token is registered yet (the separate P1 item). Three lines to fix,
pure logic, no rollout risk — folded into this batch.

### Third finding — surfaced by the critique, and it predates this work

A single socket sending two `join` frames is left in **both** rooms, and the first
one leaks forever. The close handler only cleans up the room named by the socket's
current `roomId` closure variable, so the earlier room keeps a dead socket, never
reaches `size === 0`, never schedules expiry, and reports a phantom live party
through `/api/room/:id`.

The critique attributed this to the `await` this plan introduces. It does not:
`scratchpad/dbl-join.js` proves it on current `main`, which is fully synchronous —

```
RESULT — two joins on ONE socket, current main (synchronous):
  room RA  count = 1  active = true
  room RB  count = 1  active = true
after the socket dies:
  room RA  count = 1  active = true   <-- stale
  room RB  count = 0  active = true
```

Pre-existing, not introduced. It is in scope anyway because the per-socket join
guard that fixes it (§2) is the same guard that keeps the new `await` safe.

---

## 2. Design

### Identity: reuse `deviceId`

`deviceId` is already the app's bearer credential for every HTTP endpoint, already
persisted in SharedPreferences (`DeviceIdentity.KEY_DEVICE_ID`), already 128-bit
random, and — critically — **never appears in an invite link**. The WS join frame
gains it, and the server checks it against the same source of truth the HTTP layer
uses.

**What this is and is not.** This is membership binding with a bearer secret, not
cryptographic authentication. It does not defend against a device whose prefs have
been read, and it does not sign individual frames. It closes the actual gap — that
a _link_ is currently sufficient — by requiring a secret that links never carry.
Signed, expiring session tokens are the real answer and are a later plan; this is
the version that ships without a protocol redesign.

### Verification rule

```
room = await pairingStore.getRoom(roomId)
room == null                  → PASSTHROUGH   (no pairing record: ad-hoc / dev room)
deviceId is owner or partner  → MEMBER
otherwise                     → NON-MEMBER
store threw                   → STORE-ERROR
```

**The passthrough clause is what makes this shippable.** The earlier plan assumed a
cutover would break all 53 server tests plus `test-page.html`, `guest-test.html`,
`host-sim.js` and `room.html`. It doesn't: those all use ad-hoc room ids (`V1`,
`H1`, `S1`) that have no pairing record, so they keep today's behaviour untouched.

Verified rather than assumed — across all 11 test files, the only `api/rooms`
reference inside a WebSocket test is `GET /api/rooms/status`
([rooms.test.js:190](../../../server/test/rooms.test.js#L190)), a read. **No existing
test creates a paired room and then joins it over WebSocket, so enforcement causes
zero test churn.**

Every room a real user is in was created by `POST /api/rooms` and always has a
record. Passthrough is not a bypass: choosing an unregistered `roomId` puts you in
an empty unrelated room, not the victim's.

The one casualty is `room.html` served at `/room/:id`, which could join a _paired_
room from a browser. Nothing in the app or invite flow links to it (only the legacy
`extension/popup.js` does), so it is dev-only in practice. Called out in §7.

### Three modes, one env var

`WS_AUTH_MODE`:

| Value                 | Behaviour                                                                       |
| --------------------- | ------------------------------------------------------------------------------- |
| `observe` _(default)_ | Verify, log the verdict, **always allow**. Ship-safe.                           |
| `enforce`             | Non-member gets `{type:'error',reason:'not-a-room-member'}` then `close(1008)`. |
| `off`                 | Skip the check entirely. Escape hatch.                                          |

The stage gate is an env change on Render, not a redeploy — rollback is one
variable, seconds, no build.

### Store failure: cache, then fail open

`getRoom` is a network call to Upstash. Two consequences.

**Cost.** One Redis GET per join, and reconnects are frequent on mobile
(`BackoffPolicy` retries). Mitigate with an in-process cache: `Map<roomId,
{ownerDeviceId, partnerDeviceId, at}>`, 60s TTL, explicitly invalidated in the
`redeemInvite` route — the only place membership changes. Without that
invalidation a freshly-redeemed partner would be rejected for up to 60s because
the cached record still has `partnerDeviceId: null`.

**Availability.** If Upstash is down and we fail closed, nobody can join and the app
is dead — a self-inflicted outage strictly worse than the vulnerability. On a store
_error_ (not a `null` room): use the cache if warm; otherwise log
`[WS-AUTH] verdict=store-error — allowing` and allow. Fail-open on error degrades to
exactly today's security level, never below it. Configurable via
`WS_AUTH_FAIL=open|closed`; `open` is the default and my recommendation.

### The structural hazard: an `await` inside a synchronous handler

`handleWsMessage` is fully synchronous today, and the host-continuity fix shipped
last batch depends on it. Its comment at [server.js:857–864](../../../server/server.js#L857)
is load-bearing:

> They cannot interleave: `stale.close()` only queues the close event, and Node's
> event loop is single-threaded, so this handler always runs to completion first.

Adding `await getRoom(...)` breaks that invariant unless the ordering is exact. The
critique was right that the first draft specified it ambiguously ("run the existing
block unchanged, and only then assign the closure variables" — contradictory, since
the existing block _begins_ with that assignment at [server.js:808](../../../server/server.js#L808)).
Pinned down:

```
join frame arrives
 ├─ 1. sync: parse + sanitizeName + parseJoinContent + parseDeviceId
 ├─ 2. sync: if (joinInFlight || roomId !== null) → reject, return      ← join guard
 ├─ 3. sync: joinInFlight = true
 ├─ 4. AWAIT wsauth.checkMembership(...)          ← the only await
 ├─ 5. sync: joinInFlight = false
 ├─ 6. sync: if (ws.readyState !== OPEN) return                          ← died mid-await
 ├─ 7. sync: if (!allow) → send error, close(1008), return               ← mutates nothing
 └─ 8. ══ CRITICAL SECTION, byte-for-byte today's code, no await inside ══
        roomId = incomingRoom;  clientId = incomingId;   (as today, at the top)
        create-room-if-absent → cancelRoomExpiry → capture priorRole →
        evict stale → room.set(...) → byId.set(...) → hostless check →
        send `joined` → broadcastParticipants
```

Steps 1–7 mutate no shared state, so nothing they observe can go stale. Step 8 runs
in one uninterrupted turn, exactly as today, so the host-continuity invariant holds
verbatim.

Two consequences to state rather than discover later:

- **The join guard (step 2) is what makes this safe, and it fixes the pre-existing
  double-join leak from §1.** One socket, one room, for its lifetime. A second join
  is answered with `{reason:'already-joined'}` instead of silently corrupting a room.
- **During the await, `roomId`/`clientId` are still null**, so any non-join frame
  arriving in that window hits the existing guard at [server.js:907](../../../server/server.js#L907)
  and is dropped with a warning. Benign in practice — every client sends its first
  frame only after receiving `joined` (Android gates on `role`, set in the `joined`
  handler; `host-sim.js` and `test-page.html` likewise) — but it is a real behaviour
  change from today's synchronous join, and test 10 pins it.

---

## 3. Changes

### Server

**`server/wsauth.js`** (new) — isolated so it is unit-testable without a socket.

```js
function invalidate(roomId)
async function checkMembership(store, roomId, deviceId, now)
  → { verdict: 'member'|'non-member'|'passthrough'|'store-error', role?, cached: bool }
function decide(verdict, mode, failMode) → { allow: bool, reason?: string }
```

`decide` is pure — the whole mode × verdict × fail-mode matrix is table-tested with
no I/O.

**`server/server.js`**

- Import `wsauth`; read `WS_AUTH_MODE` (default `observe`), `WS_AUTH_FAIL` (default `open`).
- Restructure the `join` branch per the 8 steps above; add the per-socket
  `joinInFlight` flag.
- **Add `deviceId` to the room member record** —
  `room.set(ws, { role, id: clientId, name, voice: false, deviceId })`
  ([server.js:870](../../../server/server.js#L870)). The critique correctly caught that
  the first draft's auto-nudge fix referenced `senderInfo.deviceId`, which does not
  exist; this is what creates it.
- Log one line per join, with the verdict distinguishable so passthrough, store-error
  and genuine membership never blur together in the rollout signal:
  `[WS-AUTH] room=<id> device=<id|absent> verdict=<member|non-member|passthrough|store-error> cached=<bool> mode=<m> allowed=<bool>`
- `GET /api/authmode` → `{mode, fail}`. Confirms what Render is actually running
  without grepping logs; it is the step-C gate check.
- Call `wsauth.invalidate(roomId)` in the `POST /api/rooms/:id/join` (redeem) route.

**`server/validate.js`** — `parseDeviceId(msg)`: string, 1–64 chars, else `null`.
Same discipline as `parseJoinContent`; nothing reads `msg.deviceId` directly.

**`server/pairing.js`** — `triggerNudge` gains the guard the other mutators already
have:

```js
const isMember =
  room.ownerDeviceId === triggeringDeviceId ||
  room.partnerDeviceId === triggeringDeviceId;
if (!isMember) return { ok: false, reason: "not-a-room-member" };
```

⚠️ This also runs on the **auto-nudge on video-start** path
([server.js:960](../../../server/server.js#L960)), which passes the WS `clientId`
(`android-123`) — never a real deviceId. That call already misbehaves today (it
treats the host as a non-owner and nudges the owner); with the guard it would start
returning `not-a-room-member` and auto-nudge would stop firing. Auto-nudge is
already dead in production because no FCM token is ever registered (P1), so nothing
observable changes — but it is handled deliberately, in the same commit: pass
`senderInfo.deviceId`, which the room-record change above makes real. Test 15 pins it.

### Android

**`WatchPartyManager.kt`**

- `JoinParams` gains `deviceId: String?`; `connect(...)` gains the parameter.
- Extract `internal fun buildJoinPayload(p: JoinParams): JSONObject` from the
  `onOpen` lambda so the frame shape is unit-testable (`org.json:json:20231013` is
  already on the test classpath — [app/build.gradle:88](../../../android/app/build.gradle#L88)).
- Omit the key entirely when `deviceId` is null rather than sending `"null"`.
- Surface `not-a-room-member` through the existing `onServerError` path as an
  actionable message ("this device isn't paired to the room — reopen your invite"),
  not a raw reason code. Without this, a stale APK at step C shows a cryptic failure.

**`MainActivity.kt`** — the single `connect(...)` call site
([line 635](../../../android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt#L635))
passes `DeviceIdentity(prefs).localDeviceId()`. If null the device was never
registered; log loudly and continue (observe tolerates it; step B's gate guarantees
it is set before enforcement).

---

## 4. Tests

Server — `server/test/ws-auth.test.js`, all `HWP_STORE=fake`, seeded through the
real `POST /api/devices` + `POST /api/rooms` so fixtures cannot drift from
production shapes:

| #   | Test                                                                             | Guards                               |
| --- | -------------------------------------------------------------------------------- | ------------------------------------ |
| 1   | observe: non-member joins, allowed, room unaffected                              | stage 1 is non-breaking              |
| 2   | enforce: owner deviceId → accepted, role `host`                                  | the happy path                       |
| 3   | enforce: partner deviceId → accepted                                             | redeemed-invite path                 |
| 4   | enforce: deviceId absent → `not-a-room-member`, close 1008                       | old APKs refused, loudly             |
| 5   | enforce: wrong deviceId → rejected                                               | the §1 repro, inverted               |
| 6   | enforce: room with no pairing record → accepted                                  | the passthrough clause               |
| 7   | enforce: rejected join creates no room, absent from `/api/room/:id`              | no mutation on reject                |
| 8   | enforce: rejected join does not evict a legitimate same-`clientId` socket        | the await hazard                     |
| 9   | enforce: host reconnect still keeps host                                         | regression guard on last batch's fix |
| 10  | non-join frame arriving mid-await is dropped, join still completes               | the await hazard                     |
| 11  | socket closed during the await is not added to the room                          | the await hazard                     |
| 12  | **second join on one socket → `already-joined`; first room not leaked**          | §1 third finding                     |
| 13  | store error, warm cache → allowed from cache; cold cache + `FAIL=open` → allowed | outage tolerance                     |
| 14  | redeemed partner can join immediately — cache invalidation works                 | the 60s stale-cache trap             |

`server/test/wsauth.unit.test.js` — `decide()` truth table over
{member, non-member, passthrough, store-error} × {observe, enforce, off} × {open, closed}.
Pure, instant, no server.

`server/test/pairing.test.js` (additions):

| #   | Test                                                                        |
| --- | --------------------------------------------------------------------------- |
| 15  | `triggerNudge` from a non-member → `{ok:false, reason:'not-a-room-member'}` |
| 16  | `triggerNudge` owner→partner and partner→owner still route correctly        |

Android — `WatchPartyManagerTest.kt` (new): payload carries `deviceId` when set,
omits the key when null, leaves `roomId`/`clientId`/`displayName` unchanged. Run via
the detached Scheduled Task ([L036](../../../../myVerse/Learnings/L036-Sandbox-Loopback-Block-Fixed-By-Detached-Scheduled-Task.md)) — Gradle cannot run directly here.

Written red-first: every server test asserted failing against current `main` before
the fix lands.

---

## 5. Rollout

| Step  | Action                                                                     | Gate to the next step                                                                                                    |
| ----- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **A** | Ship server: `observe` default + nudge fix + join guard. Deploy to Render. | `/health` green; `/api/authmode` reports `observe`; 53 existing + new tests pass; old APKs still connect.                |
| **B** | Ship Android: `deviceId` in the join frame. Install on **both** devices.   | Render logs show `verdict=member` for **both** deviceIds, including after a reconnect — not just a cold start.           |
| **C** | Set `WS_AUTH_MODE=enforce` in Render env.                                  | `/api/authmode` confirms `enforce`; watch one full session; any `allowed=false` for a real device → revert the variable. |

Step C is a variable, not a deploy. Rollback is seconds and needs no build.

**Do not skip B's gate, and check it per-device, not per-room.** If one device is
offline on an old APK when C flips, it is locked out with a `not-a-room-member`
error on next connect. Recovery is either updating that APK or reverting the env
var — neither is automatic, which is why the gate is stated as _both_ deviceIds
observed, not "looks fine".

---

## 6. Effort

| Piece                                                                     | Size                                         |
| ------------------------------------------------------------------------- | -------------------------------------------- |
| `wsauth.js` + unit test                                                   | small, pure                                  |
| `server.js` join restructure + join guard + deviceId on the member record | **the risky one** — touches last batch's fix |
| `validate.js`, `pairing.js`, `/api/authmode`, cache invalidation          | small                                        |
| 16 server tests + Android test                                            | the bulk of the wall-clock                   |
| Android plumbing (2 files)                                                | small; needs a Scheduled-Task Gradle run     |

Steps A and B are separate commits and separate deploys by construction.

---

## 7. What this does not fix — read before assuming you're safe

- **Not real authentication.** A bearer `deviceId` in prefs. Anything that reads
  prefs (rooted device, backup extraction) is still a full compromise. Signed,
  expiring session tokens remain a separate plan.
- **`GET /api/room/:roomId` stays unauthenticated** and still leaks `videoUrl`,
  playback position and member count to anyone with the roomId. Belongs with the P1
  exposure/rate-limit batch.
- **Invite tokens still never expire**, and the partner slot is still overwritten by
  whoever redeems last, bricking the displaced device (P1, unfixed).
- **`room.html` at `/room/:id` cannot join a paired room once enforcement is on.**
  Nothing links to it today; `WS_AUTH_MODE=off` is the dev escape hatch.
- **No rate limiting.** Enforcement rejects non-members but does not stop a
  connection flood — sockets can still be opened until the process runs out of file
  descriptors (P1).
- **`clientId` is still client-chosen and unverified.** A member can still claim any
  `clientId` and evict another member's socket by colliding on it. Bounded to real
  members instead of the whole internet — a severity downgrade, not a fix.
- **Fail-open on store error is a deliberate hole.** If an attacker can induce an
  Upstash outage, authentication vanishes for its duration. Judged the right trade
  against bricking the app; `WS_AUTH_FAIL=closed` takes the opposite side.

---

## 8. Critique disposition

**Accepted**

| Finding                                                                                                                                                                                                         | Disposition                                                                                                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Join-handler sequencing was specified contradictorily — "run the existing block unchanged, _then_ assign `roomId`", but the block begins with that assignment ([server.js:808](../../../server/server.js#L808)) | Rewritten as the explicit 8-step ordering in §2                                                                                                                          |
| The auto-nudge fix referenced `senderInfo.deviceId`, which does not exist — the member record is `{role, id, name, voice}` ([server.js:870](../../../server/server.js#L870))                                    | Correct, and the first draft was unimplementable. §3 now adds `deviceId` to the record first                                                                             |
| One socket sending two joins ends up in two rooms; the first leaks                                                                                                                                              | Real. **Attribution corrected** — reproduced on current `main`, which is synchronous, so it is pre-existing, not caused by the `await`. Fixed by the join guard; test 12 |
| Logs blur passthrough, store-error and genuine membership                                                                                                                                                       | Verdict is now an explicit field in the log line                                                                                                                         |
| No way to confirm which mode is live without reading logs                                                                                                                                                       | Added `GET /api/authmode` as the step-C gate check                                                                                                                       |
| Old APK gets a cryptic error at step C                                                                                                                                                                          | Android renders `not-a-room-member` as actionable text; §5 states the recovery path                                                                                      |

**Rejected, with evidence**

| Claim                                                                                          | Why it is wrong                                                                                                                                                                                                                                                                                                                             |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| "A race in `redeemInvite` makes `getRoom` return null, letting a guest in via passthrough"     | `createRoom` writes the room record before any invite exists ([pairing.js:30](../../../server/pairing.js#L30)); `redeemInvite` only mutates `partnerDeviceId`. `getRoom` never returns null for a real room. The genuine hazard is the opposite — a _stale cache_ rejecting a fresh partner — which §2 already invalidates and test 14 pins |
| "Existing tests may create a paired room then WS-join it, breaking the passthrough assumption" | Checked all 11 test files: the only `api/rooms` reference in a WS test is `GET /api/rooms/status` ([rooms.test.js:190](../../../server/test/rooms.test.js#L190)), a read. Zero churn                                                                                                                                                        |
| "Cache stampede at TTL boundaries with 100+ concurrent sessions"                               | Two-person app; the cache holds one entry per active room. Not a real load profile                                                                                                                                                                                                                                                          |
