// P0-3 — WebSocket join authorisation.
//
// Before this, `roomId` was the entire credential for the WebSocket layer, while
// every HTTP endpoint already proved membership. Since roomId travels in every
// invite link, anyone who saw a link could join the couple's room: read what they
// were watching and where, read and inject chat, force-close the partner's app via
// `close-app`, mint a LiveKit voice token, and — by connecting to an idle room
// first — become host and drive their playback.
//
// Fixtures are built through the real HTTP pairing endpoints rather than by poking
// the store, so they cannot drift from the shapes production actually produces.
const { test } = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
const WebSocket = require("ws");
const { startServer } = require("./harness");

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// A failing assertion skips any close() after it, and a still-open socket keeps the
// runner's event loop alive forever (L037). Track everything; terminate in finally.
const openSockets = [];
function closeAll() {
  while (openSockets.length) {
    try {
      openSockets.pop().terminate();
    } catch {}
  }
}

function request(method, url, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const r = http.request(
      new URL(url),
      {
        method,
        headers: data
          ? {
              "Content-Type": "application/json",
              "Content-Length": Buffer.byteLength(data),
            }
          : {},
      },
      (res) => {
        let out = "";
        res.on("data", (d) => (out += d));
        res.on("end", () =>
          resolve({
            status: res.statusCode,
            json: out ? JSON.parse(out) : null,
          }),
        );
      },
    );
    r.on("error", reject);
    if (data) r.write(data);
    r.end();
  });
}
const post = (srv, p, body) => request("POST", srv.baseUrl + p, body || {});
const get = (srv, p) => request("GET", srv.baseUrl + p);

/** A real paired room, built the way the Android app builds one. */
async function makePairedRoom(srv, { withPartner = true } = {}) {
  const owner = (await post(srv, "/api/devices")).json.deviceId;
  const created = await post(srv, "/api/rooms", {
    deviceId: owner,
    roomName: "room-" + Math.random().toString(36).slice(2, 9),
    ownerProfile: { name: "Ann" },
  });
  const roomId = created.json.roomId;
  let partner = null;
  if (withPartner) {
    partner = (await post(srv, "/api/devices")).json.deviceId;
    const inv = await post(srv, `/api/rooms/${roomId}/invite`, {
      deviceId: owner,
    });
    await post(srv, `/api/rooms/${roomId}/join`, {
      deviceId: partner,
      token: inv.json.token,
    });
  }
  return { roomId, owner, partner };
}

/**
 * Attempt a join and report whichever way it ended: accepted (`joined`), refused
 * (`error` + close code), or dead. Resolving on either outcome is what lets the
 * refusal tests assert rather than hang.
 */
function attempt(wsUrl, payload) {
  return new Promise((resolve) => {
    const ws = new WebSocket(wsUrl);
    openSockets.push(ws);
    const out = { ws, joined: null, error: null, closeCode: null, relayed: [] };
    let settled = false;
    const done = () => {
      if (!settled) {
        settled = true;
        resolve(out);
      }
    };
    ws.on("open", () => ws.send(JSON.stringify({ type: "join", ...payload })));
    ws.on("message", (raw) => {
      const m = JSON.parse(raw);
      if (m.type === "joined") {
        out.joined = m;
        done();
      } else if (m.type === "error") {
        out.error = m;
      } else {
        out.relayed.push(m);
      }
    });
    ws.on("close", (code) => {
      out.closeCode = code;
      done();
    });
    ws.on("error", () => done());
    // A refusal that does not close (e.g. already-joined) still has to settle.
    setTimeout(done, 3000);
  });
}

const ENFORCE = { HWP_STORE: "fake", WS_AUTH_MODE: "enforce" };
const OBSERVE = { HWP_STORE: "fake", WS_AUTH_MODE: "observe" };

// ── the rollout switch itself ────────────────────────────────────────────────

