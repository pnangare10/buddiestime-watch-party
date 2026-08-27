// leave-party: one device ends the movie night for the whole room. The server
// must clear the room's stored videoUrl (so the next join doesn't sync back
// to a video nobody is watching anymore) and tell every other peer so they
// can navigate home too.
const { test } = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
const WebSocket = require("ws");
const { startServer } = require("./harness");

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

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
    ws.received = [];
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
      ws.received.push(msg);
      if (msg.type === "joined") resolve({ ws, joined: msg });
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

test("leave-party clears the room's stored videoUrl", async () => {
  const srv = await startServer({ port: 8151, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "LP1",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    a.ws.send(JSON.stringify({ type: "leave-party" }));
    await wait(300);

    const status = await getJson(srv.baseUrl + "/api/room/LP1");
    assert.strictEqual(
      status.json.videoUrl,
      null,
      "videoUrl must be cleared after leave-party",
    );
    a.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("leave-party notifies the other peer with a party-left message", async () => {
  const srv = await startServer({ port: 8152, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "LP2",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    const b = await joinRoom(srv.wsUrl, {
      roomId: "LP2",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "https://hotstar.com/x",
      platform: "hotstar",
    });
    b.ws.received = []; // clear the sync-request/joined noise before asserting

    a.ws.send(JSON.stringify({ type: "leave-party" }));
    await wait(300);

    const partyLeft = b.ws.received.find((m) => m.type === "party-left");
    assert.ok(partyLeft, "peer should receive a party-left message");
    assert.strictEqual(partyLeft.name, "Ann", "server stamps who left");

    // the sender itself should NOT get its own party-left echoed back
    const echoedToSender = a.ws.received.find((m) => m.type === "party-left");
    assert.strictEqual(
      echoedToSender,
      undefined,
      "sender is excluded from the broadcast",
    );

    a.ws.close();
    b.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("a later joiner does not resync to the video that was left behind", async () => {
  const srv = await startServer({ port: 8153, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const a = await joinRoom(srv.wsUrl, {
      roomId: "LP3",
      clientId: "c1",
      displayName: "Ann",
      videoUrl: "https://hotstar.com/old-movie",
      platform: "hotstar",
    });
    a.ws.send(JSON.stringify({ type: "leave-party" }));
    await wait(300);
    a.ws.close();

    const b = await joinRoom(srv.wsUrl, {
      roomId: "LP3",
      clientId: "c2",
      displayName: "Bob",
      videoUrl: "https://hotstar.com/new-movie",
      platform: "hotstar",
    });
    assert.strictEqual(
      b.joined.videoUrl,
      null,
      "rejoining after leave-party must not resurrect the old video",
    );
    b.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});
