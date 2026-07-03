# Persistent Rooms + Reliable Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Watch Party rooms persistent (5-min empty grace), host-driven (auto host-promotion + 5s sync), and the Android client resilient (wake-on-cold-start + auto-reconnect), fronted by a new home screen with recent rooms + search.

**Architecture:** The Node WS server keeps rooms in-memory but stops destroying them the instant they empty; it exposes a small HTTP status API and auto-promotes a new host when the host leaves. The Android app gains a launcher home screen (Create button + join/search field + recent-rooms list), a hardened `WatchPartyManager` that wakes the server via `/health` then auto-reconnects with exponential backoff, and testable pure-Kotlin helpers for backoff, recents storage, and status parsing.

**Tech Stack:** Node.js (`ws`, built-in `http`), Android (Kotlin, OkHttp 4.12, Material 1.11, `org.json`), JUnit 4 for JVM logic tests, `node:test`/`node:assert` for server tests.

## Global Constraints

- Target Android app + `server/server.js` only. Do NOT touch `extension/`, `bookmarklet/`, `server/room.html`, `server/test-page.html`.
- Server stays in-memory — no database, no new npm deps.
- Production server URL (baked into Android): `wss://buddiestime-watch-party.onrender.com`. Health URL derived by swapping scheme to `https` + `/health`.
- Room empty-grace default: 300000 ms, overridable via env `ROOM_GRACE_MS`.
- Server keep-alive ping default: 30000 ms, overridable via env `PING_INTERVAL_MS`.
- Host periodic sync cadence: 5000 ms (was 2000 ms).
- Android package: `com.buddiestime.watchparty`. Kotlin source root: `android/app/src/main/kotlin/com/buddiestime/watchparty/`. JVM tests: `android/app/src/test/kotlin/com/buddiestime/watchparty/`.
- Preserve the existing verbose logging style (`Log.d(TAG, ...)` on Android, `console.log('[...]')` on server) — log every branch, WS message, and state transition.
- Existing WS protocol messages must remain backward-compatible; only additive changes.
- Build the Android app with `cd android && ./gradlew.bat` (Windows). Run JVM tests with `./gradlew.bat testDebugUnitTest`.

---

## File Structure

**Server (modify):**

- `server/server.js` — room lifecycle (grace TTL), host-promotion, `/api/rooms/status` + `/api/room/:id`, ping sweep. Extract room-lifecycle helpers so they're unit-testable.

**Server (create):**

- `server/test/harness.js` — spawns the server on a test port with env overrides, waits for `/health`, returns `{ baseUrl, wsUrl, stop() }`.
- `server/test/rooms.test.js` — grace-TTL + host-promotion + status-API tests (`node --test`).
- `server/test/keepalive.test.js` — dead-socket termination test.

**Android (create):**

- `.../watchparty/Config.kt` — `object Config { SERVER_URL, healthUrl(), effectiveServerUrl(prefs) }`.
- `.../watchparty/BackoffPolicy.kt` — pure reconnect-delay calculator.
- `.../watchparty/RecentRoomsStore.kt` — SharedPreferences-backed recents (serialize/parse/add/dedupe).
- `.../watchparty/RoomStatus.kt` — data class + `parseRoomStatusList(json): List<RoomStatus>`.
- `.../watchparty/RoomsHomeActivity.kt` — launcher home screen.
- `android/app/src/main/res/layout/activity_rooms_home.xml` — home layout.
- `android/app/src/main/res/layout/item_recent_room.xml` — one recents row.
- JVM tests: `BackoffPolicyTest.kt`, `RecentRoomsStoreTest.kt`, `RoomStatusTest.kt`.

**Android (modify):**

- `.../watchparty/WatchPartyManager.kt` — wake-then-connect, longer timeouts, auto-reconnect, `role` message handling.
- `.../watchparty/MainActivity.kt` — 2000→5000 cadence in `SYNC_SCRIPT`, handle promotion `role`, record recents on connect, use `Config` URL.
- `.../watchparty/ServiceSelectorActivity.kt` — becomes the "Create Party → pick service" step (reached from home), returns to home on back.
- `android/app/src/main/AndroidManifest.xml` — `RoomsHomeActivity` becomes LAUNCHER; `ServiceSelectorActivity` no longer LAUNCHER.

---

## Task 1: Server — room empty-grace TTL (stop destroying rooms instantly)

**Files:**

- Modify: `server/server.js` (connection `close` handler ~317-343; add helpers near top)
- Create: `server/test/harness.js`
- Create: `server/test/rooms.test.js`

**Interfaces:**

- Consumes: existing `rooms`, `clientsById`, `roomState` maps.
- Produces:
  - `const ROOM_GRACE_MS = Number(process.env.ROOM_GRACE_MS) || 300000`
  - `roomGraceTimers` — `Map<roomId, NodeJS.Timeout>`
  - `function scheduleRoomExpiry(roomId)` — starts/replaces a grace timer that deletes `rooms`/`clientsById`/`roomState`/timer entry on expiry; sets `roomState.get(roomId).emptySince = Date.now()`.
  - `function cancelRoomExpiry(roomId)` — clears + deletes the timer if present.
  - Harness `startServer({ port, env }) → Promise<{ baseUrl, wsUrl, stop }>`.

- [ ] **Step 1: Write the test harness**

Create `server/test/harness.js`:

```js
const { spawn } = require("child_process");
const path = require("path");
const http = require("http");

function waitForHealth(baseUrl, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      const req = http.get(baseUrl + "/health", (res) => {
        res.resume();
        if (res.statusCode === 200) return resolve();
        retry();
      });
      req.on("error", retry);
    };
    const retry = () =>
      Date.now() > deadline
        ? reject(new Error("health timeout"))
        : setTimeout(tick, 100);
    tick();
  });
}

async function startServer({ port = 8099, env = {} } = {}) {
  const serverPath = path.join(__dirname, "..", "server.js");
  const child = spawn(process.execPath, [serverPath], {
    env: { ...process.env, PORT: String(port), ...env },
    stdio: ["ignore", "ignore", "inherit"],
  });
  const baseUrl = `http://localhost:${port}`;
  await waitForHealth(baseUrl);
  return {
    baseUrl,
    wsUrl: `ws://localhost:${port}`,
    stop: () =>
      new Promise((r) => {
        child.once("exit", () => r());
        child.kill("SIGKILL");
      }),
  };
}

module.exports = { startServer };
```

- [ ] **Step 2: Write the failing grace-TTL test**

Create `server/test/rooms.test.js`:

```js
const { test } = require("node:test");
const assert = require("node:assert");
const WebSocket = require("ws");
const { startServer } = require("./harness");