test("/api/authmode reports what this instance is actually running", async () => {
  const srv = await startServer({ port: 8181, env: ENFORCE });
  try {
    const res = await get(srv, "/api/authmode");
    assert.strictEqual(res.status, 200);
    // The step-C gate check: confirming the flip landed must not depend on
    // catching the right line in Render's log stream.
    assert.deepStrictEqual(res.json, { mode: "enforce", fail: "open" });
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── stage 1: observe must change nothing ─────────────────────────────────────

test("observe: a non-member is logged but still allowed in", async () => {
  const srv = await startServer({ port: 8182, env: OBSERVE });
  try {
    const { roomId } = await makePairedRoom(srv);
    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "stranger",
      displayName: "Mallory",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    // This is the whole point of stage 1: deploy the check ahead of the client
    // that satisfies it, without locking anyone out in the meantime.
    assert.ok(r.joined, "observe mode must not reject");
    assert.strictEqual(r.joined.role, "host");
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── stage 2: enforce ─────────────────────────────────────────────────────────

test("enforce: the room owner is admitted", async () => {
  const srv = await startServer({ port: 8183, env: ENFORCE });
  try {
    const { roomId, owner } = await makePairedRoom(srv);
    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-1",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(r.joined, `owner was refused: ${JSON.stringify(r.error)}`);
    assert.strictEqual(r.joined.role, "host");
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: the paired partner is admitted", async () => {
  const srv = await startServer({ port: 8184, env: ENFORCE });
  try {
    const { roomId, partner } = await makePairedRoom(srv);
    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-2",
      deviceId: partner,
      displayName: "Bea",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(r.joined, `partner was refused: ${JSON.stringify(r.error)}`);
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: knowing only the roomId is no longer enough", async () => {
  // The original repro, inverted. This is the vulnerability the batch exists for.
  const srv = await startServer({ port: 8185, env: ENFORCE });
  try {
    const { roomId } = await makePairedRoom(srv);
    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "attacker",
      deviceId: "a".repeat(32), // well-formed, just not in this room
      displayName: "Mallory",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(
      r.joined,
      null,
      "a stranger must not get a joined payload",
    );
    assert.strictEqual(r.error?.reason, "not-a-room-member");
    assert.strictEqual(r.closeCode, 1008);
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: a client that sends no deviceId at all is refused", async () => {
  // i.e. an APK from before this change. Refused loudly and with a reason the app
  // can render, rather than silently misbehaving.
  const srv = await startServer({ port: 8186, env: ENFORCE });
  try {
    const { roomId } = await makePairedRoom(srv);
    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "old-apk",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(r.joined, null);
    assert.strictEqual(r.error?.reason, "not-a-room-member");
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: a room with no pairing record still works (ad-hoc/dev passthrough)", async () => {
  // This clause is what makes enforcement shippable: test-page.html, host-sim.js
  // and the rest of this suite use ad-hoc room ids that were never created through
  // POST /api/rooms. Not a bypass — an unregistered roomId is an empty unrelated
  // room, never the victim's, because every real room has a pairing record.
  const srv = await startServer({ port: 8187, env: ENFORCE });
  try {
    const r = await attempt(srv.wsUrl, {
      roomId: "ADHOC-DEV-ROOM",
      clientId: "c1",
      displayName: "Dev",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(r.joined, "an unregistered room must keep today's behaviour");
    assert.strictEqual(r.joined.role, "host");
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: a refused join mutates nothing — no room, no leaked state", async () => {
  const srv = await startServer({
    port: 8188,
    env: { ...ENFORCE, ROOM_GRACE_MS: "60000" },
  });
  try {
    const { roomId } = await makePairedRoom(srv);
    await attempt(srv.wsUrl, {
      roomId,
      clientId: "attacker",
      deviceId: "b".repeat(32),
      displayName: "Mallory",
      videoUrl: "https://evil.example/planted",
      platform: "evil",
    });
    await wait(200);

    // A rejection that had already created the room would hand the attacker's
    // videoUrl to the real couple in their `joined` payload — the very injection
    // the previous batch closed, reopened through a different door. The room is
    // not merely empty: /api/room/:id 404s because no roomState entry exists.
    const status = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(status.status, 404, "no room state was created at all");
    assert.strictEqual(status.json.videoUrl, undefined);
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: a refused join does not evict a legitimate socket sharing its clientId", async () => {
  // The join handler evicts any existing socket with the same clientId. If that
  // ran before authorisation, an attacker could kick the real partner off simply
  // by guessing "android-1" — a denial of service needing no membership at all.
  const srv = await startServer({ port: 8189, env: ENFORCE });
  try {
    const { roomId, owner } = await makePairedRoom(srv);
    const real = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-1",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(real.joined, "the legitimate member is in");

    const attacker = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-1", // deliberate collision
      deviceId: "c".repeat(32),
      displayName: "Mallory",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(attacker.joined, null);
    await wait(300);

    assert.strictEqual(
      real.ws.readyState,
      WebSocket.OPEN,
      "the real member's socket must survive the collision",
    );
    const status = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(status.json.count, 1, "still exactly one member");
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("enforce: a reconnecting host is still restored as host", async () => {
  // Regression guard on the previous batch's fix. The membership await sits right
  // before the eviction/role-restore block that fix lives in.
  const srv = await startServer({
    port: 8190,
    env: { ...ENFORCE, ROOM_GRACE_MS: "60000" },
  });
  try {
    const { roomId, owner, partner } = await makePairedRoom(srv);
    const host = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-host",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(host.joined.role, "host");
    await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-guest",
      deviceId: partner,
      displayName: "Bea",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });

    // Reconnect via eviction — a new socket claiming the same clientId while the
    // old one is still registered. This is the path the previous batch fixed, and
    // the one the membership await now sits in front of. (Terminating first would
    // be a *genuine* disconnect: the guest is promoted and the returning host
    // correctly comes back as a guest — that case is host-continuity.test.js's.)
    const again = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-host",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(
      again.joined.role,
      "host",
      "reconnecting host must not be demoted to guest",
    );
    await wait(200);
    const status = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(status.json.count, 2, "no duplicate member left behind");
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── the await window ─────────────────────────────────────────────────────────
// These run with a deliberately slow store so the window between "join arrived"
// and "membership known" is wide enough to aim at.

const SLOW = { ...ENFORCE, HWP_FAKE_STORE_DELAY_MS: "400" };

test("a frame arriving during the membership check is dropped, and the join still completes", async () => {
  const srv = await startServer({ port: 8191, env: SLOW });
  try {
    const { roomId, owner } = await makePairedRoom(srv);
    const ws = new WebSocket(srv.wsUrl);
    openSockets.push(ws);
    let joined = null;
    ws.on("message", (raw) => {
      const m = JSON.parse(raw);
      if (m.type === "joined") joined = m;
    });
    await new Promise((r) => ws.on("open", r));
    ws.send(
      JSON.stringify({
        type: "join",
        roomId,
        clientId: "android-1",
        deviceId: owner,
        displayName: "Ann",
        videoUrl: "https://hotstar.com/x",
        platform: "hotstar",
      }),
    );
    // Straight down the barrel of the await: roomId/clientId are still null here.
    ws.send(JSON.stringify({ type: "state-update", time: 42, paused: false }));
    await wait(1200);

    // The frame is dropped by the existing not-yet-joined guard — the point is
    // that it neither crashes the handler nor derails the join behind it.
    assert.ok(joined, "the join must still complete");
    assert.strictEqual(joined.role, "host");
    const status = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(status.json.active, true);
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("a socket that dies during the membership check is never added to the room", async () => {
  const srv = await startServer({
    port: 8192,
    env: { ...SLOW, ROOM_GRACE_MS: "60000" },
  });
  try {
    const { roomId, owner } = await makePairedRoom(srv);
    const ws = new WebSocket(srv.wsUrl);
    openSockets.push(ws);
    await new Promise((r) => ws.on("open", r));
    ws.send(
      JSON.stringify({
        type: "join",
        roomId,
        clientId: "android-1",
        deviceId: owner,
        displayName: "Ann",
        videoUrl: "https://hotstar.com/x",
        platform: "hotstar",
      }),
    );
    await wait(80); // inside the 400ms store delay
    ws.terminate();
    await wait(1200);

    // Inserting a dead socket would leave a member that never leaves: the room
    // never reaches size 0, never schedules expiry, and reports a phantom party.
    // The abandoned join creates no room at all, so this 404s…
    const status = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(
      status.status,
      404,
      "the abandoned join created no room",
    );

    // …and the direct probe: a real member joining afterwards must find itself
    // alone, not sharing the room with a ghost.
    const real = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-2",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(real.joined);
    await wait(200);
    const after = await get(srv, `/api/room/${roomId}`);
    assert.strictEqual(after.json.count, 1, "no ghost member left behind");
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── one socket, one room ─────────────────────────────────────────────────────

test("a second join on the same socket is refused and does not strand the first room", async () => {
  // Pre-existing bug, reproducible on the old fully-synchronous handler: the socket
  // landed in both rooms' maps while the close handler only ever cleaned up the
  // second, so the first kept a dead member, never emptied, never expired, and
  // reported a phantom live party through /api/room/:id.
  const srv = await startServer({
    port: 8193,
    env: { HWP_STORE: "fake", WS_AUTH_MODE: "enforce", ROOM_GRACE_MS: "60000" },
  });
  try {
    const first = await attempt(srv.wsUrl, {
      roomId: "ROOM-A",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/a",
      platform: "hotstar",
    });
    assert.ok(first.joined);

    let secondError = null;
    first.ws.on("message", (raw) => {
      const m = JSON.parse(raw);
      if (m.type === "error") secondError = m;
    });
    first.ws.send(
      JSON.stringify({
        type: "join",
        roomId: "ROOM-B",
        clientId: "c1",
        displayName: "Ann",
        videoUrl: "https://hotstar.com/b",
        platform: "hotstar",
      }),
    );
    await wait(400);

    assert.strictEqual(secondError?.reason, "already-joined");
    const b = await get(srv, "/api/room/ROOM-B");
    assert.strictEqual(b.status, 404, "the second room was never created");

    first.ws.terminate();
    await wait(400);
    const a = await get(srv, "/api/room/ROOM-A");
    assert.strictEqual(
      a.json.count,
      0,
      "the first room must empty when its only socket dies",
    );
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── store outage ─────────────────────────────────────────────────────────────
// No HWP_STORE=fake and no Upstash credentials — every getRoom throws
// StoreUnavailableError, exactly as a real outage would. (The warm-cache fallback
// is covered in wsauth.unit.test.js; it cannot be reached here because a server
// whose store never answers can never populate the cache in the first place.)

const NO_STORE = { UPSTASH_REDIS_REST_URL: "", UPSTASH_REDIS_REST_TOKEN: "" };

test("a store outage does not lock people out when WS_AUTH_FAIL=open", async () => {
  const srv = await startServer({
    port: 8194,
    env: { ...NO_STORE, WS_AUTH_MODE: "enforce", WS_AUTH_FAIL: "open" },
  });
  try {
    const r = await attempt(srv.wsUrl, {
      roomId: "SOME-REAL-ROOM",
      clientId: "android-1",
      deviceId: "d".repeat(32),
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    // Failing closed here would turn an Upstash blip into a total outage of the
    // app — strictly worse than the vulnerability this batch closes.
    assert.ok(r.joined, "a store outage must not brick the room");
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("WS_AUTH_FAIL=closed refuses during an outage, and says it was the store", async () => {
  const srv = await startServer({
    port: 8195,
    env: { ...NO_STORE, WS_AUTH_MODE: "enforce", WS_AUTH_FAIL: "closed" },
  });
  try {
    const r = await attempt(srv.wsUrl, {
      roomId: "SOME-REAL-ROOM",
      clientId: "android-1",
      deviceId: "d".repeat(32),
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.strictEqual(r.joined, null);
    // Not "not-a-room-member": the device may be perfectly legitimate, and an
    // operator reading this must be pointed at the store, not at the user.
    assert.strictEqual(r.error?.reason, "store-unavailable");
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── membership changes ───────────────────────────────────────────────────────

test("a partner who has just redeemed their invite can join immediately", async () => {
  // The cache trap: the owner's join caches a room whose partner slot is still
  // null. Without invalidation on redeem, the brand-new partner is a 'non-member'
  // for the whole TTL — locked out of the room seconds after pairing succeeded.
  const srv = await startServer({ port: 8196, env: ENFORCE });
  try {
    const { roomId, owner } = await makePairedRoom(srv, { withPartner: false });

    const host = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-1",
      deviceId: owner,
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(host.joined, "owner in first — room is now cached partner-less");

    const partner = (await post(srv, "/api/devices")).json.deviceId;
    const inv = await post(srv, `/api/rooms/${roomId}/invite`, {
      deviceId: owner,
    });
    const redeemed = await post(srv, `/api/rooms/${roomId}/join`, {
      deviceId: partner,
      token: inv.json.token,
    });
    assert.strictEqual(redeemed.status, 200, "pairing itself succeeded");

    const r = await attempt(srv.wsUrl, {
      roomId,
      clientId: "android-2",
      deviceId: partner,
      displayName: "Bea",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    assert.ok(
      r.joined,
      `freshly paired partner was refused: ${JSON.stringify(r.error)}`,
    );
  } finally {
    closeAll();
    await srv.stop();
  }
});
