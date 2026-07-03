const { test } = require("node:test");
const assert = require("node:assert");
const { sweepClients } = require("../keepalive");

function fakeClient(isAlive) {
  return {
    isAlive,
    pinged: 0,
    terminated: 0,
    ping() {
      this.pinged++;
    },
    terminate() {
      this.terminated++;
    },
  };
}

test("sweep pings a live socket and flips it to pending (isAlive=false)", () => {
  const live = fakeClient(true);
  const res = sweepClients([live]);
  assert.strictEqual(live.pinged, 1);
  assert.strictEqual(live.isAlive, false, "marked pending until next pong");
  assert.strictEqual(live.terminated, 0);
  assert.strictEqual(res.pinged, 1);
});

test("sweep terminates a socket that never ponged since the last sweep", () => {
  const dead = fakeClient(false);
  const res = sweepClients([dead]);
  assert.strictEqual(dead.terminated, 1);
  assert.strictEqual(dead.pinged, 0);
  assert.strictEqual(res.terminated, 1);
});

test("a socket that pongs between sweeps survives the next sweep", () => {
  const ws = fakeClient(true);
  sweepClients([ws]); // round 1: ping, isAlive→false
  assert.strictEqual(ws.isAlive, false);
  ws.isAlive = true; // simulate the pong handler firing
  sweepClients([ws]); // round 2: still alive → ping again, not terminated
  assert.strictEqual(ws.terminated, 0);
  assert.strictEqual(ws.pinged, 2);
});
