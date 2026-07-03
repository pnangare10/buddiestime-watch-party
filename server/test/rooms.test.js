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
