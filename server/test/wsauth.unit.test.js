// The mode × verdict × fail-mode matrix, with no server and no I/O. `decide()` is
// pure precisely so this table can be exhaustive — the WebSocket tests in
// ws-auth.test.js then only have to prove the wiring, not re-derive the policy.
const { test } = require("node:test");
const assert = require("node:assert");
const wsauth = require("../wsauth");

// ── decide(): every combination, stated explicitly ──────────────────────────

const VERDICTS = ["member", "non-member", "passthrough", "store-error"];
const MODES = ["off", "observe", "enforce"];
const FAILS = ["open", "closed"];

// allow? keyed by `${verdict}|${mode}|${fail}`. Written out in full rather than
// computed, so a change in policy shows up here as a diff instead of silently
// agreeing with whatever the implementation now does.
const EXPECTED = {
  // off — never blocks anything, whatever the store said
  "member|off|open": true,
  "member|off|closed": true,
  "non-member|off|open": true,
  "non-member|off|closed": true,
  "passthrough|off|open": true,
  "passthrough|off|closed": true,
  "store-error|off|open": true,
  "store-error|off|closed": true,

  // observe — verifies and logs, but always lets the join through
  "member|observe|open": true,
  "member|observe|closed": true,
  "non-member|observe|open": true,
  "non-member|observe|closed": true,
  "passthrough|observe|open": true,
  "passthrough|observe|closed": true,
  "store-error|observe|open": true,
  "store-error|observe|closed": true,

  // enforce — the only mode that ever refuses
  "member|enforce|open": true,
  "member|enforce|closed": true,
  "non-member|enforce|open": false,
  "non-member|enforce|closed": false,
  "passthrough|enforce|open": true, // room has no pairing record → ad-hoc/dev room
  "passthrough|enforce|closed": true,
  "store-error|enforce|open": true, // an Upstash blip must not brick the app
  "store-error|enforce|closed": false, // …unless the operator asked for the opposite
};

for (const verdict of VERDICTS) {
  for (const mode of MODES) {
    for (const fail of FAILS) {
      const key = `${verdict}|${mode}|${fail}`;
      test(`decide: ${key} → allow=${EXPECTED[key]}`, () => {
        const got = wsauth.decide(verdict, mode, fail);
        assert.strictEqual(got.allow, EXPECTED[key]);
        if (!got.allow)
          assert.ok(got.reason, "a refusal must carry a reason for the client");
      });
    }
  }
}

test("a refused non-member is told exactly why", () => {
  assert.strictEqual(
    wsauth.decide("non-member", "enforce", "open").reason,
    "not-a-room-member",
  );
});

test("a refusal caused by the store is not blamed on the client", () => {
  // Distinct reason: the device may be perfectly legitimate and the operator
  // needs to see 'store-unavailable' in the logs, not a membership failure.
  assert.strictEqual(
    wsauth.decide("store-error", "enforce", "closed").reason,
    "store-unavailable",
  );
});

test("an unknown mode is treated as the safest useful default, not as 'off'", () => {
  // A typo in the Render env var must not silently disable authentication.
  const got = wsauth.decide("non-member", "enfroce", "open");
  assert.strictEqual(got.allow, true, "unknown mode falls back to observe");
});

// ── checkMembership(): verdicts, caching, and store failure ─────────────────

function fakeStore(rooms, opts = {}) {
  return {
    calls: 0,
    async getRoom(id) {
      this.calls++;
      if (opts.throwOnGet) throw new Error("upstash unreachable");
      return rooms[id] ?? null;
    },
  };
}

const ROOM = { ownerDeviceId: "own-1", partnerDeviceId: "part-1" };

test("owner and partner are members; anyone else is not", async () => {
  wsauth._clearCache();
  const store = fakeStore({ R: ROOM });
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", "own-1")).verdict,
    "member",
  );
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", "part-1")).verdict,
    "member",
  );
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", "stranger")).verdict,
    "non-member",
  );
});

