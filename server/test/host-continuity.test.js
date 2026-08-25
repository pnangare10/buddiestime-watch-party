// Host role must survive a reconnect.
//
// The join handler evicts a stale socket with the same clientId BEFORE assigning
// a role (so the stale socket's close handler stays quiet about a client that is
// merely reconnecting). But role was then derived from `room.size`, which the
// eviction had already changed — so a host reconnecting while a guest was still
// present came back as a guest, and the close handler skipped promotion because
// its `leaving` lookup was empty. Result: a room with zero hosts, and sync dead
// until the room emptied and the grace window expired.
//
// WatchPartyManager.scheduleReconnect drives this automatically, so any ordinary
// mobile network blip triggered it.
const { test } = require("node:test");
const assert = require("node:assert");
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

function joinRoom(wsUrl, { roomId, clientId, displayName }) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    openSockets.push(ws);
    ws.participants = [];
    ws.relayed = [];
    ws.roles = [];
    ws.on("open", () =>
      ws.send(
        JSON.stringify({
          type: "join",
          roomId,
          clientId,
          displayName,
          videoUrl: "https://hotstar.com/x",
          platform: "hotstar",
        }),
      ),
    );
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw);
      if (msg.type === "joined") resolve({ ws, joined: msg });
      if (msg.type === "participants") ws.participants.push(msg.list);
      if (msg.type === "state-update") ws.relayed.push(msg);
      if (msg.type === "role") ws.roles.push(msg.role);
    });
    ws.on("error", reject);
  });
}

const hostsIn = (list) => list.filter((p) => p.role === "host").length;

test("a host that reconnects while a guest is present stays host", async () => {
  const srv = await startServer({ port: 8151, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "C1",
      clientId: "h",
      displayName: "Ann",
    });
    const guest = await joinRoom(srv.wsUrl, {
      roomId: "C1",
      clientId: "g",
      displayName: "Bob",
    });
    assert.strictEqual(host.joined.role, "host");
    assert.strictEqual(guest.joined.role, "guest");

    // The blip: same clientId, brand new socket. The old one is never closed
    // cleanly by the client — the server evicts it.
    const host2 = await joinRoom(srv.wsUrl, {
      roomId: "C1",
      clientId: "h",
      displayName: "Ann",
    });
    await wait(500);

    assert.strictEqual(
      host2.joined.role,
      "host",
      "reconnecting host keeps its role",
    );

    const latest = guest.ws.participants.at(-1);
    assert.strictEqual(hostsIn(latest), 1, "room has exactly one host");
    assert.strictEqual(latest.length, 2, "room has exactly two members");

    // And it can actually drive playback again.
    host2.ws.send(
      JSON.stringify({
        type: "state-update",
        time: 42,
        paused: false,
        videoUrl: "https://hotstar.com/x",
      }),
    );
    await wait(300);
    assert.strictEqual(
      guest.ws.relayed.length,
      1,
      "guest receives the reconnected host's updates",
    );

    host2.ws.close();
    guest.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("a guest that reconnects stays a guest and does not displace the host", async () => {
  const srv = await startServer({ port: 8152, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "C2",
      clientId: "h",
      displayName: "Ann",
    });
    const guest = await joinRoom(srv.wsUrl, {
      roomId: "C2",
      clientId: "g",
      displayName: "Bob",
    });
    assert.strictEqual(host.joined.role, "host");

    const guest2 = await joinRoom(srv.wsUrl, {
      roomId: "C2",
      clientId: "g",
      displayName: "Bob",
    });
    await wait(500);

    assert.strictEqual(
      guest2.joined.role,
      "guest",
      "reconnecting guest stays a guest",
    );
    const latest = host.ws.participants.at(-1);
    assert.strictEqual(hostsIn(latest), 1, "room still has exactly one host");
    assert.strictEqual(
      latest.find((p) => p.id === "h").role,
      "host",
      "the original host is untouched",
    );

    host.ws.close();
    guest2.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("after a full host disconnect the promoted guest keeps the role on rejoin", async () => {
  const srv = await startServer({ port: 8153, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "C3",
      clientId: "h",
      displayName: "Ann",
    });
    const guest = await joinRoom(srv.wsUrl, {
      roomId: "C3",
      clientId: "g",
      displayName: "Bob",
    });
    assert.strictEqual(host.joined.role, "host");

    host.ws.close(); // genuine departure — promotion path
    await wait(400);
    assert.deepStrictEqual(guest.ws.roles, ["host"], "guest was promoted");

    // The old host comes back. The room already has a host, so it must join as a guest.
    const host2 = await joinRoom(srv.wsUrl, {
      roomId: "C3",
      clientId: "h",
      displayName: "Ann",
    });
    await wait(400);

    assert.strictEqual(
      host2.joined.role,
      "guest",
      "returning host does not steal the role",
    );
    const latest = guest.ws.participants.at(-1);
    assert.strictEqual(hostsIn(latest), 1, "still exactly one host");

    host2.ws.close();
    guest.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});

test("reconnecting into a room that emptied makes you host again", async () => {
  const srv = await startServer({ port: 8154, env: { ROOM_GRACE_MS: "5000" } });
  try {
    const host = await joinRoom(srv.wsUrl, {
      roomId: "C4",
      clientId: "h",
      displayName: "Ann",
    });
    assert.strictEqual(host.joined.role, "host");
    host.ws.close();
    await wait(300); // inside the grace window — room still exists

    const host2 = await joinRoom(srv.wsUrl, {
      roomId: "C4",
      clientId: "h",
      displayName: "Ann",
    });
    assert.strictEqual(host2.joined.role, "host", "empty room → host");
    host2.ws.close();
  } finally {
    closeAll();
    await srv.stop();
  }
});
