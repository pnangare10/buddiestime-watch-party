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

function nextOfType(ws, type) {
  return new Promise((resolve) => {
    const onMsg = (raw) => {
      const msg = JSON.parse(raw);
      if (msg.type === type) {
        ws.off("message", onMsg);
        resolve(msg);
      }
    };
    ws.on("message", onMsg);
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// RC-8: a guest joining mid-playback was handed the host's last pushed timestamp
// verbatim, so it started life already behind by however long ago that push was.
test("a guest joining mid-playback receives the host's time aged forward", async () => {
  const srv = await startServer({ port: 8093 });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "AGE1",
      clientId: "h1",
      displayName: "Host",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });
    host.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 100,
        paused: false,
        videoUrl: "hotstar.com/x",
      }),
    );

    await wait(1500);

    const guest = await joinRoom(srv.wsUrl, {
      roomId: "AGE1",
      clientId: "g1",
      displayName: "Guest",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });

    assert.ok(
      guest.joined.time >= 101.4,
      `expected time aged past 101.4s, got ${guest.joined.time}`,
    );
    assert.ok(
      guest.joined.time < 103,
      `expected time near 101.5s, got ${guest.joined.time}`,
    );
    assert.strictEqual(guest.joined.paused, false);

    host.ws.close();
    guest.ws.close();
  } finally {
    await srv.stop();
  }
});

test("a paused room's time is handed over untouched", async () => {
  const srv = await startServer({ port: 8094 });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "AGE2",
      clientId: "h1",
      displayName: "Host",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });
    host.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 100,
        paused: true,
        videoUrl: "hotstar.com/x",
      }),
    );

    await wait(1500);

    const guest = await joinRoom(srv.wsUrl, {
      roomId: "AGE2",
      clientId: "g1",
      displayName: "Guest",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });

    assert.strictEqual(guest.joined.time, 100);
    assert.strictEqual(guest.joined.paused, true);

    host.ws.close();
    guest.ws.close();
  } finally {
    await srv.stop();
  }
});

// The relay path is the hot path: it must stay verbatim-fresh (age ~0) and must
// still carry the fields older clients already depend on.
test("relayed state-update reaches the guest with the host's own timestamp", async () => {
  const srv = await startServer({ port: 8095 });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "REL1",
      clientId: "h1",
      displayName: "Host",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });
    const guest = await joinRoom(srv.wsUrl, {
      roomId: "REL1",
      clientId: "g1",
      displayName: "Guest",
      videoUrl: "hotstar.com/x",
      platform: "hotstar",
    });

    const relayed = nextOfType(guest.ws, "state-update");
    host.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 250.5,
        paused: false,
        videoUrl: "hotstar.com/x",
      }),
    );
    const msg = await relayed;

    assert.ok(
      Math.abs(msg.time - 250.5) < 0.5,
      `relay should not age the host's fresh sample, got ${msg.time}`,
    );
    assert.strictEqual(msg.paused, false);
    assert.strictEqual(msg.videoUrl, "hotstar.com/x");
    assert.strictEqual(msg.platform, "hotstar");

    host.ws.close();
    guest.ws.close();
  } finally {
    await srv.stop();
  }
});
