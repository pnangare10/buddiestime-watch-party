# Pairing, Onboarding & Personalization Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Companion design doc:** `docs/superpowers/specs/2026-07-22-pairing-onboarding-redesign-design.md` — read it first. This plan implements every decision recorded there without re-litigating them.

**Goal:** Replace the hardcoded-couple, multi-room Watch Party app with a two-device pairing model: a server-issued anonymous `deviceId` per install, a persistent `Room` record (distinct from the existing ephemeral video-sync session) created by one partner and redeemed by the other via a one-time invite link, a shared daily/manual theme, two independently-editable message pools (nudge vs. welcome), and push-notification "nudges" to invite the partner to watch — automatic on video-start and manual via a button.

**Architecture:** Two new persistent server-side entities, `Device` and `Room`, stored in a small external KV (not in-memory — see Decision D1 below) and exposed via REST (`server/pairing.js` + new HTTP routes in `server.js`). This is entirely separate from the existing `rooms`/`roomState` in-memory Maps in `server.js`, which keep doing exactly what they do today: ephemeral, drift-corrected video-sync for an *active* watch session, now keyed by the same durable `roomId`. Push notifications go through Firebase Cloud Messaging (`server/push.js`), mirroring the existing LiveKit integration pattern (env-configured credentials, a `READY` flag, graceful no-op when unset). On Android, a new pairing/onboarding flow (`WelcomeSetupActivity`, `PairingRedeemActivity`) replaces the current multi-room `RoomsHomeActivity`, a new `SettingsActivity` hosts theme/message-pool/profile editing, and `Personalization.kt`/`FlirtyLines.kt`/`RecentRoomsStore.kt` are deleted outright per the design doc's full-replacement decision.

**Tech Stack:** Node.js (`ws`, built-in `http`/`fetch`), Upstash Redis (REST API, no client SDK — plain `fetch`), `firebase-admin` (server push), Android (Kotlin, OkHttp 4.12, Material 1.11, `org.json`, Firebase Cloud Messaging SDK), JUnit 4 for JVM logic tests, `node:test` for server tests.

---

## Decisions carried over from the design doc (do not re-ask)