test("a missing deviceId is a non-member, never a match on a null slot", async () => {
  wsauth._clearCache();
  // The trap: a room with no partner yet has partnerDeviceId === null. An old APK
  // sends no deviceId at all, which arrives as null — null === null must not pass.
  const store = fakeStore({
    R: { ownerDeviceId: "own-1", partnerDeviceId: null },
  });
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", null)).verdict,
    "non-member",
  );
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", undefined)).verdict,
    "non-member",
  );
});

test("a room with no pairing record is passthrough, and is not cached", async () => {
  wsauth._clearCache();
  const rooms = {};
  const store = fakeStore(rooms);
  assert.strictEqual(
    (await wsauth.checkMembership(store, "ADHOC", "x")).verdict,
    "passthrough",
  );
  // Caching a negative would keep a room created one second later invisible for
  // the whole TTL, so every passthrough must re-ask the store.
  rooms.ADHOC = ROOM;
  assert.strictEqual(
    (await wsauth.checkMembership(store, "ADHOC", "own-1")).verdict,
    "member",
  );
});

test("a second check inside the TTL is served from cache without touching the store", async () => {
  wsauth._clearCache();
  const store = fakeStore({ R: ROOM });
  const first = await wsauth.checkMembership(store, "R", "own-1", 1000);
  const second = await wsauth.checkMembership(
    store,
    "R",
    "own-1",
    1000 + 30_000,
  );
  assert.strictEqual(first.cached, false);
  assert.strictEqual(second.cached, true);
  assert.strictEqual(store.calls, 1, "one store read for two checks");
});

test("the cache expires after its TTL", async () => {
  wsauth._clearCache();
  const store = fakeStore({ R: ROOM });
  await wsauth.checkMembership(store, "R", "own-1", 1000);
  const later = await wsauth.checkMembership(
    store,
    "R",
    "own-1",
    1000 + wsauth.CACHE_TTL_MS + 1,
  );
  assert.strictEqual(later.cached, false);
  assert.strictEqual(store.calls, 2);
});

test("invalidate() drops a room so a freshly redeemed partner is seen at once", async () => {
  wsauth._clearCache();
  const rooms = { R: { ownerDeviceId: "own-1", partnerDeviceId: null } };
  const store = fakeStore(rooms);
  // Owner joins → the room, with an empty partner slot, is now cached.
  await wsauth.checkMembership(store, "R", "own-1", 1000);
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", "part-1", 1100)).verdict,
    "non-member",
  );
  // Partner redeems the invite. Without invalidation they stay locked out for 60s.
  rooms.R.partnerDeviceId = "part-1";
  wsauth.invalidate("R");
  assert.strictEqual(
    (await wsauth.checkMembership(store, "R", "part-1", 1200)).verdict,
    "member",
  );
});

test("a store outage falls back to the cache rather than to a verdict", async () => {
  wsauth._clearCache();
  const good = fakeStore({ R: ROOM });
  await wsauth.checkMembership(good, "R", "own-1", 1000);
  const broken = fakeStore({}, { throwOnGet: true });
  const out = await wsauth.checkMembership(broken, "R", "own-1", 1100);
  assert.strictEqual(out.verdict, "member", "warm cache still answers");
  assert.strictEqual(out.cached, true);
});

test("a store outage with a cold cache reports store-error, not non-member", async () => {
  wsauth._clearCache();
  const broken = fakeStore({}, { throwOnGet: true });
  const out = await wsauth.checkMembership(broken, "R", "own-1");
  assert.strictEqual(out.verdict, "store-error");
  // Reporting 'non-member' here would blame a legitimate device for an outage and
  // — under enforce — lock the whole room out.
  assert.notStrictEqual(out.verdict, "non-member");
});

test("an expired cache entry still rescues a join during an outage", async () => {
  wsauth._clearCache();
  const good = fakeStore({ R: ROOM });
  await wsauth.checkMembership(good, "R", "own-1", 1000);
  const broken = fakeStore({}, { throwOnGet: true });
  // Well past the TTL: the entry is too old to trust for a fast path, but during a
  // store outage a stale answer beats locking the couple out of their own room.
  const out = await wsauth.checkMembership(
    broken,
    "R",
    "own-1",
    1000 + wsauth.CACHE_TTL_MS * 10,
  );
  assert.strictEqual(out.verdict, "member");
  assert.strictEqual(out.stale, true, "flagged so the log can say so");
});
