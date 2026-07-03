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