- Streaming service stays a free per-session choice — not part of pairing.
- Pairing setup fields: her name + pet name, his name + pet name, timezone (default IST), anniversary date, her birthday, preferred starting theme, per-device quiet hours, optional pairing PIN.
- Invite link is a **one-time-use pointer**, not a payload: `https://<server>/pair/:roomId/:token`.
- Room-name collision → reprompt, no auto-suggest.
- Device-loss recovery → still-linked device mints a fresh invite for the *same* room; no new Room created.
- Nudge notification: **two triggers** — automatic on video-start, and a manual "invite now" button. Both **suppressed entirely** (not deferred) during the recipient's quiet hours.
- Welcome messages: shown on **app-open only**, distinct pool from nudge messages.
- Theme: checkbox "auto-rotate daily" (default **on**); unchecking + picking a theme makes it sticky (manual) until re-checked.
- Link delivery: plain https URL + Android intent filter + HTML fallback page. No Play Store dependency, no verified Android App Links (this app isn't Play Store–listed).
- Full replacement — delete `Personalization.kt`, the multi-room `RoomsHomeActivity` UI, `RecentRoomsStore`. No backward-compat shims. All work stays on the current branch; do not merge to `main`.

## New decisions this plan has to make (design doc didn't specify)

- **D1 — Device/Room persistence.** `render.yaml`/`railway.json` show no persistent disk, so in-memory storage would silently un-pair the couple on every redeploy — unacceptable for data that's supposed to be permanent. **Decision: Upstash Redis, REST API, no SDK dependency** (two env vars: `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`), following the exact pattern `livekit.js` already uses for an external credential-gated service. If you'd rather self-host Postgres/SQLite-with-a-volume, swap `server/store.js`'s implementation — every other task is written against its function signatures, not against Redis specifically. **This needs a real Upstash (or equivalent) account + credentials before Task 1 can be verified end-to-end — flagging so it isn't a silent blocker.**
- **D2 — Push provider.** Firebase Cloud Messaging (Android's standard push channel). Needs a Firebase project + `google-services.json` (Android) + a service-account JSON (server) — same "external console, env-configured" shape as LiveKit. **Also needs the user to create this project before Task 6/Task 15 can be verified against a real device**, though both tasks are written to degrade gracefully (`FCM_READY = false`) exactly like `LK_READY` does today, so the rest of the app still builds/runs without it.
- **D3 — `FlirtyLines.kt`'s role.** The existing splash "flirty line at cold app-launch" and the design doc's "welcome message shown on app-open" are the same feature slot with the source of truth moved from a hardcoded list to `Room.welcomeMessages`. `FlirtyLines.kt` is deleted; its splash call site is rewired to pull from the welcome pool (Task 14). The **nudge pool is a separate, new concept**, introduced fresh for the push-invite feature only (Task 6/15) — it has no predecessor in the current code.
- **D4 — Auth model.** No login. `X-Device-Id` header is the only credential, checked against the `Device` store per request. This is intentionally as lightweight as the app's two-person, non-adversarial threat model — noted as a non-goal to harden further in `§5` of the design doc.

---

## Global Constraints

- Target: Android app + `server/`. Chrome extension, bookmarklet, and `room.html` are untouched (per design doc §8).
- The existing ephemeral `rooms`/`roomState`/`clientsById` Maps and their 5-min grace-expiry in `server.js` are **not touched** by this plan except to key off the new durable `roomId` — do not change drift correction, chat, reactions, or voice.
- New persistent store: Upstash Redis via REST (`fetch`), env vars `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`.
- New push dependency: `firebase-admin` (server), Firebase BOM + `firebase-messaging` (Android). Env var `FIREBASE_SERVICE_ACCOUNT_JSON` (stringified service-account JSON, same shape as how secrets are already handled) or `GOOGLE_APPLICATION_CREDENTIALS` pointing at a mounted file — pick whichever this deploy target supports; code against an abstraction (`server/push.js`) either way.
- Android package: `com.buddiestime.watchparty`. Kotlin source root: `android/app/src/main/kotlin/com/buddiestime/watchparty/`. JVM tests: `android/app/src/test/kotlin/com/buddiestime/watchparty/`.
- Preserve the existing verbose logging style (`Log.d(TAG, ...)` / `console.log('[...]')`) on every branch and state transition.
- Build: `cd android && ./gradlew.bat` (Windows). JVM tests: `./gradlew.bat testDebugUnitTest`. Server tests: `cd server && node --test`.
- Full replacement, no shims: delete dead code in the same commit that supersedes it, don't leave both paths alive behind a flag.

---

## File Structure

**Server (create):**
- `server/store.js` — Upstash-backed KV wrapper: `getDevice/putDevice`, `getRoom/putRoom`, `findRoomIdByName`, `getInvite/putInvite/deleteInvite`.
- `server/pairing.js` — pure business logic (device mint, room create, invite mint/redeem, profile/theme/message-pool mutation) built on top of `store.js`'s interface; unit-testable with an in-memory fake store.
- `server/push.js` — Firebase Admin wrapper: `sendNudge(fcmToken, body)`, `FCM_READY` flag, mirrors `livekit.js` shape.
- `server/test/store.fake.js` — in-memory fake implementing `store.js`'s interface, for fast pairing-logic tests without hitting real Upstash.
- `server/test/pairing.test.js`, `server/test/nudge.test.js` — logic tests against the fake store.

**Server (modify):**
- `server/server.js` — new HTTP routes (`/api/devices`, `/api/rooms`, `/api/rooms/:id/invite`, `/api/rooms/:id/join`, `/api/rooms/:id`, `/api/rooms/:id/theme`, `/api/rooms/:id/messages/:pool`, `/api/rooms/:id/nudge`), a `pair/:roomId/:token` HTML-fallback route, and one hook into the existing `state-update` WS handler to fire the automatic nudge on a host's first play.
- `server/package.json` — add `firebase-admin`.
- `server/.env.example` — document the new env vars.

**Android (create):**
- `.../watchparty/DeviceIdentity.kt` — local `deviceId` bootstrap + persistence.
- `.../watchparty/PairingApi.kt` — thin OkHttp/JSON client for every new server endpoint.
- `.../watchparty/PairingModels.kt` — data classes + parsers for `Room`/`Device`/profile/theme/message-pool JSON.
- `.../watchparty/QuietHours.kt` — pure `isWithinQuietHours(now, start, end): Boolean`.
- `.../watchparty/WelcomeSetupActivity.kt` (+ layouts) — first-run: own profile → partner draft → create room → mint invite → share sheet.
- `.../watchparty/PairingRedeemActivity.kt` (+ layout) — deep-link target; redeems an invite, optional PIN prompt, error states.
- `.../watchparty/SettingsActivity.kt` (+ layouts) — profile editor, theme picker+checkbox, both message-pool editors, quiet hours, notification permission, re-pair action.
- `.../watchparty/FcmService.kt` — `FirebaseMessagingService` subclass: token refresh → `PATCH /api/devices/:id`, incoming nudge → `NotificationCompat` display.
- `android/app/google-services.json` — Firebase config (added once a Firebase project exists; see D2).
- JVM tests: `DeviceIdentityTest.kt`, `PairingModelsTest.kt`, `QuietHoursTest.kt`.

**Android (modify):**
- `.../watchparty/RoomsHomeActivity.kt` — gutted to a single-room home: pairing status, "Start Watching," partner status, "Invite now" button; multi-room browse/search/create UI removed.
- `.../watchparty/MainActivity.kt` — remove `RecentRoomsStore`/multi-room join-by-code dialog; wire real profile names instead of `Personalization`; add the manual nudge button.
- `.../watchparty/FlufflesTheme.kt` — reads `Room.theme` (auto vs. manual) instead of pure local date math when a room is paired.
- `android/app/src/main/AndroidManifest.xml` — new activities, pairing-link intent filter, `POST_NOTIFICATIONS` permission, `FcmService` registration.
- `android/app/build.gradle` — Firebase BOM + `firebase-messaging`, Google Services plugin.

**Android (delete):**
- `.../watchparty/Personalization.kt`
- `.../watchparty/FlirtyLines.kt`
- `.../watchparty/RecentRoomsStore.kt` + `RecentRoomsStoreTest.kt`
- `.../watchparty/ServiceSelectorActivity.kt`'s name-prompt dialog path (name now comes from the paired profile, never asked ad hoc) — keep the service-picker part.

---

## Task 1: Server — persistence layer (`store.js`) over Upstash Redis

**Files:**
- Create: `server/store.js`, `server/test/store.fake.js`
- Modify: `server/package.json`, `server/.env.example`

**Interfaces:**
```js
// store.js
async function getDevice(deviceId) -> DeviceRecord | null
async function putDevice(deviceId, record) -> void
async function getRoom(roomId) -> RoomRecord | null
async function putRoom(roomId, record) -> void
async function findRoomIdByName(roomName) -> string | null   // for uniqueness check
async function reserveRoomName(roomName, roomId) -> boolean   // atomic-ish: false if already taken
async function getInvite(token) -> { roomId } | null
async function putInvite(token, roomId) -> void
async function deleteInvite(token) -> void
const STORE_READY: boolean   // true iff both Upstash env vars are set
```
`store.fake.js` implements the exact same functions over plain in-process `Map`s, for fast synchronous-ish tests that never touch the network.

- [ ] **Step 1: Write the failing store test (against the fake, to lock the contract)**

Create `server/test/store.fake.test.js`:
```js
const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');

test('room name reservation is exclusive', async () => {
  const store = makeFakeStore();
  assert.strictEqual(await store.reserveRoomName('SonuKomal', 'room-1'), true);
  assert.strictEqual(await store.reserveRoomName('SonuKomal', 'room-2'), false, 'second reservation must fail');
  assert.strictEqual(await store.findRoomIdByName('SonuKomal'), 'room-1');
});

test('device and room round-trip', async () => {
  const store = makeFakeStore();
  await store.putDevice('d1', { deviceId: 'd1', roomId: null });
  assert.deepStrictEqual(await store.getDevice('d1'), { deviceId: 'd1', roomId: null });
  assert.strictEqual(await store.getDevice('missing'), null);
});

test('invite token round-trip and delete', async () => {
  const store = makeFakeStore();
  await store.putInvite('tok1', 'room-1');
  assert.deepStrictEqual(await store.getInvite('tok1'), { roomId: 'room-1' });
  await store.deleteInvite('tok1');
  assert.strictEqual(await store.getInvite('tok1'), null);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/store.fake.test.js`
Expected: FAIL — `./store.fake` doesn't exist yet.

- [ ] **Step 3: Implement the fake store**

Create `server/test/store.fake.js`:
```js
function makeFakeStore() {
  const devices = new Map();
  const rooms = new Map();
  const roomNames = new Map();  // roomName -> roomId
  const invites = new Map();    // token -> roomId
  return {
    async getDevice(id) { return devices.get(id) ?? null; },
    async putDevice(id, rec) { devices.set(id, rec); },
    async getRoom(id) { return rooms.get(id) ?? null; },
    async putRoom(id, rec) { rooms.set(id, rec); },
    async findRoomIdByName(name) { return roomNames.get(name) ?? null; },
    async reserveRoomName(name, roomId) {
      if (roomNames.has(name)) return false;
      roomNames.set(name, roomId);
      return true;
    },
    async getInvite(token) { const roomId = invites.get(token); return roomId ? { roomId } : null; },
    async putInvite(token, roomId) { invites.set(token, roomId); },
    async deleteInvite(token) { invites.delete(token); },
  };
}
module.exports = { makeFakeStore };
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd server && node --test test/store.fake.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Implement the real Upstash-backed store.js**

```js
// server/store.js
// Persistent Device/Room storage over Upstash Redis's REST API (no client SDK —
// plain fetch, two env vars, same "external service via env creds" shape as livekit.js).
const BASE = process.env.UPSTASH_REDIS_REST_URL;
const TOKEN = process.env.UPSTASH_REDIS_REST_TOKEN;
const STORE_READY = !!(BASE && TOKEN);

if (!STORE_READY) {
  console.warn('[STORE] Upstash credentials missing — pairing endpoints will fail closed');
  console.warn('[STORE]   UPSTASH_REDIS_REST_URL set?', !!BASE);
  console.warn('[STORE]   UPSTASH_REDIS_REST_TOKEN set?', !!TOKEN);
} else {
  console.log('[STORE] Upstash ready');
}

async function cmd(...parts) {
  const url = BASE + '/' + parts.map(encodeURIComponent).join('/');
  const res = await fetch(url, { headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!res.ok) throw new Error(`[STORE] Upstash ${parts[0]} failed: ${res.status}`);
  const body = await res.json();
  return body.result;
}

async function getJSON(key) {
  const raw = await cmd('GET', key);
  return raw ? JSON.parse(raw) : null;
}
async function putJSON(key, value) {
  await cmd('SET', key, JSON.stringify(value));
}

const deviceKey = (id) => `device:${id}`;
const roomKey = (id) => `room:${id}`;
const roomNameKey = (name) => `roomname:${name.toLowerCase()}`;
const inviteKey = (token) => `invite:${token}`;

async function getDevice(id) { return getJSON(deviceKey(id)); }
async function putDevice(id, rec) { return putJSON(deviceKey(id), rec); }
async function getRoom(id) { return getJSON(roomKey(id)); }
async function putRoom(id, rec) { return putJSON(roomKey(id), rec); }
async function findRoomIdByName(name) { return cmd('GET', roomNameKey(name)); }

// SETNX — atomic "set if not exists"; Upstash returns 1 on success, 0 if the key existed.
async function reserveRoomName(name, roomId) {
  const result = await cmd('SETNX', roomNameKey(name), roomId);
  return result === 1;
}

async function getInvite(token) {
  const roomId = await cmd('GET', inviteKey(token));
  return roomId ? { roomId } : null;
}
async function putInvite(token, roomId) { await cmd('SET', inviteKey(token), roomId); }
async function deleteInvite(token) { await cmd('DEL', inviteKey(token)); }

module.exports = {
  STORE_READY, getDevice, putDevice, getRoom, putRoom,
  findRoomIdByName, reserveRoomName, getInvite, putInvite, deleteInvite,
};
```

- [ ] **Step 6: Add the dependency-free env docs**

Append to `server/.env.example`:
```
# Pairing/Room persistence (Upstash Redis REST API — https://upstash.com, free tier is plenty)
UPSTASH_REDIS_REST_URL=
UPSTASH_REDIS_REST_TOKEN=
```

- [ ] **Step 7: Commit**

```bash
git add server/store.js server/test/store.fake.js server/test/store.fake.test.js server/.env.example
git commit -m "feat(server): Upstash-backed persistent store for Device/Room pairing data"
```

---

## Task 2: Server — pairing business logic (`pairing.js`) over the store interface

**Files:**
- Create: `server/pairing.js`, `server/test/pairing.test.js`

**Interfaces:**
```js
async function createDevice(store) -> { deviceId }
async function createRoom(store, { roomName, ownerDeviceId, ownerProfile, partnerProfileDraft, anniversaryDate }) 
  -> { ok: true, roomId } | { ok: false, reason: 'device-already-in-room' | 'room-name-taken' }
async function mintInvite(store, { roomId, requestingDeviceId, pin }) 
  -> { ok: true, token } | { ok: false, reason: 'not-a-room-member' }
async function redeemInvite(store, { roomId, token, deviceId, pin }) 
  -> { ok: true, herProfile, hisProfile, replacedRole } | { ok: false, reason: 'invalid-token' | 'already-used' | 'pin-required' | 'pin-mismatch' | 'device-already-in-room' }
async function getRoomView(store, { roomId, deviceId }) -> RoomView | { ok:false, reason:'forbidden' }
async function setTheme(store, { roomId, deviceId, mode, value }) -> { ok:true } | { ok:false, reason }
async function addMessage(store, { roomId, deviceId, pool: 'nudge'|'welcome', text }) -> { ok:true, id } | { ok:false, reason }
async function removeMessage(store, { roomId, deviceId, pool, id }) -> { ok:true } | { ok:false, reason }
async function updateDeviceProfile(store, { deviceId, patch }) -> { ok:true } | { ok:false, reason }
```

All functions take the store as a parameter (dependency injection) so tests run against `store.fake.js` with zero network calls; `server.js` wires the real `store.js` in.

- [ ] **Step 1: Write the failing pairing tests**

Create `server/test/pairing.test.js` (representative subset — write the full set covering every branch above):
```js
const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');
const pairing = require('../pairing');

test('createRoom then mintInvite then redeemInvite pairs two devices', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);

  const created = await pairing.createRoom(store, {
    roomName: 'SonuKomal',
    ownerDeviceId: hisId,
    ownerProfile: { displayName: 'Sonu' },
    partnerProfileDraft: { displayName: 'Komal', petName: 'jaanu' },
  });
  assert.strictEqual(created.ok, true);

  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  assert.strictEqual(invite.ok, true);

  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId });
  assert.strictEqual(redeemed.ok, true);
  assert.strictEqual(redeemed.herProfile.petName, 'jaanu');

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.partnerDeviceId, herId);
});

test('redeeming a consumed token fails', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const { deviceId: thirdId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'Room2', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId });

  const second = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: thirdId });
  assert.strictEqual(second.ok, false);
  assert.strictEqual(second.reason, 'already-used');
});

test('room name collision is rejected, no auto-suggestion', async () => {
  const store = makeFakeStore();
  const { deviceId: d1 } = await pairing.createDevice(store);
  const { deviceId: d2 } = await pairing.createDevice(store);
  await pairing.createRoom(store, { roomName: 'Taken', ownerDeviceId: d1, ownerProfile: {}, partnerProfileDraft: {} });
  const second = await pairing.createRoom(store, { roomName: 'Taken', ownerDeviceId: d2, ownerProfile: {}, partnerProfileDraft: {} });
  assert.strictEqual(second.ok, false);
  assert.strictEqual(second.reason, 'room-name-taken');
});