function joinRoom(
  wsUrl,
  { roomId, clientId, displayName, videoUrl, platform },
) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    ws.on("open", () =>
      ws.send(
        JSON.stringify({
          type: "join",
          roomId,
          clientId,
          displayName,
          videoUrl,
          platform,
        }),
      ),
    );
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw);
      if (msg.type === "joined") resolve({ ws, joined: msg });
    });
    ws.on("error", reject);
  });
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test("room survives empty for the grace window and keeps its video state", async () => {
  const srv = await startServer({ port: 8091, env: { ROOM_GRACE_MS: "1500" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "R1",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });
    // host pushes a timestamp then leaves
    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 42,
        paused: false,
        videoUrl: "hotstar.com/x",
      }),
    );
    await wait(100);
    a.ws.close();
    await wait(300); // within grace

    const b = await joinRoom(srv.wsUrl, {
      roomId: "R1",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "about:blank",
      platform: "hotstar",
    });
    assert.strictEqual(
      b.joined.videoUrl,
      "hotstar.com/x",
      "rejoiner inherits room video",
    );
    assert.ok(b.joined.time >= 42, "rejoiner inherits room timestamp");
    b.ws.close();
  } finally {
    await srv.stop();
  }
});

test("room is gone after the grace window expires", async () => {
  const srv = await startServer({ port: 8092, env: { ROOM_GRACE_MS: "600" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "R2",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "hotstar.com/y",
      platform: "hotstar",
    });
    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 99,
        paused: true,
        videoUrl: "hotstar.com/y",
      }),
    );
    await wait(100);
    a.ws.close();
    await wait(900); // past grace

    const b = await joinRoom(srv.wsUrl, {
      roomId: "R2",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "about:blank",
      platform: "hotstar",
    });
    assert.strictEqual(b.joined.role, "host", "fresh room → new host");
    assert.strictEqual(
      b.joined.time,
      0,
      "expired room did not keep old timestamp",
    );
    b.ws.close();
  } finally {
    await srv.stop();
  }
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd server && node --test test/rooms.test.js`
Expected: FAIL — the first test fails because on empty the room is deleted (rejoiner gets a fresh room with no video/time).

- [ ] **Step 4: Implement grace TTL in server.js**

Near the other module constants (after `const VOICE_CAP = 4;`), add:

```js
const ROOM_GRACE_MS = Number(process.env.ROOM_GRACE_MS) || 300000;
const roomGraceTimers = new Map(); // roomId → timeout
```

Add helpers just below `broadcast(...)` (before the `wss.on('connection'` block):

```js
function cancelRoomExpiry(roomId) {
  const t = roomGraceTimers.get(roomId);
  if (t) {
    clearTimeout(t);
    roomGraceTimers.delete(roomId);
    console.log(`[${roomId}] grace timer cancelled`);
  }
}

function scheduleRoomExpiry(roomId) {
  cancelRoomExpiry(roomId);
  const state = roomState.get(roomId);
  if (state) state.emptySince = Date.now();
  console.log(
    `[${roomId}] room empty — scheduling expiry in ${ROOM_GRACE_MS}ms`,
  );
  const timer = setTimeout(() => {
    rooms.delete(roomId);
    clientsById.delete(roomId);
    roomState.delete(roomId);
    roomGraceTimers.delete(roomId);
    console.log(`[${roomId}] grace elapsed — Room DESTROYED`);
  }, ROOM_GRACE_MS);
  roomGraceTimers.set(roomId, timer);
}
```

In the `join` handler, cancel any pending expiry when someone (re)joins. Immediately after `const room = rooms.get(roomId);` (currently server.js:198), add:

```js
cancelRoomExpiry(roomId);
```

Replace the empty-room branch in the `close` handler (currently server.js:331-335):

```js
    if (room.size === 0) {
      scheduleRoomExpiry(roomId);
      console.log(`[${roomId}] now empty — kept alive for grace window`);
    } else {
```

- [ ] **Step 5: Run to verify both tests pass**

Run: `cd server && node --test test/rooms.test.js`
Expected: PASS (2 tests) — the "gone after expiry" test also passes because the 600ms grace elapses before the rejoin.

- [ ] **Step 6: Commit**

```bash
git add server/server.js server/test/harness.js server/test/rooms.test.js
git commit -m "feat(server): keep rooms alive for a 5-min empty grace window"
```

---

## Task 2: Server — auto-promote a new host when the host leaves

**Files:**

- Modify: `server/server.js` (`close` handler, non-empty branch)
- Modify: `server/test/rooms.test.js` (add test)

**Interfaces:**

- Consumes: `rooms` (Map<ws,{role,id,name,voice}>), `broadcastToAll`, `broadcastParticipants`.
- Produces: new server→client message `{ type: 'role', role: 'host'|'guest', clientId }` sent to the promoted client; `function promoteNewHost(roomId)` returning the promoted info or null.

- [ ] **Step 1: Write the failing host-promotion test**

Append to `server/test/rooms.test.js`:

```js
test("oldest remaining member is promoted to host when host leaves", async () => {
  const srv = await startServer({ port: 8093, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "R3",
      clientId: "host1",
      displayName: "Ann",
      videoUrl: "hotstar.com/z",
      platform: "hotstar",
    });
    assert.strictEqual(a.joined.role, "host");
    const b = await joinRoom(srv.wsUrl, {
      roomId: "R3",
      clientId: "guest1",
      displayName: "Bob",
      videoUrl: "hotstar.com/z",
      platform: "hotstar",
    });
    assert.strictEqual(b.joined.role, "guest");

    const promoted = new Promise((resolve) => {
      b.ws.on("message", (raw) => {
        const m = JSON.parse(raw);
        if (m.type === "role") resolve(m);
      });
    });
    a.ws.close(); // host leaves
    const roleMsg = await promoted;
    assert.strictEqual(roleMsg.role, "host");
    assert.strictEqual(roleMsg.clientId, "guest1");
    b.ws.close();
  } finally {
    await srv.stop();
  }
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/rooms.test.js`
Expected: FAIL — no `role` message is ever sent; the promise never resolves and the test times out.

- [ ] **Step 3: Implement promotion**

Add helper next to the grace helpers:

```js
function promoteNewHost(roomId) {
  const room = rooms.get(roomId);
  if (!room || room.size === 0) return null;
  const alreadyHost = [...room.values()].some((c) => c.role === "host");
  if (alreadyHost) return null;
  const [ws, info] = [...room.entries()][0]; // oldest surviving member (insertion order)
  info.role = "host";
  console.log(`[${roomId}] promoting ${info.id}(${info.name}) → host`);
  if (ws.readyState === 1)
    ws.send(JSON.stringify({ type: "role", role: "host", clientId: info.id }));
  return info;
}
```

In the `close` handler's non-empty `else` branch (currently server.js:336-342), call it before broadcasting participants:

```js
    } else {
      if (leaving?.role === 'host') {
        console.log(`[${roomId}] host left — promoting a replacement`);
        promoteNewHost(roomId);
      }
      broadcastParticipants(roomId, 'member-left');
      if (leaving?.voice) {
        console.log(`[${roomId}]   leaver was in voice — broadcasting voice-participants`);
        broadcastVoiceParticipants(roomId, 'member-left');
      }
    }
```

- [ ] **Step 4: Run to verify all tests pass**

Run: `cd server && node --test test/rooms.test.js`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add server/server.js server/test/rooms.test.js
git commit -m "feat(server): auto-promote oldest member to host when host leaves"
```

---

## Task 3: Server — room status HTTP API

**Files:**

- Modify: `server/server.js` (HTTP request handler, before the static-routes block ~44)
- Modify: `server/test/rooms.test.js` (add test)

**Interfaces:**

- Consumes: `rooms`, `roomState`.
- Produces:
  - `GET /api/rooms/status?ids=a,b,c` → `200` JSON `{ rooms: [{ roomId, active, count, platform, videoUrl, title }] }`. `active` = room exists (members present OR within grace). `count` = current member count.
  - `GET /api/room/:id` → `200` JSON `{ roomId, active, count, platform, videoUrl, title }` or `404 {error:'not-found'}`.
  - `function roomStatus(roomId)` returning the status object or null.

- [ ] **Step 1: Write the failing status-API test**

Append to `server/test/rooms.test.js`:

```js
const http = require("node:http");
function getJson(url) {
  return new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let body = "";
        res.on("data", (d) => (body += d));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            json: body ? JSON.parse(body) : null,
          }),
        );
      })
      .on("error", reject);
  });
}

test("status API reports active rooms with counts and video", async () => {
  const srv = await startServer({ port: 8094, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "S1",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "hotstar.com/live",
      platform: "hotstar",
    });
    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 10,
        paused: false,
        videoUrl: "hotstar.com/live",
      }),
    );
    await wait(100);

    const list = await getJson(srv.baseUrl + "/api/rooms/status?ids=S1,NOPE");
    assert.strictEqual(list.status, 200);
    const s1 = list.json.rooms.find((r) => r.roomId === "S1");
    assert.strictEqual(s1.active, true);
    assert.strictEqual(s1.count, 1);
    assert.strictEqual(s1.videoUrl, "hotstar.com/live");
    const nope = list.json.rooms.find((r) => r.roomId === "NOPE");
    assert.strictEqual(nope.active, false);
    assert.strictEqual(nope.count, 0);

    const single = await getJson(srv.baseUrl + "/api/room/S1");
    assert.strictEqual(single.status, 200);
    assert.strictEqual(single.json.platform, "hotstar");

    const missing = await getJson(srv.baseUrl + "/api/room/GHOST");
    assert.strictEqual(missing.status, 404);

    a.ws.close();
  } finally {
    await srv.stop();
  }
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/rooms.test.js`
Expected: FAIL — `/api/rooms/status` currently hits the 404 fallback.

- [ ] **Step 3: Implement the endpoints**

Add helper near `participantsList(...)`:

```js
function roomStatus(roomId) {
  const state = roomState.get(roomId);
  if (!state)
    return {
      roomId,
      active: false,
      count: 0,
      platform: null,
      videoUrl: null,
      title: null,
    };
  const room = rooms.get(roomId);
  return {
    roomId,
    active: true,
    count: room ? room.size : 0,
    platform: state.platform || null,
    videoUrl: state.videoUrl || null,
    title: state.title || null,
  };
}
```

In the HTTP handler, add these branches right after the `/health` block (server.js:32):

```js
if (url.pathname === "/api/rooms/status") {
  const ids = (url.searchParams.get("ids") || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  console.log(`[HTTP]   → /api/rooms/status ids=[${ids.join(",")}]`);
  const payload = { rooms: ids.map(roomStatus) };
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
  return;
}

if (url.pathname.startsWith("/api/room/")) {
  const id = decodeURIComponent(url.pathname.slice("/api/room/".length));
  console.log(`[HTTP]   → /api/room/${id}`);
  if (!roomState.has(id)) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not-found" }));
    return;
  }
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify(roomStatus(id)));
  return;
}
```

- [ ] **Step 4: Run to verify all tests pass**

Run: `cd server && node --test test/rooms.test.js`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add server/server.js server/test/rooms.test.js
git commit -m "feat(server): add /api/rooms/status and /api/room/:id status endpoints"
```

---

## Task 4: Server — keep-alive ping sweep

**Files:**

- Modify: `server/server.js` (after `wss` is created ~60)
- Create: `server/test/keepalive.test.js`

**Interfaces:**

- Consumes: `wss` (WebSocketServer).
- Produces: `const PING_INTERVAL_MS = Number(process.env.PING_INTERVAL_MS) || 30000`; each socket tagged `ws.isAlive`; `pong` handler resets it; interval terminates sockets that didn't pong.

- [ ] **Step 1: Write the failing keep-alive test**

Create `server/test/keepalive.test.js`:

```js
const { test } = require("node:test");
const assert = require("node:assert");
const WebSocket = require("ws");
const { startServer } = require("./harness");

test("a socket that never pongs is terminated by the sweep", async () => {
  const srv = await startServer({
    port: 8095,
    env: { PING_INTERVAL_MS: "300", ROOM_GRACE_MS: "5000" },
  });
  try {
    const ws = new WebSocket(srv.wsUrl);
    // Disable automatic pong so the server marks us dead.
    ws.on("ping", () => {}); // swallow
    ws._receiver && (ws._receiver._pingCb = null);
    await new Promise((r) => ws.on("open", r));
    ws.pong = () => {}; // never actually pong
    const closed = new Promise((resolve) =>
      ws.on("close", () => resolve(true)),
    );
    const result = await Promise.race([
      closed,
      new Promise((r) => setTimeout(() => r(false), 2000)),
    ]);
    assert.strictEqual(
      result,
      true,
      "server should have terminated the pong-less socket",
    );
  } finally {
    await srv.stop();
  }
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && node --test test/keepalive.test.js`
Expected: FAIL — no sweep exists, socket stays open, race resolves `false`.

- [ ] **Step 3: Implement the sweep**

Add constant with the others:

```js
const PING_INTERVAL_MS = Number(process.env.PING_INTERVAL_MS) || 30000;
```

Immediately after `const wss = new WebSocketServer({ server: httpServer });` (server.js:60), add:

```js
const keepAlive = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      console.log("[WS] terminating dead socket (no pong)");
      return ws.terminate();
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch {}
  });
}, PING_INTERVAL_MS);
wss.on("close", () => clearInterval(keepAlive));
```

Inside `wss.on('connection', (ws, req) => {` (server.js:156), right after the opening line, add:

```js
ws.isAlive = true;
ws.on("pong", () => {
  ws.isAlive = true;
});
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd server && node --test test/keepalive.test.js`
Expected: PASS. Also re-run `node --test test/rooms.test.js` → still PASS (4).

- [ ] **Step 5: Commit**

```bash
git add server/server.js server/test/keepalive.test.js
git commit -m "feat(server): ping sweep terminates dead/half-open sockets"
```

---

## Task 5: Android — Config object (baked-in server URL)

**Files:**

- Create: `android/app/src/main/kotlin/com/buddiestime/watchparty/Config.kt`

**Interfaces:**

- Produces:
  - `object Config`
  - `const val SERVER_URL = "wss://buddiestime-watch-party.onrender.com"`
  - `fun healthUrl(wsUrl: String = SERVER_URL): String` — swaps `wss://`→`https://`, `ws://`→`http://`, appends `/health`.
  - `fun effectiveServerUrl(override: String?): String` — returns a non-blank override else `SERVER_URL`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/com/buddiestime/watchparty/ConfigTest.kt`:

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigTest {
    @Test fun healthUrl_swaps_scheme_and_appends_health() {
        assertEquals("https://buddiestime-watch-party.onrender.com/health", Config.healthUrl("wss://buddiestime-watch-party.onrender.com"))
        assertEquals("http://localhost:8080/health", Config.healthUrl("ws://localhost:8080"))
    }
    @Test fun effectiveServerUrl_prefers_nonblank_override() {
        assertEquals("ws://10.0.2.2:8080", Config.effectiveServerUrl("ws://10.0.2.2:8080"))
        assertEquals(Config.SERVER_URL, Config.effectiveServerUrl(null))
        assertEquals(Config.SERVER_URL, Config.effectiveServerUrl("   "))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.ConfigTest"`
Expected: FAIL — `Config` unresolved / compile error.

- [ ] **Step 3: Implement Config.kt**

```kotlin
package com.buddiestime.watchparty

object Config {
    const val SERVER_URL = "wss://buddiestime-watch-party.onrender.com"

    fun healthUrl(wsUrl: String = SERVER_URL): String {
        val http = wsUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        return http.trimEnd('/') + "/health"
    }

    fun effectiveServerUrl(override: String?): String =
        override?.trim().takeUnless { it.isNullOrEmpty() } ?: SERVER_URL
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.ConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/Config.kt android/app/src/test/kotlin/com/buddiestime/watchparty/ConfigTest.kt
git commit -m "feat(android): Config with baked-in server URL + health-url helper"
```

---

## Task 6: Android — BackoffPolicy (pure reconnect timing)

**Files:**

- Create: `.../watchparty/BackoffPolicy.kt`
- Create: test `.../test/.../BackoffPolicyTest.kt`

**Interfaces:**

- Produces:
  - `class BackoffPolicy(val baseMs: Long = 1000, val maxMs: Long = 15000)`
  - `fun delayFor(attempt: Int): Long` — attempt 1→base, doubling, capped at `maxMs`; attempt ≤0 treated as 1.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/com/buddiestime/watchparty/BackoffPolicyTest.kt`:

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffPolicyTest {
    private val p = BackoffPolicy(baseMs = 1000, maxMs = 15000)
    @Test fun doubles_from_base() {
        assertEquals(1000, p.delayFor(1))
        assertEquals(2000, p.delayFor(2))
        assertEquals(4000, p.delayFor(3))
        assertEquals(8000, p.delayFor(4))
    }
    @Test fun caps_at_max() {
        assertEquals(15000, p.delayFor(5))
        assertEquals(15000, p.delayFor(50))
    }
    @Test fun guards_low_attempts() {
        assertEquals(1000, p.delayFor(0))
        assertEquals(1000, p.delayFor(-3))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.BackoffPolicyTest"`
Expected: FAIL — unresolved `BackoffPolicy`.

- [ ] **Step 3: Implement BackoffPolicy.kt**

```kotlin
package com.buddiestime.watchparty

class BackoffPolicy(private val baseMs: Long = 1000, private val maxMs: Long = 15000) {
    fun delayFor(attempt: Int): Long {
        val n = if (attempt < 1) 1 else attempt
        var d = baseMs
        repeat(n - 1) { d = (d * 2).coerceAtMost(maxMs) }
        return d.coerceAtMost(maxMs)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.BackoffPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/BackoffPolicy.kt android/app/src/test/kotlin/com/buddiestime/watchparty/BackoffPolicyTest.kt
git commit -m "feat(android): BackoffPolicy for exponential reconnect delays"
```

---

## Task 7: Android — RoomStatus parsing

**Files:**

- Create: `.../watchparty/RoomStatus.kt`
- Create: test `.../test/.../RoomStatusTest.kt`

**Interfaces:**

- Produces:
  - `data class RoomStatus(val roomId: String, val active: Boolean, val count: Int, val platform: String?, val videoUrl: String?, val title: String?)`
  - `fun parseRoomStatusList(json: String): List<RoomStatus>` — parses the `{ "rooms": [...] }` body from `/api/rooms/status`; tolerates missing fields.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/com/buddiestime/watchparty/RoomStatusTest.kt`:

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStatusTest {
    @Test fun parses_rooms_array() {
        val json = """{"rooms":[
            {"roomId":"S1","active":true,"count":2,"platform":"hotstar","videoUrl":"hotstar.com/x","title":"Match"},
            {"roomId":"S2","active":false,"count":0}
        ]}"""
        val list = parseRoomStatusList(json)
        assertEquals(2, list.size)
        assertEquals("S1", list[0].roomId)
        assertTrue(list[0].active)
        assertEquals(2, list[0].count)
        assertEquals("hotstar", list[0].platform)
        assertFalse(list[1].active)
        assertEquals(null, list[1].platform)
    }
    @Test fun tolerates_empty() {
        assertEquals(0, parseRoomStatusList("""{"rooms":[]}""").size)
        assertEquals(0, parseRoomStatusList("{}").size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.RoomStatusTest"`
Expected: FAIL — unresolved `parseRoomStatusList`.

- [ ] **Step 3: Implement RoomStatus.kt**

```kotlin
package com.buddiestime.watchparty

import org.json.JSONObject

data class RoomStatus(
    val roomId: String,
    val active: Boolean,
    val count: Int,
    val platform: String?,
    val videoUrl: String?,
    val title: String?,
)

fun parseRoomStatusList(json: String): List<RoomStatus> {
    val root = JSONObject(json)
    val arr = root.optJSONArray("rooms") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        RoomStatus(
            roomId = o.optString("roomId"),
            active = o.optBoolean("active", false),
            count = o.optInt("count", 0),
            platform = o.optString("platform", null.toString()).takeIf { o.has("platform") && !o.isNull("platform") },
            videoUrl = if (o.has("videoUrl") && !o.isNull("videoUrl")) o.optString("videoUrl") else null,
            title = if (o.has("title") && !o.isNull("title")) o.optString("title") else null,
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.RoomStatusTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/RoomStatus.kt android/app/src/test/kotlin/com/buddiestime/watchparty/RoomStatusTest.kt
git commit -m "feat(android): RoomStatus model + status-list JSON parser"
```

---

## Task 8: Android — RecentRoomsStore (SharedPreferences)

**Files:**

- Create: `.../watchparty/RecentRoomsStore.kt`
- Create: test `.../test/.../RecentRoomsStoreTest.kt`

**Interfaces:**

- Produces:
  - `data class RecentRoom(val roomId: String, val platform: String, val videoUrl: String, val lastJoined: Long)`
  - `object RecentRoomsCodec` with `fun encode(list: List<RecentRoom>): String` and `fun decode(json: String): List<RecentRoom>` (JSON array). Pure — unit-tested.
  - `fun mergeRecent(existing: List<RecentRoom>, room: RecentRoom, max: Int = 20): List<RecentRoom>` — dedupes by `roomId` (newest wins), sorts by `lastJoined` desc, caps at `max`.
  - `class RecentRoomsStore(prefs: SharedPreferences)` with `fun add(room: RecentRoom)`, `fun all(): List<RecentRoom>` (thin wrapper over codec+merge; verified at build/manual — not unit-tested to avoid Android framework mocks).

- [ ] **Step 1: Write the failing test (pure codec + merge only)**

Create `android/app/src/test/kotlin/com/buddiestime/watchparty/RecentRoomsStoreTest.kt`:

```kotlin
package com.buddiestime.watchparty

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentRoomsStoreTest {
    @Test fun encode_then_decode_roundtrips() {
        val list = listOf(
            RecentRoom("abc", "hotstar", "hotstar.com/x", 100),
            RecentRoom("def", "netflix", "netflix.com/watch/1", 200),
        )
        val decoded = RecentRoomsCodec.decode(RecentRoomsCodec.encode(list))
        assertEquals(list, decoded)
    }
    @Test fun decode_tolerates_garbage() {
        assertEquals(emptyList<RecentRoom>(), RecentRoomsCodec.decode(""))
        assertEquals(emptyList<RecentRoom>(), RecentRoomsCodec.decode("not json"))
    }
    @Test fun merge_dedupes_newest_wins_and_sorts_desc() {
        val existing = listOf(RecentRoom("abc", "hotstar", "old", 100))
        val merged = mergeRecent(existing, RecentRoom("abc", "hotstar", "new", 300))
        assertEquals(1, merged.size)
        assertEquals("new", merged[0].videoUrl)
        assertEquals(300, merged[0].lastJoined)
    }
    @Test fun merge_caps_length() {
        val many = (1..25).map { RecentRoom("r$it", "hotstar", "u$it", it.toLong()) }
        val merged = many.fold(emptyList<RecentRoom>()) { acc, r -> mergeRecent(acc, r, max = 20) }
        assertEquals(20, merged.size)
        assertEquals("r25", merged[0].roomId) // newest first
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.RecentRoomsStoreTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement RecentRoomsStore.kt**

```kotlin
package com.buddiestime.watchparty

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RecentRoom(
    val roomId: String,
    val platform: String,
    val videoUrl: String,
    val lastJoined: Long,
)

object RecentRoomsCodec {
    fun encode(list: List<RecentRoom>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("roomId", it.roomId)
                .put("platform", it.platform)
                .put("videoUrl", it.videoUrl)
                .put("lastJoined", it.lastJoined))
        }
        return arr.toString()
    }

    fun decode(json: String): List<RecentRoom> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentRoom(o.optString("roomId"), o.optString("platform"), o.optString("videoUrl"), o.optLong("lastJoined"))
        }
    } catch (e: Exception) { emptyList() }
}