test('pairing PIN must match on redeem when set', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'PinRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId, pin: '4242' });

  const wrong = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId, pin: '0000' });
  assert.strictEqual(wrong.ok, false);
  assert.strictEqual(wrong.reason, 'pin-mismatch');

  const right = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite.token, deviceId: herId, pin: '4242' });
  assert.strictEqual(right.ok, true);
});

test('device-loss recovery: re-invite on a full room replaces the missing side, keeps the room', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'RecoverRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  const invite1 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: herId });

  // she reinstalls, loses herId; his still-linked device requests a fresh invite for the SAME room
  const invite2 = await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId });
  assert.notStrictEqual(invite2.token, invite1.token);
  // the old token must no longer work (a new invite invalidates the prior unredeemed/consumed one)
  const staleAttempt = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite1.token, deviceId: 'ghost' });
  assert.strictEqual(staleAttempt.ok, false);

  const { deviceId: herNewId } = await pairing.createDevice(store);
  const redeemed = await pairing.redeemInvite(store, { roomId: created.roomId, token: invite2.token, deviceId: herNewId });
  assert.strictEqual(redeemed.ok, true);

  const room = await store.getRoom(created.roomId);
  assert.strictEqual(room.partnerDeviceId, herNewId, 'new device replaces the lost one');
  assert.strictEqual(room.ownerDeviceId, hisId, 'owner side untouched');
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/pairing.test.js`
Expected: FAIL — `../pairing` doesn't exist.

- [ ] **Step 3: Implement pairing.js**

```js
// server/pairing.js
// Pure pairing business logic over an injected store (see store.js / store.fake.js
// for the interface both must satisfy). No HTTP here — server.js's routes call these.
const crypto = require('crypto');

function newId() { return crypto.randomBytes(16).toString('hex'); }
function newToken() { return crypto.randomBytes(24).toString('base64url'); }

async function createDevice(store) {
  const deviceId = newId();
  await store.putDevice(deviceId, {
    deviceId, roomId: null, role: null,
    profile: {}, fcmToken: null, quietHours: null, createdAt: Date.now(),
  });
  console.log(`[PAIRING] createDevice → ${deviceId}`);
  return { deviceId };
}

async function createRoom(store, { roomName, ownerDeviceId, ownerProfile, partnerProfileDraft, anniversaryDate }) {
  const device = await store.getDevice(ownerDeviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  if (device.roomId) return { ok: false, reason: 'device-already-in-room' };

  const roomId = newId();
  const reserved = await store.reserveRoomName(roomName, roomId);
  if (!reserved) {
    console.warn(`[PAIRING] createRoom: name "${roomName}" taken`);
    return { ok: false, reason: 'room-name-taken' };
  }

  await store.putRoom(roomId, {
    roomId, roomName, ownerDeviceId, partnerDeviceId: null,
    anniversaryDate: anniversaryDate ?? null,
    theme: { mode: 'auto', value: null, setByDeviceId: null, setAt: null },
    nudgeMessages: [], welcomeMessages: [],
    pendingInvite: null,
    createdAt: Date.now(), updatedAt: Date.now(),
  });
  device.roomId = roomId; device.role = 'owner'; device.profile = ownerProfile || {};
  await store.putDevice(ownerDeviceId, device);

  // partnerProfileDraft is staged on the room until she redeems and it becomes her own profile
  const room = await store.getRoom(roomId);
  room.partnerProfileDraft = partnerProfileDraft || {};
  await store.putRoom(roomId, room);

  console.log(`[PAIRING] createRoom → roomId=${roomId} name="${roomName}" owner=${ownerDeviceId}`);
  return { ok: true, roomId };
}

async function mintInvite(store, { roomId, requestingDeviceId, pin }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const isMember = room.ownerDeviceId === requestingDeviceId || room.partnerDeviceId === requestingDeviceId;
  if (!isMember) return { ok: false, reason: 'not-a-room-member' };

  // A new invite invalidates any prior unredeemed one for this room.
  if (room.pendingInvite) await store.deleteInvite(room.pendingInvite.token);

  const token = newToken();
  await store.putInvite(token, roomId);
  room.pendingInvite = { token, createdAt: Date.now(), pin: pin || null };
  room.updatedAt = Date.now();
  await store.putRoom(roomId, room);
  console.log(`[PAIRING] mintInvite roomId=${roomId} requestedBy=${requestingDeviceId} pinSet=${!!pin}`);
  return { ok: true, token };
}

async function redeemInvite(store, { roomId, token, deviceId, pin }) {
  const invite = await store.getInvite(token);
  if (!invite || invite.roomId !== roomId) return { ok: false, reason: 'invalid-token' };

  const room = await store.getRoom(roomId);
  if (!room || !room.pendingInvite || room.pendingInvite.token !== token) {
    return { ok: false, reason: 'already-used' };
  }
  if (room.pendingInvite.pin && room.pendingInvite.pin !== pin) {
    console.warn(`[PAIRING] redeemInvite roomId=${roomId} — PIN mismatch`);
    return { ok: false, reason: 'pin-mismatch' };
  }

  const device = await store.getDevice(deviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  if (device.roomId && device.roomId !== roomId) return { ok: false, reason: 'device-already-in-room' };

  // Normal join (empty partner slot) vs. device-loss recovery (replacing whichever
  // side isn't the requester who minted this invite — partner slot is the common case).
  const replacedRole = room.partnerDeviceId ? 'partner' : 'partner';
  room.partnerDeviceId = deviceId;
  device.roomId = roomId; device.role = 'partner';
  device.profile = { ...(room.partnerProfileDraft || {}), ...(device.profile || {}) };

  await store.deleteInvite(token);
  room.pendingInvite = null;
  room.updatedAt = Date.now();
  await store.putRoom(roomId, room);
  await store.putDevice(deviceId, device);

  const ownerDevice = await store.getDevice(room.ownerDeviceId);
  console.log(`[PAIRING] redeemInvite roomId=${roomId} newPartner=${deviceId}`);
  return {
    ok: true,
    herProfile: device.profile,
    hisProfile: ownerDevice ? ownerDevice.profile : {},
    replacedRole,
  };
}

async function getRoomView(store, { roomId, deviceId }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const isMember = room.ownerDeviceId === deviceId || room.partnerDeviceId === deviceId;
  if (!isMember) return { ok: false, reason: 'forbidden' };
  return { ok: true, room };
}

async function setTheme(store, { roomId, deviceId, mode, value }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  view.room.theme = { mode, value: mode === 'manual' ? value : null, setByDeviceId: deviceId, setAt: Date.now() };
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  console.log(`[PAIRING] setTheme roomId=${roomId} mode=${mode} value=${value} by=${deviceId}`);
  return { ok: true };
}

async function addMessage(store, { roomId, deviceId, pool, text }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  const key = pool === 'nudge' ? 'nudgeMessages' : 'welcomeMessages';
  const entry = { id: newId(), text, authorDeviceId: deviceId, createdAt: Date.now() };
  view.room[key].push(entry);
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  console.log(`[PAIRING] addMessage roomId=${roomId} pool=${pool} by=${deviceId} text="${text.slice(0,60)}"`);
  return { ok: true, id: entry.id };
}

async function removeMessage(store, { roomId, deviceId, pool, id }) {
  const view = await getRoomView(store, { roomId, deviceId });
  if (!view.ok) return view;
  const key = pool === 'nudge' ? 'nudgeMessages' : 'welcomeMessages';
  view.room[key] = view.room[key].filter((m) => m.id !== id);
  view.room.updatedAt = Date.now();
  await store.putRoom(roomId, view.room);
  return { ok: true };
}

async function updateDeviceProfile(store, { deviceId, patch }) {
  const device = await store.getDevice(deviceId);
  if (!device) return { ok: false, reason: 'unknown-device' };
  device.profile = { ...device.profile, ...patch.profile };
  if (patch.fcmToken !== undefined) device.fcmToken = patch.fcmToken;
  if (patch.quietHours !== undefined) device.quietHours = patch.quietHours;
  await store.putDevice(deviceId, device);
  console.log(`[PAIRING] updateDeviceProfile ${deviceId} patch=${JSON.stringify(patch)}`);
  return { ok: true };
}

module.exports = {
  createDevice, createRoom, mintInvite, redeemInvite,
  getRoomView, setTheme, addMessage, removeMessage, updateDeviceProfile,
};
```

- [ ] **Step 4: Run to verify all tests pass**

Run: `cd server && node --test test/pairing.test.js`
Expected: PASS (all tests above).

- [ ] **Step 5: Commit**

```bash
git add server/pairing.js server/test/pairing.test.js
git commit -m "feat(server): pairing business logic — device/room creation, invite mint+redeem, theme, message pools"
```

---

## Task 3: Server — wire pairing HTTP routes into `server.js`

**Files:**
- Modify: `server/server.js` (require `store.js`/`pairing.js`; new routes in the HTTP handler)

**Interfaces (new HTTP surface):**
```
POST   /api/devices                          -> { deviceId }
POST   /api/rooms                             body: { deviceId, roomName, ownerProfile, partnerProfileDraft, anniversaryDate? }
POST   /api/rooms/:id/invite                  body: { deviceId, pin? }           -> { token }
POST   /api/rooms/:id/join                    body: { deviceId, token, pin? }    -> { herProfile, hisProfile }
GET    /api/rooms/:id                         header X-Device-Id                 -> room view (theme, both pools, partner profile)
PATCH  /api/rooms/:id/theme                   body: { deviceId, mode, value? }
POST   /api/rooms/:id/messages/:pool          body: { deviceId, text }           pool = nudge|welcome
DELETE /api/rooms/:id/messages/:pool/:msgId   body: { deviceId }
PATCH  /api/devices/:id                       body: { profile?, fcmToken?, quietHours? }
GET    /pair/:roomId/:token                   HTML fallback page (see Task 9/10) when opened without the app
```
Every mutating route reads `deviceId` from the JSON body (kept explicit rather than trusting only a header, matching this app's already-lightweight auth model) — `X-Device-Id` header is accepted as an alternative and preferred when both a header and body match; a mismatch is rejected.

- [ ] **Step 1: Write the failing HTTP-level tests**

Extend `server/test/harness.js`'s pattern with a JSON POST helper, then create `server/test/routes.test.js`:
```js
const { test } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { startServer } = require('./harness');

function req(method, url, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(url);
    const r = http.request(u, { method, headers: data ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } : {} }, (res) => {
      let out = '';
      res.on('data', (d) => (out += d));
      res.on('end', () => resolve({ status: res.statusCode, json: out ? JSON.parse(out) : null }));
    });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

test('full pairing round trip over HTTP', async () => {
  const srv = await startServer({ port: 8095, env: { UPSTASH_REDIS_REST_URL: 'fake', UPSTASH_REDIS_REST_TOKEN: 'fake', HWP_STORE: 'fake' } });
  try {
    const hisDevice = await req('POST', srv.baseUrl + '/api/devices');
    assert.strictEqual(hisDevice.status, 200);
    const herDevice = await req('POST', srv.baseUrl + '/api/devices');

    const room = await req('POST', srv.baseUrl + '/api/rooms', {
      deviceId: hisDevice.json.deviceId, roomName: 'HttpTestRoom',
      ownerProfile: { displayName: 'Sonu' }, partnerProfileDraft: { displayName: 'Komal' },
    });
    assert.strictEqual(room.status, 200);

    const invite = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/invite`, { deviceId: hisDevice.json.deviceId });
    assert.strictEqual(invite.status, 200);

    const join = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`, {
      deviceId: herDevice.json.deviceId, token: invite.json.token,
    });
    assert.strictEqual(join.status, 200);
    assert.strictEqual(join.json.herProfile.displayName, 'Komal');

    const second = await req('POST', `${srv.baseUrl}/api/rooms/${room.json.roomId}/join`, {
      deviceId: 'someone-else', token: invite.json.token,
    });
    assert.strictEqual(second.status, 409, 'reused token must be rejected');
  } finally {
    await srv.stop();
  }
});
```

*Note:* this requires `server.js` to support an injectable/fake store for tests without a real Upstash account (via an `HWP_STORE=fake` env switch that swaps in `store.fake.js`) — add that switch in Step 3 below so CI/local runs don't need real Upstash credentials.

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/routes.test.js`
Expected: FAIL — 404s, routes don't exist yet.

- [ ] **Step 3: Implement the routes**

Near the top of `server.js`, alongside the existing `require('./livekit')`/`require('./keepalive')`:
```js
const pairingStore = process.env.HWP_STORE === 'fake' ? require('./test/store.fake').makeFakeStore() : require('./store');
const pairing = require('./pairing');
```

Add a small body-reader helper and the routes inside the existing `http.createServer((req, res) => { ... })` callback, before the final 404 fallback — following the same `if (url.pathname === ...) { ...; return; }` style already used for `/health` and `/api/rooms/status`. Each POST/PATCH/DELETE route reads the body with:
```js
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}
```
Then, for example:
```js
if (req.method === 'POST' && url.pathname === '/api/devices') {
  const { deviceId } = await pairing.createDevice(pairingStore);
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ deviceId }));
  return;
}

if (req.method === 'POST' && url.pathname === '/api/rooms') {
  const body = await readJsonBody(req);
  const result = await pairing.createRoom(pairingStore, body);
  res.writeHead(result.ok ? 200 : 409, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(result));
  return;
}
```
...and equivalent blocks for `/api/rooms/:id/invite`, `/api/rooms/:id/join` (404/409 per the `reason` returned), `/api/rooms/:id` (GET), `/api/rooms/:id/theme` (PATCH), `/api/rooms/:id/messages/:pool` (POST/DELETE), `/api/devices/:id` (PATCH) — each a thin translation of the `pairing.js` function's `{ ok, reason }` result into an HTTP status (200 / 403 for `forbidden` / 404 for `unknown-*` / 409 for `already-used`/`device-already-in-room`/`room-name-taken`/`pin-mismatch`).

Since the HTTP handler is a plain (non-async) callback today, wrap the new async route-handling in an IIFE at the top of the callback (or convert the whole handler to `async`, which is safe here since nothing depends on synchronous completion).

- [ ] **Step 4: Run to verify it passes**

Run: `cd server && node --test test/routes.test.js`
Expected: PASS.

- [ ] **Step 5: Regression-check the existing server tests still pass**

Run: `cd server && node --test`
Expected: PASS (all existing + new tests).

- [ ] **Step 6: Commit**

```bash
git add server/server.js server/test/routes.test.js
git commit -m "feat(server): wire pairing REST routes (devices, rooms, invite, join, theme, messages)"
```

---

## Task 4: Server — automatic nudge on video-start + manual nudge endpoint + push

**Files:**
- Create: `server/push.js`
- Modify: `server/server.js` (hook into the existing `state-update` WS handler; new `POST /api/rooms/:id/nudge` route)
- Modify: `server/package.json` (add `firebase-admin`)

**Interfaces:**
```js
// push.js
const FCM_READY: boolean
async function sendNudge(fcmToken, body) -> { ok: boolean, reason?: string }
```
```js
// pairing.js addition
function pickRandomMessage(room, pool) -> string | null
function isQuietNow(device, nowDate) -> boolean   // reuse QuietHours logic, mirrored server-side
async function triggerNudge(store, pushSend, { roomId, triggeringDeviceId }) -> { ok: boolean, reason?: string }
```

- [ ] **Step 1: Write the failing nudge-suppression test (pure logic, no real FCM)**

Create `server/test/nudge.test.js`:
```js
const { test } = require('node:test');
const assert = require('node:assert');
const { makeFakeStore } = require('./store.fake');
const pairing = require('../pairing');

test('triggerNudge sends to the partner using a message from the nudge pool', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'NudgeRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId }).then((inv) =>
    pairing.redeemInvite(store, { roomId: created.roomId, token: inv.token, deviceId: herId }));
  await pairing.addMessage(store, { roomId: created.roomId, deviceId: hisId, pool: 'nudge', text: 'come watch with me 😏' });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = 'her-fcm-token';
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => { sent.push({ token, body }); return { ok: true }; };

  const result = await pairing.triggerNudge(store, fakePush, { roomId: created.roomId, triggeringDeviceId: hisId });
  assert.strictEqual(result.ok, true);
  assert.strictEqual(sent.length, 1);
  assert.strictEqual(sent[0].token, 'her-fcm-token');
  assert.strictEqual(sent[0].body, 'come watch with me 😏');
});

test('triggerNudge suppresses entirely during quiet hours', async () => {
  const store = makeFakeStore();
  const { deviceId: hisId } = await pairing.createDevice(store);
  const { deviceId: herId } = await pairing.createDevice(store);
  const created = await pairing.createRoom(store, { roomName: 'QuietRoom', ownerDeviceId: hisId, ownerProfile: {}, partnerProfileDraft: {} });
  await pairing.mintInvite(store, { roomId: created.roomId, requestingDeviceId: hisId }).then((inv) =>
    pairing.redeemInvite(store, { roomId: created.roomId, token: inv.token, deviceId: herId }));
  await pairing.addMessage(store, { roomId: created.roomId, deviceId: hisId, pool: 'nudge', text: 'hey' });
  const herDevice = await store.getDevice(herId);
  herDevice.fcmToken = 'her-fcm-token';
  herDevice.quietHours = { startHour: 0, endHour: 23 }; // effectively always quiet, for a deterministic test
  await store.putDevice(herId, herDevice);

  const sent = [];
  const fakePush = async (token, body) => { sent.push({ token, body }); return { ok: true }; };
  const result = await pairing.triggerNudge(store, fakePush, { roomId: created.roomId, triggeringDeviceId: hisId });
  assert.strictEqual(result.ok, false);
  assert.strictEqual(result.reason, 'quiet-hours');
  assert.strictEqual(sent.length, 0, 'must not send at all, not defer');
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/nudge.test.js`
Expected: FAIL — `triggerNudge` doesn't exist.

- [ ] **Step 3: Implement `isQuietNow` + `pickRandomMessage` + `triggerNudge` in pairing.js**

Append to `server/pairing.js`:
```js
function isQuietNow(device, now = new Date()) {
  const qh = device.quietHours;
  if (!qh) return false;
  const hour = now.getHours();
  const { startHour, endHour } = qh;
  return startHour <= endHour
    ? hour >= startHour && hour < endHour
    : hour >= startHour || hour < endHour; // wraps past midnight
}

function pickRandomMessage(room, pool) {
  const list = pool === 'nudge' ? room.nudgeMessages : room.welcomeMessages;
  if (!list || list.length === 0) return null;
  return list[Math.floor(Math.random() * list.length)].text;
}

async function triggerNudge(store, pushSend, { roomId, triggeringDeviceId }) {
  const room = await store.getRoom(roomId);
  if (!room) return { ok: false, reason: 'unknown-room' };
  const recipientId = room.ownerDeviceId === triggeringDeviceId ? room.partnerDeviceId : room.ownerDeviceId;
  if (!recipientId) return { ok: false, reason: 'no-partner' };

  const recipient = await store.getDevice(recipientId);
  if (!recipient || !recipient.fcmToken) return { ok: false, reason: 'no-push-token' };
  if (isQuietNow(recipient)) {
    console.log(`[NUDGE] roomId=${roomId} suppressed — recipient in quiet hours`);
    return { ok: false, reason: 'quiet-hours' };
  }

  const text = pickRandomMessage(room, 'nudge');
  if (!text) return { ok: false, reason: 'no-messages' };

  const result = await pushSend(recipient.fcmToken, text);
  console.log(`[NUDGE] roomId=${roomId} to=${recipientId} text="${text}" result=${JSON.stringify(result)}`);
  return result.ok ? { ok: true } : { ok: false, reason: result.reason || 'push-failed' };
}

module.exports.isQuietNow = isQuietNow;
module.exports.pickRandomMessage = pickRandomMessage;
module.exports.triggerNudge = triggerNudge;
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd server && node --test test/nudge.test.js`
Expected: PASS (2 tests).

- [ ] **Step 5: Implement push.js (Firebase Admin wrapper)**

```js
// server/push.js
// FCM push, mirroring livekit.js's shape: env-configured creds, a READY flag, graceful no-op when unset.
const admin = require('firebase-admin');

const SERVICE_ACCOUNT_JSON = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
const FCM_READY = !!SERVICE_ACCOUNT_JSON;

if (!FCM_READY) {
  console.warn('[PUSH] FIREBASE_SERVICE_ACCOUNT_JSON missing — nudge notifications will fail closed');
} else {
  admin.initializeApp({ credential: admin.credential.cert(JSON.parse(SERVICE_ACCOUNT_JSON)) });
  console.log('[PUSH] Firebase Admin ready');
}

async function sendNudge(fcmToken, body) {
  if (!FCM_READY) return { ok: false, reason: 'push-not-configured' };
  try {
    await admin.messaging().send({
      token: fcmToken,
      notification: { title: 'Watch Party 💗', body },
      data: { type: 'nudge' },
    });
    return { ok: true };
  } catch (e) {
    console.warn(`[PUSH] send failed: ${e.message}`);
    return { ok: false, reason: 'send-failed' };
  }
}

module.exports = { FCM_READY, sendNudge };
```

- [ ] **Step 6: Wire the manual-nudge HTTP route and the automatic on-video-start hook**

In `server.js`, add the route (same pattern as Task 3):
```js
if (req.method === 'POST' && url.pathname.match(/^\/api\/rooms\/[^/]+\/nudge$/)) {
  const roomId = url.pathname.split('/')[3];
  const body = await readJsonBody(req);
  const result = await pairing.triggerNudge(pairingStore, push.sendNudge, { roomId, triggeringDeviceId: body.deviceId });
  res.writeHead(result.ok ? 200 : 409, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(result));
  return;
}
```

For the **automatic** trigger, find the existing `state-update` handler (the block starting `if (msg.type === "state-update")`). It's keyed by the ephemeral `roomId` used for video sync, which is the same durable `roomId` from pairing. Add, right after the existing `Object.assign(state, {...})` call: detect a paused→playing transition (`state.paused was true, msg.paused is false`) and — fire-and-forget, don't block the sync broadcast on it — call `pairing.triggerNudge(pairingStore, push.sendNudge, { roomId, triggeringDeviceId: senderInfo... })`. Track "was paused" by reading the state's previous value before `Object.assign` overwrites it:
```js
const wasPaused = state.paused;
Object.assign(state, { time: msg.time, paused: msg.paused, videoUrl: msg.videoUrl, updatedAt: Date.now() });
if (wasPaused && msg.paused === false) {
  pairing.triggerNudge(pairingStore, push.sendNudge, { roomId, triggeringDeviceId: clientId })
    .then((r) => console.log(`[${roomId}] auto-nudge on video-start → ${JSON.stringify(r)}`))
    .catch((e) => console.warn(`[${roomId}] auto-nudge error: ${e.message}`));
}
```
Require `push` and `pairing` at the top of `server.js` alongside the other requires added in Task 3.

- [ ] **Step 7: Add the dependency + env doc**

```bash
cd server && npm install firebase-admin
```
Append to `server/.env.example`:
```
# Push notifications (Firebase Cloud Messaging — create a Firebase project, generate a
# service-account key, paste the whole JSON as one line here)
FIREBASE_SERVICE_ACCOUNT_JSON=
```

- [ ] **Step 8: Run full server test suite**

Run: `cd server && node --test`
Expected: PASS (all tests; push tests never call real Firebase since `FCM_READY` is false without the env var, and the nudge tests inject a fake `pushSend`).

- [ ] **Step 9: Commit**

```bash
git add server/push.js server/pairing.js server/server.js server/package.json server/package-lock.json server/.env.example server/test/nudge.test.js
git commit -m "feat(server): automatic + manual nudge trigger with quiet-hours suppression, FCM send"
```

---

## Task 5: Server — pairing-link HTML fallback page

**Files:**
- Modify: `server/server.js` (new `GET /pair/:roomId/:token` route)
- Create: `server/pair-fallback.html`

**Interfaces:** a static-ish HTML page (same serving pattern as `install.html`) telling a visitor without the app installed to install it and reopen the link; the Android intent filter (Task 12) intercepts this exact URL pattern when the app *is* installed, so this route only ever renders for browsers.

- [ ] **Step 1: Write the page**

Create `server/pair-fallback.html` (plain HTML, no framework, matching `install.html`'s existing style) with a short explanation and a link back to itself (so re-tapping after installing works), plus a visible room name placeholder the server fills in via simple string substitution — no templating engine needed for one variable.

- [ ] **Step 2: Wire the route**

In `server.js`, alongside the existing `STATIC_ROUTES` handling, add before the final 404 fallback:
```js
if (url.pathname.match(/^\/pair\/[^/]+\/[^/]+$/)) {
  fs.readFile(path.join(__dirname, 'pair-fallback.html'), 'utf8', (err, html) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(html);
  });
  return;
}
```

- [ ] **Step 3: Manual verification**

Run: `node server/server.js`, then `curl http://localhost:8080/pair/abc/def` — expect 200 and the HTML body.

- [ ] **Step 4: Commit**

```bash
git add server/server.js server/pair-fallback.html
git commit -m "feat(server): HTML fallback page for the pairing link when the app isn't installed"
```

---

## Task 6: Android — `DeviceIdentity` (local deviceId bootstrap)

**Files:**
- Create: `.../watchparty/DeviceIdentity.kt`, test `DeviceIdentityTest.kt`

**Interfaces:**
```kotlin
class DeviceIdentity(prefs: SharedPreferences) {
    fun localDeviceId(): String?          // null until bootstrapped
    fun store(deviceId: String)
    fun hasDevice(): Boolean
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

// Uses a trivial in-memory SharedPreferences fake rather than a full Robolectric context,
// consistent with this codebase's existing pattern of testing pure logic against fakes.
class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, String?>()
    override fun getString(key: String?, def: String?) = map[key] ?: def
    override fun edit() = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { map[key] = value }
        override fun apply() {}
        override fun commit() = true
        // remaining Editor/SharedPreferences methods unused in this test — default no-ops/throws
        override fun putStringSet(k: String?, v: MutableSet<String>?) = this
        override fun putInt(k: String?, v: Int) = this
        override fun putLong(k: String?, v: Long) = this
        override fun putFloat(k: String?, v: Float) = this
        override fun putBoolean(k: String?, v: Boolean) = this
        override fun remove(k: String?) = this
        override fun clear() = this
    }
    override fun getAll() = mutableMapOf<String, Any?>()
    override fun getStringSet(k: String?, d: MutableSet<String>?) = d
    override fun getInt(k: String?, d: Int) = d
    override fun getLong(k: String?, d: Long) = d
    override fun getFloat(k: String?, d: Float) = d
    override fun getBoolean(k: String?, d: Boolean) = d
    override fun contains(k: String?) = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class DeviceIdentityTest {
    @Test fun no_device_initially() {
        val id = DeviceIdentity(FakePrefs())
        assertFalse(id.hasDevice())
        assertEquals(null, id.localDeviceId())
    }
    @Test fun store_then_read() {
        val id = DeviceIdentity(FakePrefs())
        id.store("abc123")
        assertTrue(id.hasDevice())
        assertEquals("abc123", id.localDeviceId())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.DeviceIdentityTest"`
Expected: FAIL — unresolved `DeviceIdentity`.

- [ ] **Step 3: Implement DeviceIdentity.kt**

```kotlin
package com.buddiestime.watchparty

import android.content.SharedPreferences

private const val KEY_DEVICE_ID = "device_id"

class DeviceIdentity(private val prefs: SharedPreferences) {
    fun localDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun hasDevice(): Boolean = localDeviceId() != null
    fun store(deviceId: String) { prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply() }
}
```

- [ ] **Step 4: Run to verify it passes; commit**

```bash
cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.DeviceIdentityTest"
git add android/app/src/main/kotlin/com/buddiestime/watchparty/DeviceIdentity.kt android/app/src/test/kotlin/com/buddiestime/watchparty/DeviceIdentityTest.kt
git commit -m "feat(android): DeviceIdentity local deviceId store"
```

---

## Task 7: Android — `QuietHours` pure logic

**Files:**
- Create: `.../watchparty/QuietHours.kt`, test `QuietHoursTest.kt`

**Interfaces:**
```kotlin
data class QuietHours(val startHour: Int, val endHour: Int) {
    fun isQuietAt(hour: Int): Boolean   // mirrors server pairing.js's isQuietNow, incl. midnight wrap
}
```
Used client-side only for showing "quiet hours active" hints in Settings — the actual suppression decision is server-side (Task 4) so the two must agree on semantics; this is a client mirror, not the source of truth.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test fun simple_range() {
        val q = QuietHours(23, 7)
        assertTrue(q.isQuietAt(0))
        assertTrue(q.isQuietAt(23))
        assertTrue(q.isQuietAt(6))
        assertFalse(q.isQuietAt(7))
        assertFalse(q.isQuietAt(12))
    }
    @Test fun non_wrapping_range() {
        val q = QuietHours(1, 5)
        assertTrue(q.isQuietAt(2))
        assertFalse(q.isQuietAt(0))
        assertFalse(q.isQuietAt(5))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.QuietHoursTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.buddiestime.watchparty

data class QuietHours(val startHour: Int, val endHour: Int) {
    fun isQuietAt(hour: Int): Boolean =
        if (startHour <= endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour
}
```

- [ ] **Step 4: Run to verify it passes; commit**

```bash
cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.QuietHoursTest"
git add android/app/src/main/kotlin/com/buddiestime/watchparty/QuietHours.kt android/app/src/test/kotlin/com/buddiestime/watchparty/QuietHoursTest.kt
git commit -m "feat(android): QuietHours pure range check"
```

---

## Task 8: Android — `PairingModels` (JSON parsing for the new REST surface)

**Files:**
- Create: `.../watchparty/PairingModels.kt`, test `PairingModelsTest.kt`

**Interfaces:**
```kotlin
data class Profile(val displayName: String, val petName: String?, val timezone: String?, val birthday: String?)
data class MessageEntry(val id: String, val text: String, val authorDeviceId: String, val createdAt: Long)
data class ThemeState(val mode: String, val value: String?, val setByDeviceId: String?, val setAt: Long?)
data class RoomView(
    val roomId: String, val roomName: String,
    val ownerDeviceId: String, val partnerDeviceId: String?,
    val theme: ThemeState, val nudgeMessages: List<MessageEntry>, val welcomeMessages: List<MessageEntry>,
)
fun parseRoomView(json: String): RoomView
fun parseProfile(json: JSONObject): Profile      // reused for herProfile/hisProfile in join responses
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingModelsTest {
    @Test fun parses_room_view() {
        val json = """{
            "roomId":"r1","roomName":"SonuKomal","ownerDeviceId":"d1","partnerDeviceId":"d2",
            "theme":{"mode":"auto","value":null,"setByDeviceId":null,"setAt":null},
            "nudgeMessages":[{"id":"m1","text":"come watch 😏","authorDeviceId":"d1","createdAt":100}],
            "welcomeMessages":[]
        }"""
        val room = parseRoomView(json)
        assertEquals("SonuKomal", room.roomName)
        assertEquals(1, room.nudgeMessages.size)
        assertEquals("come watch 😏", room.nudgeMessages[0].text)
        assertEquals("auto", room.theme.mode)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.PairingModelsTest"`
Expected: FAIL.

- [ ] **Step 3: Implement PairingModels.kt** (parsing mirrors the existing `RoomStatus.kt` style — `org.json`, tolerant `optX` reads)

```kotlin
package com.buddiestime.watchparty

import org.json.JSONObject

data class Profile(val displayName: String, val petName: String?, val timezone: String?, val birthday: String?)
data class MessageEntry(val id: String, val text: String, val authorDeviceId: String, val createdAt: Long)
data class ThemeState(val mode: String, val value: String?, val setByDeviceId: String?, val setAt: Long?)
data class RoomView(
    val roomId: String, val roomName: String,
    val ownerDeviceId: String, val partnerDeviceId: String?,
    val theme: ThemeState, val nudgeMessages: List<MessageEntry>, val welcomeMessages: List<MessageEntry>,
)

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

fun parseProfile(o: JSONObject): Profile = Profile(
    displayName = o.optString("displayName"),
    petName = o.optStringOrNull("petName"),
    timezone = o.optStringOrNull("timezone"),
    birthday = o.optStringOrNull("birthday"),
)

private fun parseMessages(arr: org.json.JSONArray?): List<MessageEntry> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        MessageEntry(o.optString("id"), o.optString("text"), o.optString("authorDeviceId"), o.optLong("createdAt"))
    }
}

fun parseRoomView(json: String): RoomView {
    val o = JSONObject(json)
    val t = o.getJSONObject("theme")
    return RoomView(
        roomId = o.optString("roomId"),
        roomName = o.optString("roomName"),
        ownerDeviceId = o.optString("ownerDeviceId"),
        partnerDeviceId = o.optStringOrNull("partnerDeviceId"),
        theme = ThemeState(t.optString("mode", "auto"), t.optStringOrNull("value"), t.optStringOrNull("setByDeviceId"), if (t.isNull("setAt")) null else t.optLong("setAt")),
        nudgeMessages = parseMessages(o.optJSONArray("nudgeMessages")),
        welcomeMessages = parseMessages(o.optJSONArray("welcomeMessages")),
    )
}
```

- [ ] **Step 4: Run to verify it passes; commit**

```bash
cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.PairingModelsTest"
git add android/app/src/main/kotlin/com/buddiestime/watchparty/PairingModels.kt android/app/src/test/kotlin/com/buddiestime/watchparty/PairingModelsTest.kt
git commit -m "feat(android): PairingModels — Profile/MessageEntry/ThemeState/RoomView parsing"
```

---

## Task 9: Android — `PairingApi` client

**Files:**
- Create: `.../watchparty/PairingApi.kt`

**Interfaces:** thin synchronous-callback OkHttp wrapper (same shape as `WatchPartyManager`'s existing use of OkHttp), one function per server route from Task 3/4/5:
```kotlin
class PairingApi(private val baseHttpUrl: String) {
    fun createDevice(cb: (String?) -> Unit)
    fun createRoom(deviceId: String, roomName: String, ownerProfile: JSONObject, partnerDraft: JSONObject, cb: (roomId: String?, error: String?) -> Unit)
    fun mintInvite(roomId: String, deviceId: String, pin: String?, cb: (token: String?, error: String?) -> Unit)
    fun joinRoom(roomId: String, deviceId: String, token: String, pin: String?, cb: (herProfile: Profile?, hisProfile: Profile?, error: String?) -> Unit)
    fun getRoom(roomId: String, deviceId: String, cb: (RoomView?, error: String?) -> Unit)
    fun setTheme(roomId: String, deviceId: String, mode: String, value: String?, cb: (Boolean) -> Unit)
    fun addMessage(roomId: String, deviceId: String, pool: String, text: String, cb: (Boolean) -> Unit)
    fun removeMessage(roomId: String, deviceId: String, pool: String, id: String, cb: (Boolean) -> Unit)
    fun updateProfile(deviceId: String, patch: JSONObject, cb: (Boolean) -> Unit)
    fun triggerNudge(roomId: String, deviceId: String, cb: (Boolean) -> Unit)
}
```
This class is a thin I/O wrapper (real network calls) — not unit-tested per this codebase's existing convention of not unit-testing OkHttp-calling classes directly (see `RecentRoomsStore`'s note about its thin wrapper). Verify it manually against the running server in Task 17's end-to-end pass.

- [ ] **Step 1: Implement PairingApi.kt** using `OkHttpClient` + `Request.Builder` + JSON bodies, following the exact request/response/logging style already used in `WatchPartyManager.kt` and `RoomsHomeActivity.kt`'s `refreshStatuses()` (enqueue + `okhttp3.Callback`, `Log.d(TAG, ...)` on every request/response, `runOnUiThread`-safe callback invocation left to the caller).

- [ ] **Step 2: Compile-check**

Run: `cd android && ./gradlew.bat compileDebugKotlin`
Expected: compiles clean.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/PairingApi.kt
git commit -m "feat(android): PairingApi OkHttp client for the pairing REST surface"
```

---

## Task 10: Android — `WelcomeSetupActivity` (owner-side onboarding)

**Files:**
- Create: `.../watchparty/WelcomeSetupActivity.kt` + `activity_welcome_setup.xml` (multi-step form: own profile → partner draft → room name → generate+share link)

**Responsibilities:**
1. On first launch with no `deviceId` (`DeviceIdentity.hasDevice() == false`): call `PairingApi.createDevice`, store it.
2. If device has no room yet: walk the owner through the setup fields from the design doc (§3.2) — his name/pet name, timezone (default IST, selectable), anniversary, quiet hours, his birthday, preferred starting theme; then her name/pet name draft; then a room-name field with reprompt-on-collision (submit → `createRoom` → on `room-name-taken` error, show inline error and let him retype, no auto-suggestion per the design decision).
3. On success: call `mintInvite` (with an optional PIN field first), build the link `https://<server>/pair/<roomId>/<token>`, and launch `Intent.ACTION_SEND` (share sheet) with that link as the text.
4. Route into `RoomsHomeActivity` afterward.

- [ ] **Step 1: Build the layout** — standard Material `TextInputLayout` fields across a few `ViewFlipper`/multi-screen steps (or simplest: a single scroll form with collapsible sections), following this codebase's existing Material dialog/form conventions (see `dialog_name_prompt.xml`, `dialog_join_party.xml`).

- [ ] **Step 2: Implement the Activity**, wiring each step's "Next"/"Create" button to the corresponding `PairingApi` call, with the same verbose `Log.d(TAG, ...)` logging style as `ServiceSelectorActivity`/`RoomsHomeActivity`.

- [ ] **Step 3: Manual verification** — run the app fresh (cleared data), confirm: device is created, room is created with a chosen name, a real invite link is produced, and the Android share sheet opens with that link. Retry room creation with a name already used by a prior test run and confirm the reprompt (no crash, no auto-suggestion).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/WelcomeSetupActivity.kt android/app/src/main/res/layout/activity_welcome_setup.xml
git commit -m "feat(android): WelcomeSetupActivity — owner onboarding, profile capture, invite link generation"
```

---

## Task 11: Android — `PairingRedeemActivity` (partner-side deep-link redemption)

**Files:**
- Create: `.../watchparty/PairingRedeemActivity.kt` + `activity_pairing_redeem.xml`

**Responsibilities:**
1. Registered (Task 17) against the `https://<server>/pair/{roomId}/{token}` intent filter.
2. On launch: extract `roomId`/`token` from the incoming `Intent.data`. If `DeviceIdentity.hasDevice() == false`, call `createDevice` first.
3. Call `PairingApi.joinRoom`. If the server indicates a PIN is required (surface this via a distinguishable error reason from Task 3's route, e.g. `pin-required` returned as a 428/409 with that reason — add this branch to `pairing.redeemInvite`'s contract in Task 2 if not already covered by testing it explicitly), show a PIN entry field and retry.
4. On success: persist `roomId` locally (mirrors `deviceId` storage — add `roomId` to `DeviceIdentity` or a sibling small store), store the returned `herProfile`/`hisProfile` locally for offline display, then route into `RoomsHomeActivity` — fully configured, no further prompts.
5. On `already-used`: show a clear, non-technical error ("This invite link has already been used.") with no retry path other than asking the partner to send a new one.
6. On `device-already-in-room` (this device already paired elsewhere): show a clear conflict message; do not silently overwrite.

- [ ] **Step 1: Build the layout** — a simple status/progress view plus the conditional PIN field and error states, styled consistently with the app's existing `MaterialAlertDialogBuilder` dialogs.

- [ ] **Step 2: Implement the Activity.**

- [ ] **Step 3: Manual verification** — using two emulator instances (or one emulator + the existing `test-page.html`/browser flow adapted), generate a link on device A, open it via `adb shell am start -a android.intent.action.VIEW -d "https://<server>/pair/<roomId>/<token>"` on device B, confirm device B self-configures with device A's entered profile data and both show as paired.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/PairingRedeemActivity.kt android/app/src/main/res/layout/activity_pairing_redeem.xml
git commit -m "feat(android): PairingRedeemActivity — deep-link invite redemption with PIN + error states"
```

---

## Task 12: Android manifest — intent filters, permissions, activity registration

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add the pairing-link intent filter to `PairingRedeemActivity`**

```xml
<activity
    android:name=".PairingRedeemActivity"
    android:exported="true">
    <intent-filter android:autoVerify="false">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="@string/pairing_host" android:pathPrefix="/pair/" />
    </intent-filter>
</activity>
```
(`autoVerify="false"` deliberately — per the design decision, this app skips verified Android App Links/Digital Asset Links since it isn't Play Store–listed; Android will still offer "Open in Watch Party" as a disambiguation choice when the app is installed, which is an acceptable UX for two people who both have the app.)

- [ ] **Step 2: Register `WelcomeSetupActivity` and `SettingsActivity`**, `POST_NOTIFICATIONS` permission, and the `FcmService`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
```xml
<service android:name=".FcmService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

- [ ] **Step 3: Change the launcher activity's role.** `RoomsHomeActivity` stays `LAUNCHER`/`MAIN`, but its own `onCreate` (Task 13) now branches: no device → route to `WelcomeSetupActivity`; device but no room → route to `WelcomeSetupActivity` (owner path) or wait-for-redeem state; device + room → show the normal paired home screen.

- [ ] **Step 4: Build-verify**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: builds clean, manifest merger has no conflicts.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): manifest wiring for pairing deep link, notifications, new activities"
```

---

## Task 13: Android — gut `RoomsHomeActivity` to the single-room model; delete `RecentRoomsStore`

**Files:**
- Modify: `.../watchparty/RoomsHomeActivity.kt` (remove multi-room UI, add pairing-state branching)
- Delete: `.../watchparty/RecentRoomsStore.kt`, `.../test/.../RecentRoomsStoreTest.kt`
- Modify: `android/app/src/main/res/layout/activity_rooms_home.xml` (remove search field, recents list, "Create Party" service-picker card; add pairing-status card + "Start Watching" + "Invite now" button)

**Responsibilities:**
- Remove: `etSearch`/`btnJoin`/recents `RecyclerView`/`refreshStatuses()` against `/api/rooms/status` (that status API served the *old* multi-room browsing use case, which no longer exists in this model — leave the server endpoint in place since it's harmless and untouched by this plan, just stop calling it from here).
- Add: on `onCreate`, check `DeviceIdentity`/local room state:
  - No device yet → `startActivity(WelcomeSetupActivity)`, `finish()`.
  - Device but no room → same (covers both "never started setup" and "mid-setup" cases; `WelcomeSetupActivity` itself should be idempotent about resuming from wherever the owner left off).
  - Device + room → fetch `PairingApi.getRoom`, show room name, partner name (or "waiting for your partner to join" if `partnerDeviceId` is null), a "Start Watching" button (→ `ServiceSelectorActivity`, unchanged), and an "Invite now" button (→ `PairingApi.triggerNudge`).
- Keep the existing love-note splash (`showLoveNoteSplash()`) but rewire its text source per Task 14 (from `FlirtyLines.kt` to the welcome-message pool) rather than deleting the splash mechanism itself.

- [ ] **Step 1: Delete `RecentRoomsStore.kt` and its test**, and remove every reference (`RecentRoomsStore`, `RecentRoom`, `mergeRecent`, `RecentRoomsCodec`) from `RoomsHomeActivity.kt` and `MainActivity.kt` (the latter's `recentRooms.add(...)` call in `connectToParty` goes away entirely).

- [ ] **Step 2: Rewrite `activity_rooms_home.xml`** — drop `etSearch`, `btnJoin`, `rvRecent`, `tvEmpty`; add a pairing-status `TextView`, a primary "Start Watching" button, and an "Invite now" button.

- [ ] **Step 3: Rewrite `RoomsHomeActivity.kt`'s `onCreate`/`onResume`** per the responsibilities above.

- [ ] **Step 4: Build-verify**

Run: `cd android && ./gradlew.bat assembleDebug testDebugUnitTest`
Expected: builds clean; no remaining references to deleted classes; JVM tests pass (post-deletion of `RecentRoomsStoreTest`).

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt android/app/src/main/res/layout/activity_rooms_home.xml
git rm android/app/src/main/kotlin/com/buddiestime/watchparty/RecentRoomsStore.kt android/app/src/test/kotlin/com/buddiestime/watchparty/RecentRoomsStoreTest.kt
git commit -m "refactor(android): single-room home screen, remove multi-room browse/search/recents"
```

---

## Task 14: Android — delete `Personalization.kt`; wire real paired profiles everywhere

**Files:**
- Delete: `.../watchparty/Personalization.kt`
- Modify: every call site referencing `Personalization.HER_NAME`/`Personalization.HIS_NAME` — `RoomsHomeActivity.kt` (greeting text, splash signature), `FlirtyLines.kt` → deleted (Task 15 covers its replacement), any other reference found via search.

**Responsibilities:** Introduce a small local cache (e.g. a `ProfileStore` object over `SharedPreferences`, storing the last-fetched `Profile` for self and partner as JSON, refreshed opportunistically from `PairingApi.getRoom`'s `hisProfile`/`herProfile`-equivalent data — actually sourced from the join response at pairing time and re-synced whenever `getRoom` is called) so the UI has names/pet-names available without a network round trip on every screen.

- [ ] **Step 1: Search for every remaining reference**

Run: `grep -rn "Personalization\." android/app/src/main/kotlin/`
Expected output before this task: hits in `RoomsHomeActivity.kt` (`tvGreeting`, splash signature) and `FlirtyLines.kt`.

- [ ] **Step 2: Add a minimal `ProfileStore`** (small enough to fold into `DeviceIdentity.kt` or its own file — prefer its own file for clarity) holding `selfProfile`/`partnerProfile` as cached `Profile` JSON, populated from the pairing/join responses and refreshable via `PairingApi.getRoom`.

- [ ] **Step 3: Rewire call sites** — e.g. `findViewById<TextView>(R.id.tvGreeting).text = "Hey ${profileStore.partnerProfile()?.petName ?: profileStore.partnerProfile()?.displayName ?: "there"} 💗"`, splash signature uses `profileStore.selfProfile()?.petName ?: selfProfile?.displayName`.

- [ ] **Step 4: Delete `Personalization.kt`**, build-verify no remaining references.

Run: `grep -rn "Personalization" android/app/src/main/kotlin/` → expect no results. Then `cd android && ./gradlew.bat assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src/main/kotlin/com/buddiestime/watchparty/
git rm android/app/src/main/kotlin/com/buddiestime/watchparty/Personalization.kt
git commit -m "refactor(android): remove hardcoded Personalization, wire real paired profiles"
```

---

## Task 15: Android — Welcome-message pool replaces `FlirtyLines`; theme reads `Room.theme`

**Files:**
- Delete: `.../watchparty/FlirtyLines.kt`
- Modify: `.../watchparty/RoomsHomeActivity.kt` (`showLoveNoteSplash()` sources its line from the fetched welcome-message pool, shown on app-open per the design decision, falling back to a small built-in default set if the pool is empty so a brand-new pairing isn't blank)
- Modify: `.../watchparty/FlufflesTheme.kt` (auto vs. manual mode from `Room.theme`)

**Responsibilities:**
- `showLoveNoteSplash()` (already gated to cold-start only, matching "app open only" from the design doc) picks a random entry from the cached `RoomView.welcomeMessages` authored by the *partner* (not the viewer's own messages) instead of calling the deleted `FlirtyLines.pick()`. If the pool is empty (fresh pairing, nobody's added one yet), fall back to a tiny built-in default list so the feature isn't silently blank on day one — this default list is **not** the old `FlirtyLines` pool restored, just 2–3 generic lines with no hardcoded names.
- `FlufflesTheme.apply(activity)` takes an optional `RoomView.theme` — when `mode == "manual"`, resolve `value` to the matching `Accent`; when `mode == "auto"` (or no room yet), keep today's exact date-based computation unchanged.

- [ ] **Step 1: Update `FlufflesTheme.kt`**

```kotlin
fun apply(activity: Activity, roomTheme: ThemeState? = null): Accent {
    val accent = if (roomTheme?.mode == "manual" && roomTheme.value != null) {
        accents.firstOrNull { it.label == roomTheme.value } ?: todaysAccent()
    } else todaysAccent()
    activity.theme.applyStyle(accent.overlay, true)
    Log.d(TAG, "apply: ${activity.javaClass.simpleName} ← accent=${accent.label} (mode=${roomTheme?.mode ?: "auto/no-room"})")
    return accent
}
```
(Existing `todaysAccent()` and the no-arg `apply(activity)` overload stay for call sites that run before a room is known, e.g. the very first `WelcomeSetupActivity` screen.)

- [ ] **Step 2: Rewire the splash in `RoomsHomeActivity.kt`**

```kotlin
private val defaultWelcomeLines = listOf("Hey, welcome back 💗", "Missed you 🎬", "Ready for movie night?")
private fun pickWelcomeLine(room: RoomView?): String {
    val pool = room?.welcomeMessages?.filter { it.authorDeviceId != deviceIdentity.localDeviceId() }
    return pool?.takeIf { it.isNotEmpty() }?.random()?.text ?: defaultWelcomeLines.random()
}
```
Call this instead of `FlirtyLines.pick()` inside `showLoveNoteSplash()`.

- [ ] **Step 3: Delete `FlirtyLines.kt`**, search for stragglers.

Run: `grep -rn "FlirtyLines" android/app/src/main/kotlin/` → expect no results after cleanup.

- [ ] **Step 4: Build-verify**

Run: `cd android && ./gradlew.bat assembleDebug testDebugUnitTest`

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src/main/kotlin/com/buddiestime/watchparty/
git rm android/app/src/main/kotlin/com/buddiestime/watchparty/FlirtyLines.kt
git commit -m "refactor(android): welcome-message pool replaces FlirtyLines; theme reads Room.theme"
```

---

## Task 16: Android — Firebase Cloud Messaging integration (`FcmService`)

**Files:**
- Modify: `android/build.gradle` (project-level: Google Services plugin), `android/app/build.gradle` (Firebase BOM + `firebase-messaging`, apply plugin)
- Create: `.../watchparty/FcmService.kt`
- Note: `android/app/google-services.json` must be added once a Firebase project exists (D2) — not generated by this task; document the placeholder requirement clearly if the file is absent so a build failure here reads as "add your Firebase config" rather than a mysterious Gradle error.

**Responsibilities:**
- `FcmService : FirebaseMessagingService`:
  - `onNewToken(token)` → `PairingApi.updateProfile(deviceId, fcmToken = token)`.
  - `onMessageReceived(message)` → build and show a `NotificationCompat` notification from `message.notification?.body` (the nudge text sent by the server), tapping it opens `MainActivity`/`RoomsHomeActivity` via a `PendingIntent`.
- Request `POST_NOTIFICATIONS` at a sensible moment (e.g. right after pairing completes, or when the user first taps "Invite now" / opens Settings' notification toggle) — not immediately on cold start, to avoid an unexplained permission prompt before the user understands what it's for.

- [ ] **Step 1: Add Gradle dependencies**

`android/build.gradle` (project-level, add to the existing `plugins`/`dependencies` block per whatever AGP version this project already pins):
```gradle
classpath 'com.google.gms:google-services:4.4.2'
```
`android/app/build.gradle`:
```gradle
apply plugin: 'com.google.gms.google-services'
dependencies {
    implementation platform('com.google.firebase:firebase-bom:33.5.1')
    implementation 'com.google.firebase:firebase-messaging'
    // ...existing dependencies unchanged
}
```

- [ ] **Step 2: Implement FcmService.kt**

```kotlin
package com.buddiestime.watchparty

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import org.json.JSONObject

private const val TAG = "HWP-FCM"
private const val CHANNEL_ID = "nudge"

class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.d(TAG, "onNewToken: $token")
        val prefs = getSharedPreferences("hwp_prefs", Context.MODE_PRIVATE)
        val deviceId = DeviceIdentity(prefs).localDeviceId() ?: return
        PairingApi(Config.baseHttpUrl()).updateProfile(deviceId, JSONObject().put("fcmToken", token)) { ok ->
            Log.d(TAG, "fcmToken update ok=$ok")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val body = message.notification?.body ?: message.data["body"] ?: return
        Log.d(TAG, "onMessageReceived body=\"$body\"")
        ensureChannel()
        val openIntent = Intent(this, RoomsHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Watch Party 💗")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification) // add a simple icon asset if one doesn't exist yet
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification)
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Watch party nudges", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
```
(Add a minimal `ic_notification` drawable if the project has none suitable — a simple vector heart/play icon is enough; check `res/drawable` first before creating a new one.)

- [ ] **Step 3: Request `POST_NOTIFICATIONS` at pairing-complete time** in `PairingRedeemActivity`/`WelcomeSetupActivity`'s success path (Android 13+ only; guard with `Build.VERSION.SDK_INT >= 33`), following the exact `ActivityCompat.requestPermissions`/`onRequestPermissionsResult` pattern `MainActivity.kt` already uses for `RECORD_AUDIO`.

- [ ] **Step 4: Build-verify**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: builds clean *once `google-services.json` is present* — if it's still a placeholder/missing, this step is blocked on D2 (Firebase project creation) and should be called out rather than silently skipped.

- [ ] **Step 5: Commit**

```bash
git add android/build.gradle android/app/build.gradle android/app/src/main/kotlin/com/buddiestime/watchparty/FcmService.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): FCM integration — token registration, nudge notification display"
```

---

## Task 17: Android — `SettingsActivity`

**Files:**
- Create: `.../watchparty/SettingsActivity.kt` + `activity_settings.xml`
- Modify: `android/app/src/main/AndroidManifest.xml` (register activity), add a way to reach it (e.g. an options-menu item on `RoomsHomeActivity`, following the existing `menu_main.xml`/`onOptionsItemSelected` pattern already used in `MainActivity` for "switch service")

**Responsibilities (all against the cached `RoomView` + live `PairingApi` calls):**
- **Pairing status**: room name, partner paired y/n, "Re-pair" button (calls `mintInvite` again → share sheet, same as initial setup — covers the device-loss-recovery decision).
- **Theme**: checkbox "Auto-rotate daily theme" (default reflects `room.theme.mode == "auto"`); unchecked reveals a theme picker (the 3 existing `FlufflesTheme` accents) whose selection calls `setTheme(mode="manual", value=...)`; re-checking calls `setTheme(mode="auto")`.
- **Welcome messages**: list of `room.welcomeMessages` (mine vs. partner's, labeled), add/remove via `addMessage`/`removeMessage` with `pool="welcome"`.
- **Nudge messages**: same UI shape, `pool="nudge"`.
- **Quiet hours**: two hour pickers, saved via `updateProfile(deviceId, quietHours = {...})`.
- **Own profile**: display name / pet name / timezone / birthday editor, saved via `updateProfile`.
- **Notification permission status** with a button to request it if not yet granted (Android 13+).

- [ ] **Step 1: Build the layout** — a scrollable settings screen with clearly separated sections, consistent Material styling with the rest of the app.

- [ ] **Step 2: Implement the Activity**, each control bound to the corresponding `PairingApi` call with the established `Log.d(TAG, ...)` logging on every action and result.

- [ ] **Step 3: Manual verification** — from one paired device, change the theme; confirm the *other* device reflects the change the next time it opens/reads room state (per the design decision, this does not require an active watch session). Add a welcome message on device A; confirm it shows on device B's next app-open splash. Add a nudge message on device A; confirm "Invite now" on device A delivers it to device B (device B backgrounded, to prove push actually reaches a closed app).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/SettingsActivity.kt android/app/src/main/res/layout/activity_settings.xml android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): SettingsActivity — theme, message pools, quiet hours, profile, re-pair"
```

---

## Task 18: Android — manual "Invite now" button wiring

**Files:**
- Modify: `.../watchparty/RoomsHomeActivity.kt` (button added in Task 13's layout rewrite) and/or `.../watchparty/MainActivity.kt` if you also want it reachable mid-session (recommended — inviting *while already in the room alone* is a very natural moment to nudge)

- [ ] **Step 1: Wire the button** to `PairingApi.triggerNudge(roomId, deviceId) { ok -> ... }`, showing a `Toast` for both outcomes ("Nudge sent 💌" / a quiet, non-alarming failure message — don't surface "quiet-hours"/"no-push-token" as scary errors, just a soft "couldn't reach them right now").

- [ ] **Step 2: Manual verification** — tap it with the partner device backgrounded and out of quiet hours; confirm the push notification arrives and tapping it opens the app.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt
git commit -m "feat(android): manual invite-now nudge button"
```

---

## Task 19: Cleanup pass + full regression

**Files:** repo-wide search, whatever turns up.

- [ ] **Step 1: Search for dead references** to everything deleted in this plan:

```bash
grep -rn "Personalization\|FlirtyLines\|RecentRoomsStore\|RecentRoom(" android/app/src/main/kotlin/ android/app/src/test/kotlin/
```
Expected: no results. Fix any stragglers found.

- [ ] **Step 2: Remove the now-unused name-prompt dialog path in `ServiceSelectorActivity.kt`** (`promptForName`, `KEY_NAME` reads) — the display name now comes from the paired `Profile`, never asked ad hoc. Keep the service-picker cards (`cardHotstar`/`cardNetflix`/etc.) untouched; only the name-gating logic goes.

- [ ] **Step 3: Full server test suite**

Run: `cd server && node --test`
Expected: PASS — every existing test (rooms/keepalive/etc.) plus every test added in this plan.

- [ ] **Step 4: Full Android JVM test suite**

Run: `cd android && ./gradlew.bat testDebugUnitTest`
Expected: PASS — every existing test (`BackoffPolicyTest`, `ConfigTest`, `RoomStatusTest`, `ChatOverlayLogicTest`) plus every test added in this plan; `RecentRoomsStoreTest` gone (deleted, not failing).

- [ ] **Step 5: Full Android build**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: builds clean.

- [ ] **Step 6: End-to-end manual pass on two emulators/devices** (see `TESTING.md` for the existing two-browser/emulator pattern, adapted here to two paired phones):
  - Fresh install A → `WelcomeSetupActivity` → generate link.
  - Fresh install B → open link → auto-configured, no prompts.
  - A starts a video → B gets an automatic push nudge (B backgrounded).
  - A taps "Invite now" → B gets a manual push nudge.
  - B sets a theme in Settings → A's UI reflects it on next open, without an active watch session.
  - A adds a welcome message → B sees it on next app-open splash.
  - Reinstall B (simulating device loss) → A generates a fresh invite from Settings' re-pair action → B redeems it → same room, history/messages intact.
  - Attempt to reuse the very first invite link from Task 10 → clear "already used" error.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "chore: cleanup pass — remove dead onboarding-name-prompt path, verify full regression"
```

---

## Explicitly out of scope for this plan (per design doc §8)

- Chrome extension, bookmarklet, `room.html` web client.
- LiveKit voice chat internals (untouched, just keeps working alongside the new pairing model).
- Any non-Android pairing flow.
- Hardening the `X-Device-Id`-as-bearer-credential auth model beyond what's described (D4) — acceptable for this app's two-person, non-adversarial threat model.