fun mergeRecent(existing: List<RecentRoom>, room: RecentRoom, max: Int = 20): List<RecentRoom> =
    (listOf(room) + existing)
        .distinctBy { it.roomId }
        .sortedByDescending { it.lastJoined }
        .take(max)

private const val KEY_RECENTS = "recent_rooms"

class RecentRoomsStore(private val prefs: SharedPreferences) {
    fun all(): List<RecentRoom> = RecentRoomsCodec.decode(prefs.getString(KEY_RECENTS, "") ?: "")
    fun add(room: RecentRoom) {
        val merged = mergeRecent(all(), room)
        prefs.edit().putString(KEY_RECENTS, RecentRoomsCodec.encode(merged)).apply()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew.bat testDebugUnitTest --tests "com.buddiestime.watchparty.RecentRoomsStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/RecentRoomsStore.kt android/app/src/test/kotlin/com/buddiestime/watchparty/RecentRoomsStoreTest.kt
git commit -m "feat(android): RecentRoomsStore with pure codec + merge helpers"
```

---

## Task 9: Android — WatchPartyManager: wake-then-connect, timeouts, auto-reconnect, role handling

**Files:**

- Modify: `.../watchparty/WatchPartyManager.kt`

**Interfaces:**

- Consumes: `Config.healthUrl`, `BackoffPolicy`, existing callbacks.
- Produces (new/changed on `WatchPartyManager`):
  - New ctor callback param `onRoleAssigned` is already present; **add** `onReconnecting: (attempt: Int) -> Unit = {}`.
  - `connect(...)` now: (1) posts status "Waking up the server…", (2) polls `Config.healthUrl(serverUrl)` up to 60s on a background thread, (3) opens the WS, (4) on unexpected close/failure schedules a backoff reconnect reusing the same params; a user `disconnect()` sets an `intentionalClose` flag that suppresses reconnect.
  - Handle incoming `{type:'role'}` message → update `role`, call `onRoleAssigned(role)`.

- [ ] **Step 1: Update OkHttp client timeouts**

Replace the client builder (WatchPartyManager.kt:37-39):

```kotlin
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
```

- [ ] **Step 2: Add reconnect + health-wake state**

Add fields after `private val client = ...`:

```kotlin
    private val backoff = BackoffPolicy()
    private var reconnectAttempt = 0
    @Volatile private var intentionalClose = false
    private var lastParams: JoinParams? = null
    private val healthClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    data class JoinParams(val serverUrl: String, val roomId: String, val platform: String, val videoUrl: String, val displayName: String, val clientId: String)
```

Add the `onReconnecting` param to the constructor list (after `onVoiceParticipants`):

```kotlin
    private val onReconnecting: (attempt: Int) -> Unit = {},
```

- [ ] **Step 3: Rewrite connect() to wake then open**

Replace the body of `connect(...)` (WatchPartyManager.kt:41-97) with:

```kotlin
    fun connect(serverUrl: String, roomId: String, platform: String, videoUrl: String, displayName: String) {
        Log.d(TAG, "connect() serverUrl=$serverUrl roomId=$roomId platform=$platform videoUrl=$videoUrl displayName=\"$displayName\"")
        if (displayName.isBlank()) { Log.w(TAG, "connect aborted — displayName blank"); post { onStatusChange("Name required") }; return }

        val clientId = lastParams?.clientId ?: ("android-" + (Math.random() * 999999).toInt())
        lastParams = JoinParams(serverUrl, roomId, platform, videoUrl, displayName, clientId)
        intentionalClose = false
        openWithWake(lastParams!!)
    }

    private fun openWithWake(p: JoinParams) {
        post { onStatusChange("Waking up the server…") }
        Thread {
            val healthy = pollHealth(Config.healthUrl(p.serverUrl), 60_000)
            Log.d(TAG, "health poll result=$healthy")
            if (intentionalClose) { Log.d(TAG, "openWithWake aborted — intentional close"); return@Thread }
            if (!healthy) { post { onStatusChange("Server unreachable — retrying…"); scheduleReconnect() }; return@Thread }
            post { openSocket(p) }
        }.start()
    }

    private fun pollHealth(healthUrl: String, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !intentionalClose) {
            try {
                healthClient.newCall(Request.Builder().url(healthUrl).build()).execute().use { r ->
                    if (r.isSuccessful) return true
                }
            } catch (e: Exception) { Log.d(TAG, "health ping failed: ${e.message}") }
            try { Thread.sleep(2000) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    private fun openSocket(p: JoinParams) {
        ws?.let { Log.d(TAG, "closing existing WS before reconnect"); it.close(1000, "Reconnecting") }
        val request = Request.Builder().url(p.serverUrl).build()
        Log.d(TAG, "opening WS to ${p.serverUrl} with clientId=${p.clientId}")
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                val joinPayload = JSONObject().apply {
                    put("type", "join"); put("roomId", p.roomId); put("clientId", p.clientId)
                    put("platform", p.platform); put("videoUrl", p.videoUrl); put("displayName", p.displayName)
                }
                Log.d(TAG, "WS onOpen — sending join: $joinPayload")
                webSocket.send(joinPayload.toString())
                post { onStatusChange("Connecting…") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS onMessage raw (len=${text.length}): ${text.take(240)}")
                val msg = try { JSONObject(text) } catch (e: Exception) { Log.w(TAG, "parse failed: ${e.message}"); return }
                post { handleMessage(msg) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS onFailure: ${t.message}", t)
                ws = null; role = null
                if (intentionalClose) { post { onStatusChange("Disconnected") } } else { post { scheduleReconnect() } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS onClosed code=$code reason=$reason")
                ws = null; role = null
                if (intentionalClose) { post { onStatusChange("Disconnected") } } else { post { scheduleReconnect() } }
            }
        })
    }

    private fun scheduleReconnect() {
        if (intentionalClose) return
        reconnectAttempt++
        val delay = backoff.delayFor(reconnectAttempt)
        Log.d(TAG, "scheduleReconnect attempt=$reconnectAttempt delay=${delay}ms")
        onReconnecting(reconnectAttempt)
        onStatusChange("Reconnecting…")
        val p = lastParams ?: return
        mainHandler.postDelayed({ if (!intentionalClose) openWithWake(p) }, delay)
    }
```

- [ ] **Step 4: Handle the `role` promotion message**

In `handleMessage`'s `when (type)` block, add a branch (next to `"joined"`):

```kotlin
            "role" -> {
                role = msg.optString("role")
                Log.d(TAG, "role change → $role")
                onRoleAssigned(role!!)
                onStatusChange(if (role == "host") "● Host" else "● Guest")
            }
```

- [ ] **Step 5: Make disconnect() suppress reconnect**

Replace `disconnect()` (WatchPartyManager.kt:229-234):

```kotlin
    fun disconnect() {
        Log.d(TAG, "disconnect()")
        intentionalClose = true
        lastParams = null
        reconnectAttempt = 0
        ws?.close(1000, "User left party")
        ws = null
        role = null
    }
```

- [ ] **Step 6: Build to verify it compiles**

Run: `cd android && ./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No unit test — this is integration code verified by compile + manual smoke in Task 12.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/WatchPartyManager.kt
git commit -m "feat(android): wake-on-cold-start, longer timeouts, auto-reconnect, host-promotion"
```

---

## Task 10: Android — RoomsHomeActivity home screen (Create button, search/join, recents)

**Files:**

- Create: `android/app/src/main/res/layout/activity_rooms_home.xml`
- Create: `android/app/src/main/res/layout/item_recent_room.xml`
- Create: `.../watchparty/RoomsHomeActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**

- Consumes: `Config`, `RecentRoomsStore`, `RoomStatus`/`parseRoomStatusList`, `ServiceSelectorActivity` (Create), `MainActivity` (Join).
- Produces: launcher activity that starts `ServiceSelectorActivity` (Create) or `MainActivity` with extras `roomId`, `service`, `join=true` (Join).
- MainActivity join contract (implemented in Task 11): reads `intent.getStringExtra("roomId")` and `intent.getStringExtra("service")`, and auto-connects as guest.

- [ ] **Step 1: Create the row layout**

`android/app/src/main/res/layout/item_recent_room.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:orientation="horizontal" android:gravity="center_vertical"
    android:padding="16dp" android:background="?android:attr/selectableItemBackground">
    <View android:id="@+id/dotActive" android:layout_width="10dp" android:layout_height="10dp"
        android:layout_marginEnd="12dp" android:background="@android:color/darker_gray" />
    <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_weight="1" android:orientation="vertical">
        <TextView android:id="@+id/tvRoomId" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:textStyle="bold" android:textSize="16sp"
            android:textColor="#FFFFFF" tools:ignore="HardcodedText" />
        <TextView android:id="@+id/tvRoomSub" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:textSize="12sp" android:textColor="#A0A0C0" />
    </LinearLayout>
    <TextView android:id="@+id/tvRoomCount" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:textSize="12sp" android:textColor="#A0A0C0" />
</LinearLayout>
```

(If the `tools` namespace triggers a lint error, add `xmlns:tools="http://schemas.android.com/tools"` to the root or drop the `tools:ignore`.)

- [ ] **Step 2: Create the home layout**

`android/app/src/main/res/layout/activity_rooms_home.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:background="#0F0F1A" android:padding="24dp">

    <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="Watch Party" android:textColor="#FFFFFF" android:textSize="26sp"
        android:textStyle="bold" android:layout_marginBottom="20dp" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnCreate" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:text="Create Party"
        android:textSize="16sp" android:paddingVertical="14dp" android:layout_marginBottom="20dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:hint="Enter or search a room code" android:layout_marginBottom="8dp">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etSearch" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:imeOptions="actionGo"
            android:inputType="text" android:maxLines="1" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnJoin" style="@style/Widget.MaterialComponents.Button.OutlinedButton"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="Join room" android:layout_marginBottom="20dp" />

    <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="Recent rooms" android:textColor="#A0A0C0" android:textSize="14sp"
        android:layout_marginBottom="8dp" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvRecent" android:layout_width="match_parent"
        android:layout_height="0dp" android:layout_weight="1" />

    <TextView android:id="@+id/tvEmpty" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:text="No recent rooms yet"
        android:textColor="#606080" android:gravity="center" android:padding="24dp"
        android:visibility="gone" />
</LinearLayout>
```

- [ ] **Step 3: Add RecyclerView dependency**

In `android/app/build.gradle` `dependencies { }`, add:

```gradle
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
```

- [ ] **Step 4: Create RoomsHomeActivity.kt**

```kotlin
package com.buddiestime.watchparty

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "HWP-HOME"
private const val PREFS = "hwp_prefs"

class RoomsHomeActivity : AppCompatActivity() {
    private lateinit var store: RecentRoomsStore
    private lateinit var adapter: RecentAdapter
    private lateinit var tvEmpty: TextView
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    private var all: List<RecentRoom> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rooms_home)
        store = RecentRoomsStore(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        tvEmpty = findViewById(R.id.tvEmpty)

        val rv = findViewById<RecyclerView>(R.id.rvRecent)
        adapter = RecentAdapter { room -> joinRoom(room.roomId, room.platform) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener {
            startActivity(Intent(this, ServiceSelectorActivity::class.java))
        }
        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)
        findViewById<MaterialButton>(R.id.btnJoin).setOnClickListener { doJoinFromField(etSearch.text?.toString()) }
        etSearch.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { doJoinFromField(etSearch.text?.toString()); true } else false
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { filter(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        all = store.all()
        adapter.submit(all)
        tvEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        refreshStatuses()
    }

    private fun filter(q: String) {
        val f = if (q.isBlank()) all else all.filter { it.roomId.contains(q.trim(), ignoreCase = true) }
        adapter.submit(f)
    }

    private fun doJoinFromField(raw: String?) {
        val code = raw?.trim().orEmpty()
        if (code.isEmpty()) { Toast.makeText(this, "Enter a room code", Toast.LENGTH_SHORT).show(); return }
        val known = all.firstOrNull { it.roomId.equals(code, ignoreCase = true) }
        joinRoom(code, known?.platform ?: "hotstar")
    }

    private fun joinRoom(roomId: String, platform: String) {
        Log.d(TAG, "joinRoom roomId=$roomId platform=$platform")
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("service", platform)
            putExtra("roomId", roomId)
            putExtra("join", true)
        })
    }

    private fun refreshStatuses() {
        val ids = all.map { it.roomId }
        if (ids.isEmpty()) return
        val healthBase = Config.healthUrl().removeSuffix("/health")
        val url = "$healthBase/api/rooms/status?ids=" + ids.joinToString(",")
        Log.d(TAG, "refreshStatuses url=$url")
        http.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { Log.w(TAG, "status fetch failed: ${e.message}") }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: return
                val statuses = try { parseRoomStatusList(body) } catch (e: Exception) { Log.w(TAG, "parse: ${e.message}"); return }
                runOnUiThread { adapter.applyStatuses(statuses.associateBy { it.roomId }) }
            }
        })
    }

    // ── RecyclerView adapter ────────────────────────────────────────────────
    class RecentAdapter(val onClick: (RecentRoom) -> Unit) : RecyclerView.Adapter<RecentAdapter.VH>() {
        private var items: List<RecentRoom> = emptyList()
        private var statuses: Map<String, RoomStatus> = emptyMap()
        fun submit(list: List<RecentRoom>) { items = list; notifyDataSetChanged() }
        fun applyStatuses(m: Map<String, RoomStatus>) { statuses = m; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dotActive)
            val id: TextView = v.findViewById(R.id.tvRoomId)
            val sub: TextView = v.findViewById(R.id.tvRoomSub)
            val count: TextView = v.findViewById(R.id.tvRoomCount)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent_room, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            val st = statuses[r.roomId]
            h.id.text = r.roomId
            h.sub.text = r.platform.replaceFirstChar { it.uppercase() }
            val active = st?.active == true
            h.dot.setBackgroundColor(if (active) Color.parseColor("#1a9e6e") else Color.parseColor("#555555"))
            h.count.text = if (active) "${st?.count ?: 0} watching" else "inactive"
            h.itemView.setOnClickListener { onClick(r) }
        }
    }
}
```

- [ ] **Step 5: Make RoomsHomeActivity the launcher (manifest)**

In `android/app/src/main/AndroidManifest.xml`, add a new activity entry above `ServiceSelectorActivity` and move the `<intent-filter>` LAUNCHER block from `ServiceSelectorActivity` to it:

```xml
        <activity
            android:name=".RoomsHomeActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ServiceSelectorActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
            android:exported="false" />
```

- [ ] **Step 6: Build to verify it compiles + installs**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. (UI verified manually in Task 12.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/RoomsHomeActivity.kt android/app/src/main/res/layout/activity_rooms_home.xml android/app/src/main/res/layout/item_recent_room.xml android/app/src/main/AndroidManifest.xml android/app/build.gradle
git commit -m "feat(android): home screen with Create, room search/join, recent rooms"
```

---

## Task 11: Android — MainActivity: baked-in URL, join extras, record recents, 5s cadence

**Files:**

- Modify: `.../watchparty/MainActivity.kt`

**Interfaces:**

- Consumes: `Config.SERVER_URL`, `RecentRoomsStore`, intent extras `roomId`/`join`/`service`.
- Produces: auto-connect on launch when `join=true`; records a `RecentRoom` whenever a party is created or joined; wires `onReconnecting` status.

- [ ] **Step 1: Change host sync cadence 2000 → 5000**

In `SYNC_SCRIPT` (MainActivity.kt:89), change `}, 2000);` to `}, 5000);`.

- [ ] **Step 2: Add a RecentRoomsStore field and record on connect**

Add field near `prefs` (MainActivity.kt:53):

```kotlin
    private lateinit var recentRooms: RecentRoomsStore
```

In `onCreate` after `prefs = getSharedPreferences(...)` (MainActivity.kt:205):

```kotlin
        recentRooms = RecentRoomsStore(prefs)
```

At the end of `connectToParty(...)` after `manager?.connect(...)` (MainActivity.kt:469), record the recent room:

```kotlin
        recentRooms.add(RecentRoom(
            roomId = room,
            platform = currentService?.name ?: "hotstar",
            videoUrl = currentPageUrl,
            lastJoined = System.currentTimeMillis()
        ))
```

- [ ] **Step 3: Wire onReconnecting into the manager**

In the `WatchPartyManager(...)` constructor call (MainActivity.kt:416), add the new callback (place after `onVoiceParticipants = { ... }`):

```kotlin
            onReconnecting = { attempt ->
                Log.d(TAG, "onReconnecting attempt=$attempt")
                tvStatus.text = "Reconnecting… (#$attempt)"
                tvStatus.visibility = View.VISIBLE
            },
```

- [ ] **Step 4: Auto-connect when arriving from Home as a guest**

At the end of `onCreate` (replace the debug auto-connect block at MainActivity.kt:263-268):

```kotlin
        val testServer = intent.getStringExtra("hwp_server")
        val testRoom   = intent.getStringExtra("hwp_room")
        val joinRoomId = intent.getStringExtra("roomId")
        if (testServer != null && testRoom != null) {
            Log.d(TAG, "debug auto-connect: $testServer / $testRoom")
            connectToParty(testServer, testRoom)
        } else if (intent.getBooleanExtra("join", false) && joinRoomId != null) {
            Log.d(TAG, "home-join auto-connect: room=$joinRoomId")
            connectToParty(Config.SERVER_URL, joinRoomId)
        }
```

- [ ] **Step 5: Use baked-in URL for the in-app Create/Join dialog**

In `showJoinDialog()` the dialog currently asks for a server URL. Keep the dialog for manual override but default the server field to `Config.SERVER_URL`. In the dialog setup (MainActivity.kt:360-368), after obtaining `etServer`, add:

```kotlin
        etServer.setText(Config.SERVER_URL)
```

And in the `Create` neutral button, if the server field is blank, fall back to `Config.SERVER_URL`:

```kotlin
            .setNeutralButton("Create") { _, _ ->
                val server = etServer.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: Config.SERVER_URL
                val room = generateRoomId()
                Log.d(TAG, "Create click server=$server generatedRoom=$room")
                connectToParty(server, room)
                Toast.makeText(this, "Room created: $room", Toast.LENGTH_LONG).show()
            }
```

- [ ] **Step 6: Build to verify it compiles**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/buddiestime/watchparty/MainActivity.kt
git commit -m "feat(android): 5s cadence, home-join auto-connect, record recents, baked-in URL"
```

---

## Task 12: Full-stack verification (build + manual smoke)

**Files:** none (verification only).

- [ ] **Step 1: Run all server tests**

Run: `cd server && node --test test/*.test.js`
Expected: PASS (rooms.test.js: 4, keepalive.test.js: 1).

- [ ] **Step 2: Run all Android unit tests**

Run: `cd android && ./gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL — ConfigTest, BackoffPolicyTest, RoomStatusTest, RecentRoomsStoreTest, plus the pre-existing ChatOverlayLogicTest all pass.

- [ ] **Step 3: Build the debug APK**

Run: `cd android && ./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke (documented, run by the user)**

Document these steps in the PR/commit description; they require a device + the deployed server:

1. Cold start: with the Render server asleep, open the app → tap a recent/Create → observe "Waking up the server…" (not an immediate error) → connects within ~60s.
2. Home screen: shows Create button, search field, recent rooms with active/inactive dots + counts.
3. Create a party on device A (host) on a video; join the same room code on device B → B auto-opens A's video and stays synced (drift corrects within ~5s).
4. Kill device A (host); device B keeps playing and is auto-promoted to host (status flips to "● Host").
5. Leave both; rejoin within 5 min → room still there at the last video/position. Wait >5 min → room shows inactive.
6. Toggle airplane mode briefly on device B → status shows "Reconnecting…" then recovers to the same room/position.

- [ ] **Step 5: Final commit / branch wrap**

```bash
git add -A
git commit -m "chore: verification notes for persistent-rooms + reliable-connect"
```

---

## Self-Review (author checklist — completed)

- **Spec coverage:** A→Tasks 9 (wake/timeout/reconnect) + 4 (ping sweep); B→Tasks 1 (grace) + 2 (promotion) + 3 (status API) + 11 (5s cadence); C→Tasks 5,7,8,10,11; D unchanged behavior preserved. All spec sections mapped.
- **Placeholder scan:** No TBD/TODO; every code step has concrete code; test bodies included.
- **Type consistency:** `RecentRoom`, `RoomStatus`, `parseRoomStatusList`, `BackoffPolicy.delayFor`, `Config.healthUrl/effectiveServerUrl`, server `roomStatus`/`promoteNewHost`/`scheduleRoomExpiry`, and the `{type:'role'}` message are defined once and consumed with matching signatures across tasks.
- **Known follow-up flagged for critique:** `intentionalClose` is toggled from a background thread and the UI thread — acceptable here (`@Volatile`), but confirm during critique. RecyclerView uses `notifyDataSetChanged` (fine for small lists).
